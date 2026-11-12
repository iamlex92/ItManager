"""What the console opens with: what needs attention right now, and one search over everything."""

from typing import Any

from fastapi import APIRouter, Query
from sqlalchemy import String, cast, func, or_, select

from ..models import Device, DeviceAlert, Peripheral, Port, ScriptAuditLog, Vlan
from .deps import SessionDep
from .devices import _search_clause

router = APIRouter(tags=["summary"])

# Devices that have nothing to answer with: they are never "down".
_NOT_PINGED = ("SOCKET", "PATCH_PANEL", "RACK")
_LIST = 8


def _dev(d: Device) -> dict[str, Any]:
    return {
        "id": str(d.id),
        "name": d.name,
        "type": d.device_type,
        "ip": str(d.ip_address) if d.ip_address else None,
    }


@router.get("/summary")
async def summary(session: SessionDep):
    """Counts and short lists for the start screen: down devices, open events, the latest actions."""
    pinged = Device.device_type.not_in(_NOT_PINGED)
    total = await session.scalar(select(func.count()).select_from(Device).where(pinged)) or 0
    online = (
        await session.scalar(select(func.count()).select_from(Device).where(pinged, Device.is_online.is_(True))) or 0
    )
    down = (
        await session.scalars(
            select(Device)
            .where(pinged, Device.is_online.is_(False))
            .order_by(Device.last_seen_at.desc().nulls_last(), Device.name)
            .limit(_LIST)
        )
    ).all()
    open_alerts = (
        await session.scalar(select(func.count()).select_from(DeviceAlert).where(DeviceAlert.is_resolved.is_(False)))
        or 0
    )
    critical = (
        await session.scalar(
            select(func.count())
            .select_from(DeviceAlert)
            .where(DeviceAlert.is_resolved.is_(False), DeviceAlert.severity == "CRITICAL")
        )
        or 0
    )
    alerts = (
        await session.execute(
            select(DeviceAlert, Device.name)
            .join(Device, Device.id == DeviceAlert.device_id)
            .where(DeviceAlert.is_resolved.is_(False))
            .order_by(DeviceAlert.created_at.desc())
            .limit(_LIST)
        )
    ).all()
    audit = (
        await session.scalars(select(ScriptAuditLog).order_by(ScriptAuditLog.executed_at.desc()).limit(_LIST))
    ).all()
    return {
        "devices": {
            "total": total,
            "online": online,
            "down": [
                {**_dev(d), "last_seen_at": d.last_seen_at.isoformat(timespec="seconds") if d.last_seen_at else None}
                for d in down
            ],
        },
        "alerts": {
            "open": open_alerts,
            "critical": critical,
            "items": [
                {
                    "id": str(a.id),
                    "device_id": str(a.device_id),
                    "device": name,
                    "severity": a.severity,
                    "message": a.message,
                    "created_at": a.created_at.isoformat(timespec="seconds"),
                }
                for a, name in alerts
            ],
        },
        "recent": [
            {
                "title": r.script_name,
                "by": r.triggered_by,
                "at": r.executed_at.isoformat(timespec="seconds"),
                "exit_code": r.exit_code,
            }
            for r in audit
        ],
    }


@router.get("/search/all")
async def search_all(session: SessionDep, q: str = Query(min_length=2, max_length=100)):
    """Devices, ports, VLANs and peripherals in one answer. Ports are found by name, by a MAC seen on them and by the
    VLAN they carry; the VLAN by number, name or subnet."""
    like = f"%{q}%"
    devices = (await session.scalars(select(Device).where(_search_clause(q)).order_by(Device.name).limit(20))).all()
    port_rows = (
        await session.execute(
            select(Port, Device)
            .join(Device, Device.id == Port.device_id)
            .where(
                or_(
                    Port.port_name.ilike(like),
                    cast(Port.connected_mac, String).ilike(like.replace("-", ":")),
                )
            )
            .order_by(Device.name, Port.port_number)
            .limit(20)
        )
    ).all()
    vlans = (
        await session.scalars(
            select(Vlan)
            .where(
                or_(
                    cast(Vlan.vlan_id, String) == q.strip(),
                    Vlan.name.ilike(like),
                    cast(Vlan.subnet_cidr, String).ilike(like),
                )
            )
            .order_by(Vlan.vlan_id)
            .limit(10)
        )
    ).all()
    peripherals = (
        await session.execute(
            select(Peripheral, Device.name)
            .outerjoin(Device, Device.id == Peripheral.device_id)
            .where(
                or_(
                    Peripheral.model.ilike(like),
                    Peripheral.vendor.ilike(like),
                    Peripheral.serial_number.ilike(like),
                    Peripheral.inventory_number.ilike(like),
                )
            )
            .limit(10)
        )
    ).all()
    return {
        "devices": [_dev(d) for d in devices],
        "ports": [
            {"device_id": str(d.id), "device": d.name, "port": p.port_name, "mac": p.connected_mac}
            for p, d in port_rows
        ],
        "vlans": [
            {"vlan_id": v.vlan_id, "name": v.name, "subnet": str(v.subnet_cidr) if v.subnet_cidr else None}
            for v in vlans
        ],
        "peripherals": [
            {
                "id": str(p.id),
                "kind": p.kind,
                "title": " ".join(x for x in (p.vendor, p.model) if x) or p.kind,
                "serial": p.serial_number,
                "inventory": p.inventory_number,
                "device_id": str(p.device_id) if p.device_id else None,
                "device": name,
            }
            for p, name in peripherals
        ],
    }
