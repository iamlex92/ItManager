import re
import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, HTTPException, Request
from sqlalchemy import select

from ..cliimport.parse import (
    FAMILY_SPEED,
    PHYSICAL,
    if_family,
    if_numbers,
    looks_like_running_config,
    looks_like_vlan_brief,
    normalize_if_name,
    parse_running_config,
    parse_vlan_brief,
)
from ..models import Port, ScriptAuditLog, Vlan
from ..schemas import ConfigImportIn, ConfigImportOut, ConfigImportPort
from ..services import switches
from .deps import SessionDep, not_found

router = APIRouter(prefix="/devices", tags=["config import"])

# Reads text the user pasted and updates the server's own inventory (port mode, VLANs, shutdown, speed).
# Nothing is sent to the switch, so ALLOW_DEVICE_WRITES does not apply; applying is audited.


def _422(code: str, message: str) -> HTTPException:
    return HTTPException(status_code=422, detail={"code": code, "message": message})


_UPLINK_FAMILIES = {"te", "twe", "fo", "hu"}
# Colours for VLANs added from a config, so they differ on the map's VLAN layer.
_PALETTE = [
    "#3498db",
    "#e67e22",
    "#9b59b6",
    "#e74c3c",
    "#2ecc71",
    "#1abc9c",
    "#f1c40f",
    "#34495e",
    "#ff7eb6",
    "#95a5a6",
]
_PLACEHOLDER = re.compile(r"^port(\d+)$")


def _order(name: str) -> tuple:
    """Access ports first, then uplinks, each by slot and number."""
    return (if_family(name) in _UPLINK_FAMILIES, if_numbers(name), name)


