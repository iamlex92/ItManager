import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator
from pathlib import Path

from fastapi import APIRouter, Depends, FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from . import __version__
from .api import alerts, connections, devices, floors, misc, ports, vlans, ws
from .api.deps import require_token
from .config import Settings, get_settings
from .db import make_engine, make_sessionmaker
from .hal.base import DeviceWritesDisabled, UnsupportedVendor
from .hal.factory import build_driver
from .schemas import HealthOut
from .services.realtime import Hub
from .services.switches import poller

log = logging.getLogger(__name__)
STATIC_DIR = Path(__file__).parent / "static"


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()

    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        engine = make_engine(settings.database_url)
        app.state.settings = settings
        app.state.engine = engine
        app.state.sessionmaker = make_sessionmaker(engine)
        app.state.driver = build_driver(settings)
        app.state.hub = Hub()
        log.warning(
            "HAL mode: %s, device writes %s",
            settings.hal_mode.value,
            "ENABLED" if settings.device_writes_enabled else "disabled",
        )
        task = None
        if settings.poll_interval_seconds > 0:
            task = asyncio.create_task(
                poller(app.state.sessionmaker, app.state.driver, app.state.hub, settings.poll_interval_seconds)
            )
        try:
            yield
        finally:
            if task:
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await task
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
        )

    api = APIRouter(prefix="/api/v1", dependencies=[Depends(require_token)])
    for module in (floors, vlans, devices, ports, connections, alerts, misc):
        api.include_router(module.router)

    app.include_router(public)
    app.include_router(api)
    # WebSocket checks the token itself (query param or header).
    app.include_router(ws.router, prefix="/api/v1")
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
    return app


def app_factory() -> FastAPI:
    return create_app()
