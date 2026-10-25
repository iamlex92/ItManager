"""Shared checks for endpoints that change state on a switch."""

import contextlib
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

from fastapi import HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import Settings
from ..hal.base import DeviceWritesDisabled
from ..models import Device, ScriptAuditLog
from ..services.switches import is_managed_switch


def require_confirmation(confirm: bool) -> None:
    if confirm is not True:
        raise HTTPException(
            status_code=400,
            detail={
                "code": "confirmation_required",
                "message": 'this changes the switch configuration; repeat the request with "confirm": true',
            },
        )


def require_uplink_ack(is_uplink: bool, confirm_uplink: bool) -> None:
    # Shutting or re-VLANing an uplink can cut the switch (and this server's
    # path to it) off the network, so it needs a second explicit flag.
    if is_uplink and confirm_uplink is not True:
        raise HTTPException(
            status_code=409,
            detail={
                "code": "uplink_port_protected",
                "message": 'this is an uplink port; repeat with "confirm_uplink": true if you really mean it',
            },
        )


def require_managed_switch(device: Device) -> None:
    if not is_managed_switch(device):
        raise HTTPException(
            status_code=422,
            detail={"code": "not_a_managed_switch", "message": f"{device.name} is not a Cisco/Eltex switch"},
        )


@dataclass
class AuditRecord:
    commands: list[str] = field(default_factory=list)


@contextlib.asynccontextmanager
async def audit(
    session: AsyncSession, request: Request, settings: Settings, action: str, params: dict[str, Any]
) -> AsyncIterator[AuditRecord]:
    """Write an audit log row for a device write, whether it succeeded,
    was blocked by the write guard, or failed."""
    rec = AuditRecord()
    client = request.client.host if request.client else "unknown"
    entry = ScriptAuditLog(
        script_name=action,
        triggered_by=f"api:{client}",
        parameters={**params, "hal_mode": settings.hal_mode.value},
    )
    try:
        yield rec
    except DeviceWritesDisabled as e:
        entry.exit_code = 403
        entry.output_log = f"BLOCKED: {e}"
        await _store(request, entry)
        raise
    except Exception as e:
        entry.exit_code = 1
        entry.output_log = f"FAILED: {e!r}\n" + "\n".join(rec.commands)
        await _store(request, entry)
        raise
    entry.exit_code = 0
    entry.output_log = "\n".join(rec.commands)
    await _store(request, entry)


async def _store(request: Request, entry: ScriptAuditLog) -> None:
    # Own session, so the audit row survives a rollback of the request's work.
    async with request.app.state.sessionmaker() as s:
        s.add(entry)
        await s.commit()
