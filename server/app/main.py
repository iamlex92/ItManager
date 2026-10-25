import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator
from pathlib import Path
from uuid import UUID

import httpx
from fastapi import APIRouter, Depends, FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import select

from . import __version__, crypto
from .api import (
    alerts,
    audit,
    connections,
    credentials,
    devices,
    discovery,
    floors,
    misc,
    nfc,
    notifications,
    ports,
    printers,
    runbooks,
    vlans,
    ws,
)
from .api.deps import require_token
from .config import MonitorMode, Settings, get_settings
from .db import make_engine, make_sessionmaker
from .discovery.manager import ScanManager
from .hal.base import DeviceWritesDisabled, UnsupportedVendor
from .hal.emulator import EmulatorDriver
from .hal.factory import build_driver, build_printer_driver
from .models import Credential, Device
from .runbooks.emulator import RunbookEnvironment
from .schemas import HealthOut
from .services import heartbeat, telegram, traps
from .services import printers as printer_service
from .services.realtime import Hub
from .services.switches import poller

log = logging.getLogger(__name__)
STATIC_DIR = Path(__file__).parent / "static"


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
            await crypto.encrypt_existing(s, [(Device, "snmp_community"), (Credential, "secret")])
        app.state.driver = build_driver(settings)
        if isinstance(app.state.driver, EmulatorDriver):

            async def host_lookup(ip: str) -> bool | None:
                async with app.state.sessionmaker() as s:
                    return await s.scalar(select(Device.is_online).where(Device.ip_address == ip).limit(1))

            app.state.driver.host_lookup = host_lookup
        app.state.hub = Hub()
        app.state.runbooks = RunbookEnvironment(step_delay=settings.runbook_step_delay_seconds)
        app.state.discovery = ScanManager(app.state.sessionmaker, settings)
        log.warning(
            "HAL mode: %s, device writes %s",
            settings.hal_mode.value,
            "ENABLED" if settings.device_writes_enabled else "disabled",
        )
        app.state.printer_driver = build_printer_driver(settings)
        sm, hub = app.state.sessionmaker, app.state.hub
        tasks: list[asyncio.Task] = []
        if settings.poll_interval_seconds > 0:
            tasks.append(asyncio.create_task(poller(sm, app.state.driver, hub, settings.poll_interval_seconds)))
        if settings.printer_poll_interval_seconds > 0:
            tasks.append(
                asyncio.create_task(
                    printer_service.poller(sm, app.state.printer_driver, hub, settings.printer_poll_interval_seconds)
                )
            )
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
            runbook_mode="emulator",
            secrets_encrypted=crypto.box().enabled,
            telegram_alerts=settings.telegram_enabled,
        )

    api = APIRouter(prefix="/api/v1", dependencies=[Depends(require_token)])
    routers = (floors, vlans, devices, ports, connections, alerts, misc, printers, nfc, runbooks, audit, discovery)
    for module in (*routers, credentials, notifications):
        api.include_router(module.router)

    app.include_router(public)
    app.include_router(api)
    # WebSocket checks the token itself (query param or header).
    app.include_router(ws.router, prefix="/api/v1")
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
    return app


def app_factory() -> FastAPI:
    return create_app()
