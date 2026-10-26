"""Adds found devices and the links between them to the inventory. Writes only
to the database; nothing is sent to the devices."""

import math
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import Connection, Device, Floor, Port
from ..services.topology import PASSIVE_TYPES, load_graph
from .model import End, FoundDevice, Scan

DEVICE_TYPES = {
    "SWITCH", "WORKSTATION", "SERVER", "PRINTER", "CAMERA", "ACCESS_POINT", "ROUTER", "FIREWALL", "UPS", "PHONE",
    "VIRTUAL", "UNKNOWN",
}  # fmt: skip


@dataclass
class ImportResult:
    created: list[Device] = field(default_factory=list)
    links: list[str] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)


def default_name(dev: FoundDevice) -> str:
    return dev.name or "HOST-" + dev.ip.replace(".", "-")


async def import_found(session: AsyncSession, scan: Scan, ips: list[str], with_links: bool = True) -> ImportResult:
    out = ImportResult()
    found = {d.ip: d for d in scan.result.devices}
    floor = await session.scalar(select(Floor).order_by(Floor.floor_number, Floor.created_at).limit(1))
    width = int(floor.width_px or 2400) if floor else 2400
    height = int(floor.height_px or 1200) if floor else 1200

    existing = list((await session.scalars(select(Device))).all())
    by_ip = {str(d.ip_address): d for d in existing if d.ip_address is not None}
    names = {d.name.lower() for d in existing}
    selected: dict[str, Device] = {}
    for ip in dict.fromkeys(ips):
        dev = found.get(ip)
        if dev is None:
            out.skipped.append(f"{ip}: нет в результатах сканирования")
            continue
        if ip in by_ip:
            selected[ip] = by_ip[ip]
            continue
        name = default_name(dev)
        base, n = name, 2
        while name.lower() in names:
            name, n = f"{base}-{n}", n + 1
        names.add(name.lower())
        row = Device(
            floor_id=floor.id if floor else None,
            name=name[:100],
            vendor=dev.vendor,
            model=dev.model,
            device_type=dev.device_type if dev.device_type in DEVICE_TYPES else "UNKNOWN",
            ip_address=ip,
            mac_address=dev.mac,
            is_online=True,
            last_seen_at=datetime.now(UTC),
            metadata_={
                "discovered": {
                    "scan_id": str(scan.id),
                    "at": datetime.now(UTC).isoformat(timespec="seconds"),
                    "snmp": dev.snmp,
                    "seen_by": dev.seen_by,
                    "open_ports": dev.open_ports,
                    "description": dev.description,
                }
            },
        )
        session.add(row)
        selected[ip] = row
        by_ip[ip] = row
        out.created.append(row)
    await session.flush()

    by_name = {d.name.lower(): d for d in by_ip.values()}
    by_name.update({d.name.lower(): d for d in existing})

    def resolve(end: End) -> Device | None:
        return by_ip.get(end.ip or "") or by_name.get(end.name.lower())

    graph = await load_graph(session)
    ports_by_device: dict[uuid.UUID, list[Port]] = {}
    for p in graph.ports.values():
        ports_by_device.setdefault(p.device_id, []).append(p)
    used: dict[uuid.UUID, int] = {pid: len(adj) for pid, adj in graph.adj.items()}
    # Device pairs already cabled, also through sockets and patch panels.
    pairs = set()
    for pid, port in graph.ports.items():
        end = graph.far_end(pid)
        if end is not None:
            pairs.add(frozenset({port.device_id, end.device_id}))

    def find_port(dev: Device, name: str) -> Port | None:
        ports = ports_by_device.get(dev.id, [])
        if not name:
            # A host learned from a MAC table: its first NIC.
            return ports[0] if ports else None
        return next((p for p in ports if p.port_name.lower() == name.lower()), None)

    def make_port(dev: Device, name: str) -> Port:
        ports = ports_by_device.setdefault(dev.id, [])
        name = name or "eth0"
        port = Port(
            device_id=dev.id,
            port_number=max((p.port_number for p in ports), default=0) + 1,
            port_name=name[:50],
            is_oper_up=True,
            is_uplink=dev.device_type == "SWITCH" and name.lower().startswith("te"),
        )
        session.add(port)
        ports.append(port)
        return port

    neighbors: dict[uuid.UUID, list[Device]] = {}
    selected_ids = {d.id for d in selected.values()}
    if with_links:
        for link in scan.result.links:
            a, b = resolve(link.a), resolve(link.b)
            if a is None or b is None or a.id == b.id or not ({a.id, b.id} & selected_ids):
                continue
            label = f"{a.name} {link.a.port or '—'} ↔ {b.name} {link.b.port or '—'}"
            if frozenset({a.id, b.id}) in pairs:
                continue
            if a.device_type in PASSIVE_TYPES or b.device_type in PASSIVE_TYPES:
                continue
            pa, pb = find_port(a, link.a.port), find_port(b, link.b.port)
            if (pa and used.get(pa.id)) or (pb and used.get(pb.id)):
                out.skipped.append(f"{label}: порт уже занят другим кабелем")
                continue
            pa, pb = pa or make_port(a, link.a.port), pb or make_port(b, link.b.port)
            await session.flush()
            session.add(
                Connection(
                    port_a_id=pa.id,
                    port_b_id=pb.id,
                    cable_type="Cat6",
                    notes=f"auto: {link.source}, сканирование {datetime.now(UTC):%d.%m.%Y}",
                )
            )
            used[pa.id] = used[pb.id] = 1
            pairs.add(frozenset({a.id, b.id}))
            out.links.append(label)
            neighbors.setdefault(a.id, []).append(b)
            neighbors.setdefault(b.id, []).append(a)

    # Positions: next to a placed neighbor, otherwise in a row along the bottom of the plan.
    placed_near: dict[uuid.UUID, int] = {}
    pending = list(out.created)
    while pending:
        progress = False
        for dev in list(pending):
            anchor = next((n for n in neighbors.get(dev.id, []) if n.pos_x is not None), None)
            if anchor is None:
                continue
            k = placed_near.get(anchor.id, 0)
            placed_near[anchor.id] = k + 1
            angle = math.pi / 2 + k * math.pi / 3
            dev.pos_x = round(min(max(float(anchor.pos_x) + 110 * math.cos(angle), 40), width - 40), 2)
            dev.pos_y = round(min(max(float(anchor.pos_y) + 110 * math.sin(angle), 40), height - 40), 2)
            pending.remove(dev)
            progress = True
        if not progress:
            break
    per_row = max(1, (width - 160) // 120)
    for row, dev in enumerate(pending):
        dev.pos_x = 120 + (row % per_row) * 120
        dev.pos_y = height - 90 - (row // per_row) * 90
    await session.commit()
    return out
