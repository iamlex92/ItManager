"""What the inventory already knows, in the shape a scan result uses: the
emulator scans it, and real results are matched against it."""

import uuid
from dataclasses import dataclass, field

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import Device
from ..services.topology import PASSIVE_TYPES, load_graph
from .model import End, FoundLink


@dataclass(frozen=True)
class KnownDevice:
    id: uuid.UUID
    name: str
    ip: str | None
    mac: str | None
    vendor: str | None
    model: str | None
    device_type: str
    online: bool
    snmp_community: str | None
    # The device's other addresses: a scan finding one of them has found this device, not a new one.
    extra_ips: tuple[str, ...] = ()


@dataclass
class Inventory:
    devices: list[KnownDevice] = field(default_factory=list)
    links: list[FoundLink] = field(default_factory=list)
    # Switch name -> port names without a cable, in port order.
    free_ports: dict[str, list[str]] = field(default_factory=dict)
    # Switch name -> its uplink port names without a cable.
    free_uplinks: dict[str, list[str]] = field(default_factory=dict)

    def by_ip(self, ip: str | None) -> KnownDevice | None:
        return next((d for d in self.devices if ip and (d.ip == ip or ip in d.extra_ips)), None)

    def by_name(self, name: str | None) -> KnownDevice | None:
        low = (name or "").lower()
        return next((d for d in self.devices if low and d.name.lower() == low), None)

    def resolve(self, name: str | None, ip: str | None) -> KnownDevice | None:
        return self.by_ip(ip) or self.by_name(name)

    def has_link(self, a: str, b: str) -> bool:
        pair = {a.lower(), b.lower()}
        return any({lk.a.name.lower(), lk.b.name.lower()} == pair for lk in self.links)


async def load_inventory(session: AsyncSession) -> Inventory:
    graph = await load_graph(session)
    inv = Inventory()
    for d in graph.devices.values():
        if d.device_type in PASSIVE_TYPES or not d.is_active:
            continue
        inv.devices.append(
            KnownDevice(
                id=d.id,
                name=d.name,
                ip=str(d.ip_address) if d.ip_address is not None else None,
                mac=str(d.mac_address) if d.mac_address else None,
                vendor=d.vendor,
                model=d.model,
                device_type=d.device_type,
                online=d.is_online,
                snmp_community=d.snmp_community,
                extra_ips=tuple(str(ip) for ip in d.extra_ips or []),
            )
        )
    known = {d.id: d for d in inv.devices}
    seen: set[frozenset] = set()
    for p in sorted(graph.ports.values(), key=lambda p: p.port_number):
        dev = graph.devices[p.device_id]
        if dev.device_type != "SWITCH" or dev.id not in known:
            continue
        end = graph.far_end(p.id)
        if end is None:
            if not graph.adj.get(p.id):
                target = inv.free_uplinks if p.is_uplink else inv.free_ports
                target.setdefault(dev.name, []).append(p.port_name)
            continue
        if end.device_id not in known:
            continue
        far = known[end.device_id]
        link = FoundLink(
            End(dev.name, p.port_name, known[dev.id].ip),
            End(far.name, end.port_name, far.ip),
            "lldp" if far.device_type == "SWITCH" else "fdb",
        )
        if link.key() not in seen:
            seen.add(link.key())
            inv.links.append(link)
    return inv


async def known_ips(session: AsyncSession) -> set[str]:
    rows = await session.execute(select(Device.ip_address, Device.extra_ips))
    return {str(ip) for main, extra in rows.all() for ip in [main, *(extra or [])] if ip is not None}
