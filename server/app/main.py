import asyncio
import contextlib
import logging
import os
import re
import signal
from collections.abc import AsyncIterator
from pathlib import Path
from uuid import UUID

import httpx
from fastapi import APIRouter, Depends, FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import select

from . import __version__, crypto, serversettings
from .api import (
    alerts,
    audit,
    cliimport,
    connections,
    credentials,
    device_templates,
    devices,
    discovery,
    faceplates,
    floors,
    history,
    ipam,
    misc,
    netbox,
    nfc,
    notifications,
    pcs,
    peripherals,
    ports,
    printers,
    runbooks,
    scripts,
    topology,
    traffic,
    vlans,
    ws,
)
from .api import (
    serversettings as serversettings_api,
)
from .api.deps import require_token
from .config import (
    REAL_SWITCH_MIN_POLL_SECONDS,
    SNMP_LINK_MIN_POLL_SECONDS,
    HalMode,
    MonitorMode,
    RunbookMode,
    Settings,
    get_settings,
)
from .db import make_engine, make_sessionmaker
from .discovery.manager import ScanManager
from .hal.base import DeviceWritesDisabled, SwitchError, UnsupportedVendor
from .hal.emulator import EmulatorDriver
from .hal.factory import build_driver, build_printer_driver
from .models import Credential, Device, ServerSetting
from .pcs.backend import EmulatedPcs, RealPcs
from .pcs.service import KeyInventory
from .runbooks.emulator import RunbookEnvironment
from .runbooks.real import RealRunbooks
from .schemas import HealthOut
from .services import backup, heartbeat, linkstate, telegram, traps
from .services import printers as printer_service
from .services import traffic as traffic_service
from .services.realtime import Hub
from .services.switches import poller

log = logging.getLogger(__name__)
STATIC_DIR = Path(__file__).parent / "static"

# Requests that change the map or the inventory; the phone reloads when it hears about one.
INVENTORY_WRITE = re.compile(
    r"^/api/v1/(?:(?:floors|vlans|connections|scripts|peripherals)(?:/.*)?"
    r"|devices(?:/[^/]+)?|devices/[^/]+/faceplate(?:/.*)?|ports(?:/[^/]+)?|discovery/scans/[^/]+/import)$"
)
# The web console loads only its own files and talks only to this server.
ADMIN_HEADERS = {
    "Content-Security-Policy": (
        "default-src 'self'; img-src 'self' blob: data:; style-src 'self'; script-src 'self'; "
        "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
    ),
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-cache",
}


