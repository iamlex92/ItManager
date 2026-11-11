"""Cable graph: tracing PC -> socket -> patch panel -> switch port chains."""

import time
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
        self.cables: dict[frozenset[uuid.UUID], str] = {}
        self.cable_ids: dict[frozenset[uuid.UUID], uuid.UUID] = {}
        for c in connections:
            self.adj.setdefault(c.port_a_id, []).append(c.port_b_id)
            self.adj.setdefault(c.port_b_id, []).append(c.port_a_id)
            self.cables[frozenset((c.port_a_id, c.port_b_id))] = c.cable_type
            self.cable_ids[frozenset((c.port_a_id, c.port_b_id))] = c.id

    def cable_types_along(self, port_ids: list[uuid.UUID]) -> list[str]:
        """Cable types of each cable on a traced path, in order, repeats folded."""
        out: list[str] = []
        for a, b in zip(port_ids, port_ids[1:], strict=False):
            kind = self.cables.get(frozenset((a, b)))
            if kind and (not out or out[-1] != kind):
                out.append(kind)
        return out

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

    def _walk(self, start: uuid.UUID, visited: set[uuid.UUID]) -> list[uuid.UUID]:
        """From [start] on along the cables, through sockets and patch panels, to the first active device."""
        out = [start]
        visited.add(start)
        current = start
        while self.devices[self.ports[current].device_id].device_type in PASSIVE_TYPES:
            nxt = next((n for n in self.adj.get(current, []) if n not in visited), None)
            if nxt is None:
                break
            visited.add(nxt)
            out.append(nxt)
            current = nxt
        return out

    def path(self, port_id: uuid.UUID) -> list[uuid.UUID]:
        """The whole line this port is on, end to end: for a socket or patch panel port both of its sides (the
        switch on one, the computer on the other), for a port of an active device what its cable reaches."""
        if port_id not in self.ports:
            return []
        if self.devices[self.ports[port_id].device_id].device_type not in PASSIVE_TYPES:
            return [h.port_id for h in self.trace(port_id)]
        visited = {port_id}
        sides = [self._walk(n, visited) for n in self.adj.get(port_id, []) if n not in visited]
        # The side that ends on a switch goes last, so the line reads from the computer to the switch.
        sides.sort(key=lambda s: self.devices[self.ports[s[-1]].device_id].device_type == "SWITCH")
        before = sides[0][::-1] if sides else []
        after = [p for side in sides[1:] for p in side]
        return [*before, port_id, *after]

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


# Reads (the device list, a card, the map's search, the topology) share one graph for a few seconds instead of loading
# every device, port and cable again on each request. Writes drop it at once (see invalidate_graph); the time limit
# covers changes made behind the API's back (polling, ping, a copy from the main server).
GRAPH_TTL_SECONDS = 5.0
_cached: tuple[float, Graph] | None = None


def invalidate_graph() -> None:
    global _cached
    _cached = None


async def load_graph(session: AsyncSession, cached: bool = False) -> Graph:
    """The cable graph. [cached]: only for code that reads it; a caller that changes the devices or ports it finds
    in the graph must load a fresh one (they belong to this session)."""
    global _cached
    if cached and _cached is not None and time.monotonic() - _cached[0] < GRAPH_TTL_SECONDS:
        return _cached[1]
    devices = list((await session.scalars(select(Device))).all())
    ports = list((await session.scalars(select(Port))).all())
    conns = list((await session.scalars(select(Connection))).all())
    graph = Graph(devices, ports, conns)
    if cached:
        _cached = (time.monotonic(), graph)
    return graph


# Not part of the network picture: cable plant and cabinets.
NOT_NODES = PASSIVE_TYPES | {"RACK"}


def network_links(graph: Graph) -> list[dict]:
    """Every pair of active devices a cable joins, traced through sockets and patch panels.

    One entry per pair of ports; a device cabled to itself through the plant is left out."""
    seen: set[frozenset[uuid.UUID]] = set()
    out: list[dict] = []
    for port in graph.ports.values():
        device = graph.devices.get(port.device_id)
        if device is None or device.device_type in NOT_NODES or port.id not in graph.adj:
            continue
        hops = graph.trace(port.id)
        if len(hops) < 2:
            continue
        end = hops[-1]
        if end.device_type in NOT_NODES or end.device_id == device.id:
            continue
        key = frozenset((port.id, end.port_id))
        if key in seen:
            continue
        seen.add(key)
        a, b = graph.ports[port.id], graph.ports[end.port_id]
        switch_side = [p for p in (a, b) if graph.devices[p.device_id].device_type == "SWITCH"]
        out.append(
            {
                "a_id": device.id,
                "b_id": end.device_id,
                "a_port": a.port_name,
                "b_port": b.port_name,
                "via": [h.device_name for h in hops[1:-1]],
                "cable_types": graph.cable_types_along([h.port_id for h in hops]),
                "is_up": all(p.is_admin_up and p.is_oper_up for p in switch_side) if switch_side else None,
                "speed_mbps": min(p.speed_mbps for p in switch_side) if switch_side else None,
                "connection_ids": [
                    graph.cable_ids[frozenset((x.port_id, y.port_id))]
                    for x, y in zip(hops, hops[1:], strict=False)
                    if frozenset((x.port_id, y.port_id)) in graph.cable_ids
                ],
            }
        )
    return out
