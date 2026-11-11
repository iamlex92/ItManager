"""Old journal rows and closed events are deleted, so the tables (and the package sent to the backup server) do not
grow for ever. Open events are never deleted."""

import asyncio
import logging
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, func
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..config import Settings
from ..models import DeviceAlert, ScriptAuditLog

log = logging.getLogger(__name__)

# Not in the first minutes after a start.
FIRST_RUN_DELAY = 300
INTERVAL = 24 * 3600


async def run_once(sm: async_sessionmaker[AsyncSession], settings: Settings, now: datetime | None = None) -> dict:
    now = now or datetime.now(UTC)
    async with sm() as session:
        journal = await session.execute(
            delete(ScriptAuditLog).where(ScriptAuditLog.executed_at < now - timedelta(days=settings.audit_keep_days))
        )
        events = await session.execute(
            delete(DeviceAlert).where(
                DeviceAlert.is_resolved.is_(True),
                func.coalesce(DeviceAlert.resolved_at, DeviceAlert.created_at)
                < now - timedelta(days=settings.alerts_keep_days),
            )
        )
        await session.commit()
    return {"journal": journal.rowcount, "events": events.rowcount}


async def retention_loop(sm: async_sessionmaker[AsyncSession], settings: Settings, status: dict) -> None:
    await asyncio.sleep(FIRST_RUN_DELAY)
    while True:
        try:
            status.update(await run_once(sm, settings), at=datetime.now(UTC).isoformat(timespec="seconds"))
            if status["journal"] or status["events"]:
                log.warning(
                    "retention: %d journal rows and %d closed events deleted", status["journal"], status["events"]
                )
        except Exception:
            log.exception("retention failed")
        await asyncio.sleep(INTERVAL)
