import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, HTTPException, Request
from sqlalchemy import select

from ..cliimport.parse import (
    looks_like_running_config,
    looks_like_vlan_brief,
    normalize_if_name,
    parse_running_config,
    parse_vlan_brief,
)
from ..models import ScriptAuditLog, Vlan
from ..schemas import ConfigImportIn, ConfigImportOut, ConfigImportPort
from ..services import switches
from .deps import SessionDep, not_found

router = APIRouter(prefix="/devices", tags=["config import"])

# Reads text the user pasted and updates the server's own inventory (port mode, VLANs, shutdown, speed).
# Nothing is sent to the switch, so ALLOW_DEVICE_WRITES does not apply; applying is audited.


def _422(code: str, message: str) -> HTTPException:
    return HTTPException(status_code=422, detail={"code": code, "message": message})


@router.post("/{device_id}/config-import", response_model=ConfigImportOut)
async def import_config(device_id: uuid.UUID, body: ConfigImportIn, request: Request, session: SessionDep):
    device = await switches.load_switch(session, device_id)
    if device is None:
        raise not_found("device")
    known = set((await session.scalars(select(Vlan.vlan_id))).all())
    by_name = {normalize_if_name(p.port_name): p for p in device.ports}

    # wanted[port key] = {field: value}
    wanted: dict[str, dict] = {}
    unknown: dict[int, str] = {}
    hostname: str | None = None

    def want_vlan(vid: int) -> int | None:
        if vid in known:
            return vid
        unknown.setdefault(vid, "")
        return None

    if looks_like_running_config(body.text):
        kind = "running-config"
        cfg = parse_running_config(body.text)
        hostname = cfg.hostname
        for vid, name in cfg.vlan_names.items():
            if vid not in known:
                unknown[vid] = name
        for i in cfg.interfaces:
            key = normalize_if_name(i.name)
            w: dict = {}
            if i.mode:
                w["mode"] = i.mode
            if i.access_vlan is not None:
                w["access_vlan"] = want_vlan(i.access_vlan)
            if i.allowed_all:
                w["tagged_vlans"] = sorted(known)
            elif i.allowed_vlans is not None:
                w["tagged_vlans"] = sorted(v for v in i.allowed_vlans if want_vlan(v) is not None)
            # Only a switchport states "no shutdown" reliably; a bare interface without the word is up.
            if i.mode or i.access_vlan is not None or i.shutdown:
                w["is_admin_up"] = not i.shutdown
            if i.speed_mbps:
                w["speed_mbps"] = i.speed_mbps
            if w:
                wanted[key] = w
    elif looks_like_vlan_brief(body.text):
        kind = "vlan-brief"
        for row in parse_vlan_brief(body.text):
            if row.vlan_id not in known:
                unknown[row.vlan_id] = row.name
            for name in row.ports:
                w = wanted.setdefault(normalize_if_name(name), {})
                if row.vlan_id in known:
                    w["mode"], w["access_vlan"] = "ACCESS", row.vlan_id
    else:
        raise _422("unrecognised_text", "expected a Cisco/Eltex running-config or `show vlan brief` output")

    ports: list[ConfigImportPort] = []
    unmatched: list[str] = []
    changed_ports = 0
    for key, w in wanted.items():
        port = by_name.get(key)
        if port is None:
            unmatched.append(key)
            continue
        changes: dict[str, list] = {}
        for fld, new in w.items():
            if fld == "access_vlan" and new is None:
                continue
            old = getattr(port, fld)
            if fld == "tagged_vlans":
                old = list(old or [])
            if old != new:
                changes[fld] = [old, new]
        if changes:
            changed_ports += 1
            ports.append(ConfigImportPort(port=port.port_name, matched=True, changes=changes))
            if body.apply:
                for fld, (_, new) in changes.items():
                    setattr(port, fld, new)
    if body.apply and changed_ports:
        client = request.client.host if request.client else "unknown"
        session.add(
            ScriptAuditLog(
                script_name="config_import",
                triggered_by=f"api:{client}",
                parameters={"device": device.name, "kind": kind, "ports": changed_ports},
                executed_at=datetime.now(UTC),
                exit_code=0,
                output_log=f"Импорт конфигурации ({kind}) в учёт {device.name}: изменено портов {changed_ports}",
            )
        )
        await session.commit()
    return ConfigImportOut(
        kind=kind,
        hostname=hostname,
        applied=body.apply,
        ports=ports,
        unmatched_interfaces=sorted(unmatched),
        unknown_vlans=dict(sorted(unknown.items())),
        changed_ports=changed_ports,
    )