@router.post("/{device_id}/config-import", response_model=ConfigImportOut)
async def import_config(device_id: uuid.UUID, body: ConfigImportIn, request: Request, session: SessionDep):
    """Reads a pasted running-config or `show vlan brief` into the inventory: port mode, VLANs, shutdown and
    speed of existing ports; ports and VLANs the inventory does not have yet are added (on apply)."""
    device = await switches.load_switch(session, device_id)
    if device is None:
        raise not_found("device")
    known = {v.vlan_id: v for v in (await session.scalars(select(Vlan))).all()}

    # wanted[normalized port name] = {field: value}; VLANs from the text: id -> (name, subnet).
    wanted: dict[str, dict] = {}
    text_vlans: dict[int, tuple[str, str | None]] = {}
    hostname: str | None = None

    def mention(vid: int, name: str = "", subnet: str | None = None) -> int:
        old_name, old_subnet = text_vlans.get(vid, ("", None))
        text_vlans[vid] = (name or old_name, subnet or old_subnet)
        return vid

    if looks_like_running_config(body.text):
        kind = "running-config"
        cfg = parse_running_config(body.text)
        hostname = cfg.hostname
        for vid, name in cfg.vlan_names.items():
            mention(vid, name, cfg.vlan_subnets.get(vid))
        every_vlan = sorted(set(known) | set(cfg.vlan_names))
        for i in cfg.ports:
            # Cisco and Eltex print `shutdown` only for a port that is off.
            w: dict = {"is_admin_up": not i.shutdown}
            if not i.routed:
                if i.mode == "TRUNK":
                    w["mode"] = "TRUNK"
                    if i.allowed_all:
                        w["tagged_vlans"] = every_vlan
                    elif i.allowed_vlans is not None:
                        w["tagged_vlans"] = [mention(v) for v in sorted(i.allowed_vlans)]
                else:
                    # An access port without `switchport access vlan` is in VLAN 1: the default is not printed.
                    w["mode"] = "ACCESS"
                    w["access_vlan"] = mention(i.access_vlan or 1)
            if i.speed_mbps:
                w["speed_mbps"] = i.speed_mbps
            wanted[normalize_if_name(i.name)] = w
    elif looks_like_vlan_brief(body.text):
        kind = "vlan-brief"
        for row in parse_vlan_brief(body.text):
            mention(row.vlan_id, row.name)
            for name in row.ports:
                key = normalize_if_name(name)
                if if_family(key) in PHYSICAL:
                    wanted[key] = {"mode": "ACCESS", "access_vlan": row.vlan_id}
    else:
        raise _422("unrecognised_text", "expected a Cisco/Eltex running-config or `show vlan brief` output")

    new_vlans = {
        vid: name or ("default" if vid == 1 else f"VLAN {vid}")
        for vid, (name, _) in text_vlans.items()
        if vid not in known
    }

    if body.apply and new_vlans:
        # VLANs first: ports point at them.
        for vid, name in sorted(new_vlans.items()):
            color = _PALETTE[(len(known) + len(session.new)) % len(_PALETTE)]
            session.add(Vlan(vlan_id=vid, name=name[:50], subnet_cidr=text_vlans[vid][1], color_hex=color))
        await session.flush()

    by_name = {normalize_if_name(p.port_name): p for p in device.ports}
    placeholders = {int(m.group(1)): p for p in device.ports if (m := _PLACEHOLDER.match(p.port_name.lower()))}
    used_numbers = {p.port_number for p in device.ports}
    has_access_family = any(if_family(k) not in _UPLINK_FAMILIES for k in wanted)

    ports: list[ConfigImportPort] = []
    new_ports: list[str] = []
    changed_ports = 0
    for index, key in enumerate(sorted(wanted, key=_order), start=1):
        w = wanted[key]
        port = by_name.get(key)
        changes: dict[str, list] = {}
        if port is None and index in placeholders and placeholders[index].port_name.lower() == f"port{index}":
            # A port drawn on the map before its name was known: it keeps its cables and gets the real name.
            port = placeholders.pop(index)
            changes["port_name"] = [port.port_name, key]
        if port is None:
            number = index if index not in used_numbers else max(used_numbers, default=0) + 1
            used_numbers.add(number)
            family = if_family(key)
            new = {
                "port_name": key,
                "port_number": number,
                "speed_mbps": FAMILY_SPEED.get(family, 1000),
                "is_uplink": family in _UPLINK_FAMILIES and has_access_family,
                **w,
            }
            changes = {fld: [None, v] for fld, v in new.items() if fld != "port_number"}
            new_ports.append(key)
            ports.append(ConfigImportPort(port=key, matched=False, changes=changes))
            changed_ports += 1
            if body.apply:
                port = Port(device_id=device.id, is_oper_up=False, **new)
                session.add(port)
            continue
        for fld, value in w.items():
            old = getattr(port, fld)
            if fld == "tagged_vlans":
                old = sorted(old or [])
            if old != value:
                changes[fld] = [old, value]
        if changes:
            changed_ports += 1
            ports.append(ConfigImportPort(port=port.port_name, matched=True, changes=changes))
            if body.apply:
                for fld, (_, value) in changes.items():
                    setattr(port, fld, value)

    if body.apply and (changed_ports or new_vlans):
        client = request.client.host if request.client else "unknown"
        session.add(
            ScriptAuditLog(
                script_name="config_import",
                triggered_by=f"api:{client}",
                parameters={
                    "device": device.name,
                    "kind": kind,
                    "ports": changed_ports,
                    "new_ports": len(new_ports),
                    "new_vlans": sorted(new_vlans),
                },
                executed_at=datetime.now(UTC),
                exit_code=0,
                output_log=(
                    f"Импорт конфигурации ({kind}) в учёт {device.name}: изменено портов {changed_ports}"
                    f" (новых {len(new_ports)}), новых VLAN {len(new_vlans)}"
                ),
            )
        )
        await session.commit()
    return ConfigImportOut(
        kind=kind,
        hostname=hostname,
        applied=body.apply,
        ports=ports,
        unmatched_interfaces=[],
        unknown_vlans={},
        new_vlans=dict(sorted(new_vlans.items())),
        new_ports=new_ports,
        changed_ports=changed_ports,
    )
