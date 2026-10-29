from typing import Literal

from fastapi import APIRouter, Query
from sqlalchemy import select

from ..discovery.manager import IMPORT_ACTION, SCAN_ACTION
from ..models import ScriptAuditLog
from ..runbooks.catalog import BY_ID
from ..schemas import AuditOut
from .credentials import ACTIONS as SECURITY_ACTIONS
from .deps import SessionDep
from .pcs import AUDIT_PREFIX as PC_PREFIX
from .runbooks import AUDIT_PREFIX
from .vlans import ACTIONS as VLAN_ACTIONS

router = APIRouter(prefix="/audit", tags=["audit"])

SWITCH_ACTIONS = {
    "port_config": "Настройка порта",
    "port_bounce": "Перезапуск порта",
    "write_memory": "Сохранение конфигурации",
    "switch_ping": "Ping с коммутатора",
    "switch_traceroute": "Traceroute с коммутатора",
    **VLAN_ACTIONS,
}
NETWORK_ACTIONS = {
    SCAN_ACTION: "Сканирование сети",
    IMPORT_ACTION: "Добавление найденных устройств",
}


PC_ACTIONS = {
    "info": "ПК: системная информация",
    "disks": "ПК: свободное место",
    "folders": "ПК: крупные папки на C:",
    "processes": "ПК: процессы",
    "cleanup": "ПК: очистка Temp и кэша обновлений",
    "gpupdate": "ПК: обновление групповых политик",
    "reboot": "ПК: перезагрузка",
    "kill": "ПК: завершение процесса",
    "command": "ПК: команда в консоли",
    "keys": "Инвентаризация ключей",
}


GPO_PREFIX = "gpo:"
GPO_ACTIONS = {
    "status": "GPO: состояние",
    "link_add": "GPO: связь с подразделением",
    "link_set": "GPO: изменение связи",
    "link_remove": "GPO: удаление связи",
    "registry_set": "GPO: параметр реестра",
    "registry_remove": "GPO: удаление параметра",
    "create": "GPO: создание политики",
    "delete": "GPO: удаление политики",
    "apply": "GPO: применение на компьютерах",
}


def audit_out(row: ScriptAuditLog) -> AuditOut:
    if row.script_name.startswith(GPO_PREFIX):
        kind, title = "runbook", GPO_ACTIONS.get(row.script_name.removeprefix(GPO_PREFIX), row.script_name)
    elif row.script_name.startswith(PC_PREFIX):
        action = row.script_name.removeprefix(PC_PREFIX)
        kind, title = "runbook", PC_ACTIONS.get(action, row.script_name)
    elif row.script_name.startswith(AUDIT_PREFIX):
        rb = BY_ID.get(row.script_name.removeprefix(AUDIT_PREFIX))
        kind, title = "runbook", rb.title if rb else row.script_name
    elif row.script_name in NETWORK_ACTIONS:
        kind, title = "network", NETWORK_ACTIONS[row.script_name]
    elif row.script_name in SECURITY_ACTIONS:
        kind, title = "security", SECURITY_ACTIONS[row.script_name]
    else:
        kind, title = "switch", SWITCH_ACTIONS.get(row.script_name, row.script_name)
    return AuditOut(
        id=row.id,
        action=row.script_name,
        kind=kind,
        title=title,
        triggered_by=row.triggered_by,
        parameters=row.parameters,
        executed_at=row.executed_at,
        exit_code=row.exit_code,
        stdout=row.output_log,
        stderr=row.stderr_log,
        duration_ms=row.duration_ms,
    )


@router.get("", response_model=list[AuditOut])
async def list_audit(
    session: SessionDep,
    kind: Literal["all", "runbook", "switch", "network", "security"] = "all",
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
):
    """Every runbook run, switch write attempt (including blocked ones) and network scan, newest first."""
    stmt = select(ScriptAuditLog).order_by(ScriptAuditLog.executed_at.desc(), ScriptAuditLog.id)
    if kind == "runbook":
        stmt = stmt.where(
            ScriptAuditLog.script_name.startswith(AUDIT_PREFIX)
            | ScriptAuditLog.script_name.startswith(PC_PREFIX)
            | ScriptAuditLog.script_name.startswith(GPO_PREFIX)
        )
    elif kind == "network":
        stmt = stmt.where(ScriptAuditLog.script_name.in_(NETWORK_ACTIONS))
    elif kind == "security":
        stmt = stmt.where(ScriptAuditLog.script_name.in_(SECURITY_ACTIONS))
    elif kind == "switch":
        stmt = stmt.where(
            ~ScriptAuditLog.script_name.startswith(AUDIT_PREFIX),
            ~ScriptAuditLog.script_name.startswith(PC_PREFIX),
            ~ScriptAuditLog.script_name.startswith(GPO_PREFIX),
            ScriptAuditLog.script_name.not_in([*NETWORK_ACTIONS, *SECURITY_ACTIONS]),
        )
    rows = (await session.scalars(stmt.limit(limit).offset(offset))).all()
    return [audit_out(r) for r in rows]