def create_app(settings: Settings | None = None, telegram_transport: httpx.AsyncBaseTransport | None = None) -> FastAPI:
    settings = settings or get_settings()

    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        secrets_box = crypto.configure(settings.secret_key)
        engine = make_engine(settings.database_url)
        app.state.settings = settings
        app.state.engine = engine
        app.state.sessionmaker = make_sessionmaker(engine)
        if not secrets_box.enabled:
            log.warning("SECRET_KEY is not set: SNMP communities and credentials are stored unencrypted")
        # Encrypts leftover plaintext, and refuses to start when stored secrets can't be read.
        async with app.state.sessionmaker() as s:
            await crypto.encrypt_existing(
                s, [(Device, "snmp_community"), (Credential, "secret"), (ServerSetting, "value")]
            )
            # Settings saved in «Настройки сервера» override netadmin.env; everything below is built from them.
            app.state.settings_file = serversettings.current(settings)
            serversettings.apply(settings, await serversettings.load(s))
            app.state.settings_active = serversettings.current(settings)
        if getattr(app.state, "restart", None) is None and Path("/.dockerenv").exists():
            # Docker restarts the container (restart: unless-stopped) after a clean stop.
            app.state.restart = lambda: os.kill(os.getpid(), signal.SIGTERM)
        app.state.driver = build_driver(settings, app.state.sessionmaker)
        if isinstance(app.state.driver, EmulatorDriver):

            async def host_lookup(ip: str) -> bool | None:
                async with app.state.sessionmaker() as s:
                    return await s.scalar(select(Device.is_online).where(Device.ip_address == ip).limit(1))

            app.state.driver.host_lookup = host_lookup
        app.state.hub = Hub()
        # Switch control emulated but devices monitored for real: port links are read over SNMP.
        real_links = settings.hal_mode is HalMode.EMULATOR and settings.monitor_mode is MonitorMode.REAL
        app.state.links = linkstate.SnmpLinks(settings) if real_links else None
        if settings.runbook_mode is RunbookMode.REAL:
            app.state.runbooks = RealRunbooks(settings)
            log.warning(
                "runbooks: real AD (%s), AD writes %s",
                settings.ad_ldap_url or "AD_LDAP_URL not set",
                "ENABLED" if settings.allow_runbook_writes else "disabled",
            )
        else:
            app.state.runbooks = RunbookEnvironment(step_delay=settings.runbook_step_delay_seconds)
        app.state.discovery = ScanManager(app.state.sessionmaker, settings)
        # Domain computers follow the runbooks' switch: real WinRM, or an emulator that says so.
        app.state.pcs = RealPcs(settings) if settings.pcs_real else EmulatedPcs()
        app.state.pc_keys = KeyInventory()
        log.warning(
            "HAL mode: %s, device writes %s",
            settings.hal_mode.value,
            "ENABLED" if settings.device_writes_enabled else "disabled",
        )
        app.state.printer_driver = build_printer_driver(settings)
        sm, hub = app.state.sessionmaker, app.state.hub
        tasks: list[asyncio.Task] = []
        if settings.poll_interval_seconds > 0:
            interval = settings.poll_interval_seconds
            if settings.hal_mode is HalMode.REAL:
                # Each poll is an SSH login per switch: not every 10 s.
                interval = max(interval, REAL_SWITCH_MIN_POLL_SECONDS)
                tasks.append(asyncio.create_task(poller(sm, app.state.driver, hub, interval)))
            elif settings.monitor_mode is MonitorMode.REAL:
                # Switches are not driven over SSH, but their links are real: read them over SNMP
                # instead of letting the emulator make them up.
                links = app.state.links
                interval = max(interval, SNMP_LINK_MIN_POLL_SECONDS)
                tasks.append(asyncio.create_task(linkstate.poller(sm, links, hub, interval)))
                log.warning("switch ports: link state over SNMP every %g s (HAL_MODE=emulator)", interval)
            else:
                tasks.append(asyncio.create_task(poller(sm, app.state.driver, hub, interval)))
        if settings.printer_poll_interval_seconds > 0:
            tasks.append(
                asyncio.create_task(
                    printer_service.poller(sm, app.state.printer_driver, hub, settings.printer_poll_interval_seconds)
                )
            )
        if settings.traffic_interval_seconds > 0:
            source = traffic_service.build_source(settings)
            tasks.append(asyncio.create_task(traffic_service.sampler(sm, settings, source)))
        if settings.monitor_mode is MonitorMode.REAL and settings.heartbeat_interval_seconds > 0:
            if heartbeat.ping_available():
                tasks.append(
                    asyncio.create_task(
                        heartbeat.heartbeat(
                            sm, hub, settings.heartbeat_interval_seconds, settings.heartbeat_concurrency
                        )
                    )
                )
            else:
                log.error("ICMP heartbeat disabled: no ping binary found")
        if settings.backup_dir:
            tasks.append(asyncio.create_task(backup.backup_loop(settings)))
            log.warning("database backups: every %g h to %s", settings.backup_interval_hours, settings.backup_dir)
        app.state.telegram = None
        if settings.telegram_enabled:
            bot = telegram.BotApi(
                settings.telegram_bot_token, settings.telegram_chat_id, settings.telegram_proxy, telegram_transport
            )
            app.state.telegram = bot

            async def device_name(device_id: str) -> str | None:
                async with sm() as s:
                    return await s.scalar(select(Device.name).where(Device.id == UUID(device_id)))

            notifier = telegram.TelegramNotifier(settings, hub, bot.send, device_name)
            app.state.telegram_notifier = notifier
            tasks.append(asyncio.create_task(notifier.run()))
            log.warning("Telegram alerts: on, from %s", settings.telegram_min_severity)
        else:
            log.warning("Telegram alerts: off (TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID not set)")
        trap_transport = None
        if settings.trap_listen_port > 0:
            trap_transport = await traps.start_trap_listener(
                sm, hub, settings.trap_listen_port, settings.trap_community
            )
        app.state.trap_transport = trap_transport
        log.warning("monitoring mode: %s", settings.monitor_mode.value)
        try:
            yield
        finally:
            if trap_transport:
                trap_transport.close()
            for task in tasks:
                task.cancel()
            for task in tasks:
                with contextlib.suppress(asyncio.CancelledError):
                    await task
            if app.state.telegram:
                await app.state.telegram.aclose()
            await app.state.discovery.close()
            await app.state.driver.close()
            await engine.dispose()

    app = FastAPI(
        title="NetAdmin DCIM API",
        version=__version__,
        description=(
            "Inventory, cable topology and switch management for the factory network. "
            "All /api/v1 routes except /health need `Authorization: Bearer <API_TOKEN>`."
        ),
        lifespan=lifespan,
    )

    @app.exception_handler(DeviceWritesDisabled)
    async def _writes_disabled(_: Request, exc: DeviceWritesDisabled):
        return JSONResponse(
            status_code=403, content={"detail": {"code": "device_writes_disabled", "message": str(exc)}}
        )

    @app.exception_handler(UnsupportedVendor)
    async def _unsupported(_: Request, exc: UnsupportedVendor):
        return JSONResponse(status_code=422, content={"detail": {"code": "unsupported_vendor", "message": str(exc)}})

    @app.exception_handler(SwitchError)
    async def _switch_failed(_: Request, exc: SwitchError):
        status = {"no_switch_login": 409, "switch_rejected": 422}.get(exc.code, 502)
        return JSONResponse(status_code=status, content={"detail": {"code": exc.code, "message": str(exc)}})

    @app.exception_handler(NotImplementedError)
    async def _not_impl(_: Request, exc: NotImplementedError):
        return JSONResponse(status_code=501, content={"detail": {"code": "not_implemented", "message": str(exc)}})

    public = APIRouter(prefix="/api/v1")

    @public.get("/health", response_model=HealthOut, tags=["system"])
    async def health():
        return HealthOut(
            status="ok",
            version=__version__,
            hal_mode=settings.hal_mode.value,
            device_writes_enabled=settings.device_writes_enabled,
            monitor_mode=settings.monitor_mode.value,
            discovery_mode=settings.discovery_mode.value,
            runbook_mode=settings.runbook_mode.value,
            runbook_writes_enabled=settings.runbook_writes_enabled,
            secrets_encrypted=crypto.box().enabled,
            telegram_alerts=settings.telegram_enabled,
        )

    api = APIRouter(prefix="/api/v1", dependencies=[Depends(require_token)])
    routers = (
        floors,
        vlans,
        devices,
        cliimport,
        device_templates,
        ports,
        connections,
        alerts,
        misc,
        netbox,
        ipam,
        printers,
        nfc,
        pcs,
        runbooks,
        scripts,
        audit,
        discovery,
        peripherals,
        faceplates,
        topology,
    )
    for module in (*routers, credentials, notifications, traffic, history, serversettings_api):
        api.include_router(module.router)

    app.include_router(public)
    app.include_router(api)
    # WebSocket checks the token itself (query param or header).
    app.include_router(ws.router, prefix="/api/v1")
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

    @app.middleware("http")
    async def announce_inventory_changes(request: Request, call_next):
        response = await call_next(request)
        if (
            request.method in ("POST", "PUT", "PATCH", "DELETE")
            and response.status_code < 400
            and INVENTORY_WRITE.match(request.url.path)
        ):
            # Every listener on "*" (the phone's map, the web console) reloads its copy.
            request.app.state.hub.publish(None, {"type": "inventory_changed"})
        return response

    admin_page = (STATIC_DIR / "admin" / "index.html").read_text(encoding="utf-8").replace("{{version}}", __version__)

    @app.get("/admin", include_in_schema=False)
    async def admin():
        """The web console: the same API as the phone, behind the same API token."""
        return HTMLResponse(admin_page, headers=ADMIN_HEADERS)

    @app.get("/", include_in_schema=False)
    async def root():
        return RedirectResponse("/admin")

    return app


def app_factory() -> FastAPI:
    return create_app()
