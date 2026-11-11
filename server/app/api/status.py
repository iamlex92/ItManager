"""GET /status: what the background work did last, read from its own traces (no extra bookkeeping in the loops)."""

from datetime import UTC, datetime
from pathlib import Path

from fastapi import APIRouter, Request
from sqlalchemy import func, select

from ..models import Device, PortTraffic
from ..services import backup
from .deps import SessionDep

router = APIRouter(prefix="/status", tags=["system"])


def _iso(value: datetime | None) -> str | None:
    return value.astimezone(UTC).isoformat(timespec="seconds") if value else None


def _newest_backup(folder: str) -> tuple[str | None, str | None]:
    if not folder:
        return None, None
    files = sorted(Path(folder).glob(f"{backup.PREFIX}*{backup.SUFFIX}"), key=lambda p: p.stat().st_mtime)
    if not files:
        return None, None
    return files[-1].name, datetime.fromtimestamp(files[-1].stat().st_mtime, UTC).isoformat(timespec="seconds")


@router.get("")
async def server_status(request: Request, session: SessionDep):
    """For each background job: whether it runs here, and when it last produced something."""
    s = request.app.state.settings
    watching = not s.sync_accept
    last_seen = await session.scalar(select(func.max(Device.last_seen_at)))
    last_traffic = await session.scalar(select(func.max(PortTraffic.sampled_at)))
    name, at = _newest_backup(s.backup_dir)
    sync = request.app.state.sync_status.as_dict()
    retention = dict(getattr(request.app.state, "retention_status", {}) or {})
    return {
        "role": "backup" if s.sync_accept else "main" if s.sync_to_backup else "",
        "jobs": [
            {"name": "ping", "enabled": watching and s.monitor_mode.value == "real",
             "last_at": _iso(last_seen), "error": None},
            {"name": "traffic", "enabled": watching and s.traffic_interval_seconds > 0,
             "last_at": _iso(last_traffic), "error": None},
            {"name": "backup", "enabled": bool(s.backup_dir), "last_at": at, "error": None, "file": name},
            {"name": "sync", "enabled": s.sync_to_backup and bool(s.backup_server_url) and not s.sync_accept,
             "last_at": sync.get("pushed_at") or sync.get("received_at"),
             "error": sync.get("push_error") or sync.get("receive_error")},
            {"name": "retention", "enabled": watching, "last_at": retention.get("at"), "error": None},
        ],
    }  # fmt: skip
