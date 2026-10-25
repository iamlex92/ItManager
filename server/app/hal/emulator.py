"""In-memory switch emulator.

Holds per-device port state seeded from the database on first contact,
answers reads, applies writes to its own memory only, and makes random
access ports flap so the live status pipeline has something to show.
"""

import asyncio
import ipaddress
import random
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, replace

from . import commands
from .base import DeviceTarget, DiagResult, PortConfig, PortRef, PortStatus, SwitchDriver
from .commands import full_interface_name


@dataclass
class _EmuPort:
    status: PortStatus
    is_uplink: bool
    # Whether something is plugged in. A port with nothing attached never
    # comes up, whatever its admin state.
    has_peer: bool
    # >0 while the port is down because of an emulated flap.
    down_polls_left: int = 0


@dataclass
class _EmuDevice:
    target: DeviceTarget
    ports: dict[int, _EmuPort]
    unsaved_changes: bool = False


class EmulatorDriver(SwitchDriver):
    def __init__(
        self,
        flap_probability: float = 0.0,
        seed: int | None = None,
        bounce_delay: float = 2.0,
    ) -> None:
        self._devices: dict[uuid.UUID, _EmuDevice] = {}
        self._rng = random.Random(seed)
        self._flap_probability = flap_probability
        self._bounce_delay = bounce_delay
        self._lock = asyncio.Lock()
        # Set by the app: IP -> True (online) / False (offline) / None (unknown),
        # so emulated ping answers match what the map shows.
        self.host_lookup: Callable[[str], Awaitable[bool | None]] | None = None

    def _device(self, target: DeviceTarget) -> _EmuDevice:
        commands._vendor(target.vendor)
        dev = self._devices.get(target.id)
        if dev is None:
            ports = {
                p.number: _EmuPort(
                    status=PortStatus(
                        number=p.number,
                        name=p.name,
                        mode=p.mode,
                        access_vlan=p.access_vlan,
                        tagged_vlans=tuple(p.tagged_vlans),
                        is_admin_up=p.is_admin_up,
                        is_oper_up=p.is_oper_up and p.is_admin_up,
                        speed_mbps=p.speed_mbps,
                        poe_enabled=p.poe_enabled,
                        connected_mac=p.connected_mac,
                    ),
                    is_uplink=p.is_uplink,
                    has_peer=p.is_oper_up or p.connected_mac is not None,
                )
                for p in target.ports
            }
            dev = _EmuDevice(target=target, ports=ports)
            self._devices[target.id] = dev
        return dev

    def _port(self, target: DeviceTarget, port: PortRef) -> _EmuPort:
        dev = self._device(target)
        if port.number not in dev.ports:
            raise KeyError(f"{target.name} has no port {port.name}")
        return dev.ports[port.number]

    def _maybe_flap(self, dev: _EmuDevice) -> None:
        # Ports taken down by an earlier flap come back after a few polls.
        for p in dev.ports.values():
            if p.down_polls_left > 0:
                p.down_polls_left -= 1
                if p.down_polls_left == 0 and p.status.is_admin_up:
                    p.status = replace(p.status, is_oper_up=True)
        if self._flap_probability <= 0 or self._rng.random() >= self._flap_probability:
            return
        candidates = [
            p
            for p in dev.ports.values()
            if p.has_peer and not p.is_uplink and p.status.is_admin_up and p.status.is_oper_up
        ]
        if not candidates:
            return
        p = self._rng.choice(candidates)
        p.status = replace(p.status, is_oper_up=False)
        p.down_polls_left = self._rng.randint(1, 4)

    async def set_link(self, target: DeviceTarget, port_number: int, up: bool) -> None:
        """Emulate a cable being plugged in or pulled out."""
        async with self._lock:
            p = self._device(target).ports[port_number]
            p.has_peer = up
            p.down_polls_left = 0
            p.status = replace(p.status, is_oper_up=up and p.status.is_admin_up)

    async def get_port_statuses(self, target: DeviceTarget) -> list[PortStatus]:
        async with self._lock:
            dev = self._device(target)
            self._maybe_flap(dev)
            return [dev.ports[n].status for n in sorted(dev.ports)]

    async def get_running_config(self, target: DeviceTarget) -> str:
        async with self._lock:
            dev = self._device(target)
            return render_running_config(dev.target, [dev.ports[n].status for n in sorted(dev.ports)])

    async def _reachable(self, dest: str) -> bool:
        if self.host_lookup is None:
            return True
        return bool(await self.host_lookup(dest))

    async def ping(self, target: DeviceTarget, dest: str, count: int) -> DiagResult:
        self._device(target)
        ok = await self._reachable(dest)
        cmd = commands.ping_command(target.vendor, dest, count)
        if target.vendor == commands.CISCO:
            n = count if ok else 0
            lines = [
                "Type escape sequence to abort.",
                f"Sending {count}, 100-byte ICMP Echos to {dest}, timeout is 2 seconds:",
                ("!" if ok else ".") * count,
                f"Success rate is {100 * n // count} percent ({n}/{count})"
                + (", round-trip min/avg/max = 1/1/2 ms" if ok else ""),
            ]
        else:
            lines = [f"Pinging {dest} with 18 bytes of data:", ""]
            for i in range(1, count + 1):
                lines.append(
                    f"18 bytes from {dest}: icmp_seq={i}. time={self._rng.randint(0, 2)} ms" if ok else "PING: timeout"
                )
            lines += [
                "",
                f"----{dest} PING Statistics----",
                f"{count} packets transmitted, {count if ok else 0} packets received, {0 if ok else 100}% packet loss",
            ]
            if ok:
                lines.append("round-trip (ms) min/avg/max = 0/1/2")
        return DiagResult(command=cmd, output="\n".join(lines), success=ok)

    async def traceroute(self, target: DeviceTarget, dest: str) -> DiagResult:
        self._device(target)
        ok = await self._reachable(dest)
        cmd = commands.traceroute_command(target.vendor, dest)
        hops: list[str | None] = []
        dest_net = ipaddress.ip_network(f"{dest}/24", strict=False)
        same_net = target.ip is not None and ipaddress.ip_address(target.ip) in dest_net
        if not same_net:
            hops.append(str(next(dest_net.hosts())))  # SVI of the destination VLAN on the core
        hops += [dest] if ok else [None, None, None]
        cisco = target.vendor == commands.CISCO
        if cisco:
            lines = [
                "Type escape sequence to abort.",
                f"Tracing the route to {dest}",
                "VRF info: (vrf in name/id, vrf out name/id)",
            ]
        else:
            lines = [f"Tracing the route to {dest} ({dest}), 30 hops max, 18 byte packets"]
        for i, hop in enumerate(hops, start=1):
            if hop is None:
                lines.append(f"  {i}  *  *  *" if cisco else f" {i}  *  *  *")
            elif cisco:
                lines.append(f"  {i} {hop} 1 msec 1 msec 2 msec")
            else:
                lines.append(f" {i}  {hop} ({hop})  <1 ms  <1 ms  1 ms")
        return DiagResult(command=cmd, output="\n".join(lines), success=ok)

    async def apply_port_config(self, target: DeviceTarget, port: PortRef, change: PortConfig) -> list[str]:
        cmds = commands.port_config_commands(target.vendor, port.name, change)
        async with self._lock:
            p = self._port(target, port)
            s = p.status
            if change.mode is not None:
                s = replace(s, mode=change.mode)
            if change.access_vlan is not None:
                s = replace(s, access_vlan=change.access_vlan)
            if change.tagged_vlans is not None:
                s = replace(s, tagged_vlans=tuple(change.tagged_vlans))
            if change.is_admin_up is not None:
                s = replace(
                    s,
                    is_admin_up=change.is_admin_up,
                    is_oper_up=change.is_admin_up and p.has_peer,
                )
            p.status = s
            self._devices[target.id].unsaved_changes = True
        return cmds

    async def bounce_port(self, target: DeviceTarget, port: PortRef) -> list[str]:
        cmds = commands.bounce_commands(target.vendor, port.name)
        async with self._lock:
            p = self._port(target, port)
            p.status = replace(p.status, is_admin_up=False, is_oper_up=False)
        await asyncio.sleep(self._bounce_delay)
        async with self._lock:
            p.status = replace(p.status, is_admin_up=True, is_oper_up=p.has_peer)
        return cmds

    async def save_config(self, target: DeviceTarget) -> list[str]:
        cmds = commands.save_commands(target.vendor)
        async with self._lock:
            self._device(target).unsaved_changes = False
        return cmds


def render_running_config(target: DeviceTarget, ports: list[PortStatus]) -> str:
    vendor = target.vendor.upper()
    lines: list[str] = []
    if vendor == commands.CISCO:
        lines += ["!", "version 15.2", "service timestamps log datetime msec", "!"]
    lines += [f"hostname {target.name}", "!"]
    lines += ["snmp-server community public ro", "!"]
    for s in ports:
        lines.append(f"interface {full_interface_name(vendor, s.name)}")
        if s.mode == "TRUNK":
            lines.append(" switchport mode trunk")
            vl = ",".join(str(v) for v in s.tagged_vlans) or "none"
            if vendor == commands.CISCO:
                lines.append(f" switchport trunk allowed vlan {vl}")
            else:
                lines.append(f" switchport trunk allowed vlan add {vl}")
        else:
            lines.append(" switchport mode access")
            if s.access_vlan and s.access_vlan != 1:
                lines.append(f" switchport access vlan {s.access_vlan}")
        if not s.is_admin_up:
            lines.append(" shutdown")
        lines.append("!" if vendor == commands.CISCO else "exit")
    lines += ["end", ""]
    return "\n".join(lines)
