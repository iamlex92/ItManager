"""Cable graph: tracing PC -> socket -> patch panel -> switch port chains."""

import uuid
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import Connection, Device, Port

# Devices a signal passes through without terminating: a trace continues
# through them to the next cable.
PASSIVE_TYPES = {"SOCKET", "PATCH_PANEL"}


@dataclass(frozen=True)
class Hop:
    device_id: uuid.UUID
    device_name: str
    device_type: str
    port_id: uuid.UUID
    port_name: str


class Graph:
    def __init__(self, devices: list[Device], ports: list[Port], connections: list[Connection]) -> None:
        self.devices = {d.id: d for d in devices}
        self.ports = {p.id: p for p in ports}
        self.adj: dict[uuid.UUID, list[uuid.UUID]] = {}
        for c in connections:
            self.adj.setdefault(c.port_a_id, []).append(c.port_b_id)
            self.adj.setdefault(c.port_b_id, []).append(c.port_a_id)

    def _hop(self, port_id: uuid.UUID) -> Hop:
        p = self.ports[port_id]
        d = self.devices[p.device_id]
        return Hop(d.id, d.name, d.device_type, p.id, p.port_name)

    def trace(self, port_id: uuid.UUID) -> list[Hop]:
        if port_id not in self.ports:
            return []
        hops = [self._hop(port_id)]
        visited = {port_id}
        current = port_id
        while True:
            nxt = next((n for n in self.adj.get(current, []) if n not in visited), None)
            if nxt is None:
                break
            visited.add(nxt)
            hops.append(self._hop(nxt))
            if self.devices[self.ports[nxt].device_id].device_type not in PASSIVE_TYPES:
                break
            current = nxt
        return hops

    def far_end(self, port_id: uuid.UUID) -> Hop | None:
        hops = self.trace(port_id)
        if len(hops) < 2:
            return None
        return hops[-1]

    def connected_device_name(self, port_id: uuid.UUID) -> str | None:
        end = self.far_end(port_id)
        return end.device_name if end else None

    def switch_port_for(self, device_id: uuid.UUID) -> Port | None:
        """The switch port an end device is cabled to, if any."""
        for p in self.ports.values():
            if p.device_id != device_id:
                continue
            end = self.far_end(p.id)
            if end and end.device_type == "SWITCH":
                return self.ports[end.port_id]
        return None

    def vlan_for(self, device: Device) -> int | None:
        if device.device_type == "SWITCH":
            v = (device.metadata_ or {}).get("mgmt_vlan")
            return int(v) if v is not None else None
        if device.device_type in PASSIVE_TYPES:
            return None
        sp = self.switch_port_for(device.id)
        if sp is None or sp.mode != "ACCESS":
            return None
        return sp.access_vlan


async def load_graph(session: AsyncSession) -> Graph:
    devices = list((await session.scalars(select(Device))).all())
    ports = list((await session.scalars(select(Port))).all())
    conns = list((await session.scalars(select(Connection))).all())
    return Graph(devices, ports, conns)
