"""Upgrading a database that already has data, one revision at a time, ends with the data intact."""

import asyncio

from alembic import command
from alembic.script import ScriptDirectory
from sqlalchemy import text

from app.db import make_engine

from .conftest import TEST_DB, _alembic


async def _sql(statement: str, **params):
    engine = make_engine(TEST_DB)
    try:
        async with engine.begin() as conn:
            result = await conn.execute(text(statement), params)
            return result.all() if result.returns_rows else None
    finally:
        await engine.dispose()


def test_upgrade_one_revision_at_a_time_keeps_the_data():
    cfg = _alembic()
    revisions = [r.revision for r in reversed(list(ScriptDirectory.from_config(cfg).walk_revisions()))]
    assert len(revisions) > 5
    command.downgrade(cfg, "base")
    command.upgrade(cfg, revisions[0])
    asyncio.run(_sql("INSERT INTO floors (building_name, floor_number, plan_image_url) VALUES ('A', 1, '')"))
    asyncio.run(
        _sql(
            "INSERT INTO devices (name, device_type, ip_address, floor_id) "
            "SELECT 'SW-OLD', 'SWITCH', '10.9.9.1', id FROM floors LIMIT 1"
        )
    )
    asyncio.run(
        _sql(
            "INSERT INTO ports (device_id, port_number, port_name) "
            "SELECT id, 1, 'Gi1/0/1' FROM devices WHERE name = 'SW-OLD'"
        )
    )
    for rev in revisions[1:]:
        command.upgrade(cfg, rev)
    rows = asyncio.run(_sql("SELECT name, host(ip_address) FROM devices"))
    assert rows == [("SW-OLD", "10.9.9.1")]
    assert asyncio.run(_sql("SELECT count(*) FROM ports")) == [(1,)]
    # Back to an empty database at the current revision for the tests that follow.
    command.downgrade(cfg, "base")
    command.upgrade(cfg, "head")
