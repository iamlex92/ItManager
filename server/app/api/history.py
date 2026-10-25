"""What changed on a switch and when: port writes from the audit log plus stored config copies."""

import uuid
from typing import Any

from fastapi import APIRouter, Query
from sqlalchemy import and_, or_, select

from ..models import Device, DeviceConfig, ScriptAuditLog
from ..schemas import HistoryEntry
from .audit import SWITCH_ACTIONS
from .deps import SessionDep, not_found

router = APIRouter(prefix="/devices", tags=["history"])

# Writes only: pings and traceroutes change nothing and stay in the audit log.
CHANGE_ACTIONS = ("port_config", "port_bounce", "write_memory")


def _vlans(v: list[int] | None) -> str:
    return ",".join(map(str, v)) if v else "—"


def describe(action: str, params: dict[str, Any]) -> str:
    port = params.get("port")
    if action == "write_memory":
        return "running-config сохранён в startup-config"
    if action == "port_bounce":
        return f"{port}: shutdown и no shutdown"
    change: dict[str, Any] = params.get("change") or {}
    # Rows written before the history existed have no "before": show only the new values.
    before: dict[str, Any] = params.get("before") or {}
    parts = []
    if "mode" in change and change["mode"] != before.get("mode"):
        old = f"{str(before['mode']).lower()} → " if "mode" in before else ""
        parts.append(f"режим {old}{str(change['mode']).lower()}")
    if "access_vlan" in change and change["access_vlan"] != before.get("access_vlan"):
        old = f"{before['access_vlan'] or '—'} → " if "access_vlan" in before else ""
        parts.append(f"VLAN {old}{change['access_vlan']}")
    if "tagged_vlans" in change and change["tagged_vlans"] != before.get("tagged_vlans"):
        old = f"{_vlans(before['tagged_vlans'])} → " if "tagged_vlans" in before else ""
        parts.append(f"trunk VLAN {old}{_vlans(change['tagged_vlans'])}")
    if "is_admin_up" in change and change["is_admin_up"] != before.get("is_admin_up"):
        parts.append("включён" if change["is_admin_up"] else "выключен (shutdown)")
    return f"{port}: {', '.join(parts) if parts else 'без изменений'}"


def _kind(exit_code: int | None) -> str:
    if exit_code == 403:
        return "blocked"
    return "change" if exit_code == 0 else "failed"


@router.get("/{device_id}/history", response_model=list[HistoryEntry])
async def device_history(device_id: uuid.UUID, session: SessionDep, limit: int = Query(default=100, ge=1, le=500)):
    """Port changes (applied, blocked or failed), config saves and stored config copies, newest first."""
    device = await session.get(Device, device_id)
    if device is None:
        raise not_found("device")
    params = ScriptAuditLog.parameters
    rows = (
        await session.scalars(
            select(ScriptAuditLog)
            .where(
                ScriptAuditLog.script_name.in_(CHANGE_ACTIONS),
                or_(
                    params["device_id"].astext == str(device_id),
                    # Older rows carry only the name.
                    and_(params["device_id"].astext.is_(None), params["device"].astext == device.name),
                ),
            )
            .order_by(ScriptAuditLog.executed_at.desc())
            .limit(limit)
        )
    ).all()
    entries = [
        HistoryEntry(
            at=r.executed_at,
            kind=_kind(r.exit_code),
            action=r.script_name,
            title=SWITCH_ACTIONS.get(r.script_name, r.script_name),
            summary=describe(r.script_name, r.parameters or {}),
            actor=r.triggered_by,
            commands=[c for c in (r.output_log or "").splitlines() if c.strip()][:50],
        )
        for r in rows
    ]
    configs = (
        await session.scalars(
            select(DeviceConfig).where(DeviceConfig.device_id == device_id).order_by(DeviceConfig.captured_at)
        )
    ).all()
    previous = None
    for c in configs:
        if previous is None:
            note = "первая сохранённая копия"
        elif c.config_hash == previous:
            note = "без изменений с прошлой копии"
        else:
            note = "конфигурация изменилась с прошлой копии"
        previous = c.config_hash
        entries.append(
            HistoryEntry(
                at=c.captured_at,
                kind="config_backup",
                action="config_backup",
                title="Копия конфигурации",
                summary=f"{note} · sha256 {c.config_hash[:8]}",
                actor=c.triggered_by,
            )
        )
    entries.sort(key=lambda e: e.at, reverse=True)
    return entries[:limit]
