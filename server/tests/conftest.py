import asyncio
import os
from collections.abc import Iterator

import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient

from app import crypto
from app.config import HalMode, Settings
from app.db import make_engine, make_sessionmaker
from app.main import create_app
from app.seed import reset, seed

TEST_DB = os.environ.get("TEST_DATABASE_URL", "postgresql+asyncpg://netadmin:netadmin@127.0.0.1:5432/netadmin_test")
TOKEN = "test-token-0123456789abcdef"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
ROOT = os.path.dirname(os.path.dirname(__file__))


def _alembic() -> Config:
    cfg = Config(os.path.join(ROOT, "alembic.ini"))
    cfg.set_main_option("script_location", os.path.join(ROOT, "alembic"))
    cfg.attributes["database_url"] = TEST_DB
    return cfg


@pytest.fixture(scope="session", autouse=True)
def migrated_db() -> None:
    cfg = _alembic()
    # Down then up, so the downgrade path is exercised too.
    command.upgrade(cfg, "head")
    command.downgrade(cfg, "base")
    command.upgrade(cfg, "head")


async def _reseed() -> None:
    # Seed in plaintext; a test that sets SECRET_KEY gets its rows encrypted at app start.
    crypto.configure(None)
    engine = make_engine(TEST_DB)
    async with make_sessionmaker(engine)() as s:
        await reset(s)
        await seed(s)
    await engine.dispose()


def make_settings(**kw) -> Settings:
    base = dict(
        database_url=TEST_DB,
        api_token=TOKEN,
        poll_interval_seconds=0,
        emulator_flap_probability=0,
        emulator_bounce_delay_seconds=0,
        printer_poll_interval_seconds=0,
        heartbeat_interval_seconds=0,
        traffic_interval_seconds=0,
        trap_listen_port=0,
        runbook_step_delay_seconds=0,
        discovery_step_delay_seconds=0,
        _env_file=None,
    )
    base.update(kw)
    return Settings(**base)


@pytest.fixture
def seeded() -> None:
    asyncio.run(_reseed())


@pytest.fixture
def client(seeded) -> Iterator[TestClient]:
    with TestClient(create_app(make_settings()), headers=AUTH) as c:
        yield c


@pytest.fixture
def real_client(seeded) -> Iterator[TestClient]:
    """Server configured for real hardware with writes left at the default (off)."""
    with TestClient(create_app(make_settings(hal_mode=HalMode.REAL)), headers=AUTH) as c:
        yield c


def find(client: TestClient, name: str) -> dict:
    devices = client.get("/api/v1/devices", params={"q": name}).json()
    return next(d for d in devices if d["name"] == name)


def ports_of(client: TestClient, device_id: str) -> list[dict]:
    return client.get(f"/api/v1/devices/{device_id}/ports").json()


def audit_rows() -> list[tuple]:
    async def q():
        from sqlalchemy import text

        engine = make_engine(TEST_DB)
        async with engine.connect() as c:
            rows = (
                await c.execute(
                    text("select script_name, exit_code, output_log from script_audit_logs order by executed_at")
                )
            ).all()
        await engine.dispose()
        return [tuple(r) for r in rows]

    return asyncio.run(q())
