"""Scan answered from the inventory plus a few devices it does not know yet,
so the whole flow (find, review, add to inventory) can be tried without a
network. Nothing leaves the server."""

import asyncio
import ipaddress
from dataclasses import dataclass

from . import classify
from .inventory import Inventory
from .model import End, FoundDevice, FoundLink, Progress, ScanResult, hosts
from .probe import HostInfo

CHUNK = 16
# What each kind of device has open on the emulated network.
OPEN_PORTS = {
    "SWITCH": (22, 23, 80, 443),
    "PRINTER": (80, 443, 515, 631, 9100),
    "CAMERA": (80, 443, 554, 8000),
    "ACCESS_POINT": (22, 80, 443),
    "SERVER": (22, 135, 445, 3389, 5985),
    "WORKSTATION": (135, 445, 3389),
}


@dataclass(frozen=True)
class _Extra:
    device: FoundDevice
    community: str = "public"
    ports: tuple[int, ...] = ()


def _extras(inv: Inventory) -> tuple[list[_Extra], list[FoundLink]]:
    """Devices that answer on the emulated network but are not in the inventory:
    a new access switch behind the core, a printer and a camera on it, and a
    PC on a free port of an existing access switch."""
    switches = [d for d in inv.devices if d.device_type == "SWITCH"]
    core = next((d for d in switches if "core" in d.name.lower()), switches[0] if switches else None)
    access = [d for d in switches if d is not core and inv.free_ports.get(d.name)]
    extras = [
        _Extra(
            FoundDevice(
                ip="10.99.0.14",
                name="SW-ACC-04",
                mac="a8:f9:4b:7e:10:04",
                vendor="ELTEX",
                model="MES2324P",
                device_type="SWITCH",
                description="MES2324P AC 28-port 1G/10G Managed Switch",
                snmp=True,
            )
        ),
        _Extra(
            FoundDevice(
                ip="10.10.30.57",
                name="PRN-SKLAD",
                mac="00:17:c8:5a:30:57",
                vendor="KYOCERA",
                model="ECOSYS M2540dn",
                device_type="PRINTER",
                description="KYOCERA Document Solutions Printing System",
                snmp=True,
            )
        ),
        _Extra(
            FoundDevice(
                ip="10.10.40.77",
                name="CAM-VOROTA-2",
                mac="44:47:cc:28:40:77",
                vendor="HIKVISION",
                model="DS-2CD2143G2-I",
                device_type="CAMERA",
                description="Hikvision IP camera DS-2CD2143G2-I",
                snmp=True,
            )
        ),
        # No SNMP and ICMP blocked by the Windows firewall: found by its open ports, MAC and port from switch tables.
        _Extra(
            FoundDevice(ip="10.10.10.231", mac="3c:52:82:aa:10:e7", seen_by="tcp"),
            community="",
            ports=OPEN_PORTS["WORKSTATION"],
        ),
    ]
    links: list[FoundLink] = []
    sw4 = End("SW-ACC-04", "te1/0/1", "10.99.0.14")
    if core:
        uplinks = inv.free_uplinks.get(core.name) or inv.free_ports.get(core.name) or []
        if uplinks:
            links.append(FoundLink(End(core.name, uplinks[0], core.ip), sw4, "lldp"))
    links += [
        FoundLink(End("SW-ACC-04", "gi1/0/3", "10.99.0.14"), End("PRN-SKLAD", "", "10.10.30.57"), "fdb"),
        FoundLink(End("SW-ACC-04", "gi1/0/7", "10.99.0.14"), End("CAM-VOROTA-2", "", "10.10.40.77"), "fdb"),
    ]
    if access:
        sw = access[-1]
        links.append(FoundLink(End(sw.name, inv.free_ports[sw.name][0], sw.ip), End("", "", "10.10.10.231"), "fdb"))
    return extras, links


class EmulatedScanner:
    def __init__(self, inventory: Inventory, step_delay: float = 0.1) -> None:
        self._inv = inventory
        self._delay = step_delay

    async def scan(self, nets: list[ipaddress.IPv4Network], community: str, progress: Progress) -> ScanResult:
        inv = self._inv
        extras, extra_links = _extras(inv)
        known_ips = {d.ip for d in inv.devices if d.ip}
        # Online inventory devices answer ping; SNMP when the community matches.
        world: dict[str, tuple[FoundDevice, str, tuple[int, ...]]] = {}
        for d in inv.devices:
            if d.ip and d.online:
                answers = d.device_type in ("SWITCH", "PRINTER", "SERVER", "ACCESS_POINT", "CAMERA")
                world[d.ip] = (
                    FoundDevice(
                        ip=d.ip,
                        name=d.name,
                        mac=d.mac,
                        vendor=d.vendor,
                        model=d.model,
                        device_type=d.device_type,
                        snmp=answers,
                    ),
                    (d.snmp_community or "public") if answers else "",
                    OPEN_PORTS.get(d.device_type, ()),
                )
        # An extra already added to the inventory is answered as an inventory device.
        new_ips = set()
        for x in extras:
            if x.device.ip not in known_ips:
                world[x.device.ip] = (x.device, x.community, x.ports or OPEN_PORTS.get(x.device.device_type, ()))
                new_ips.add(x.device.ip)

        result = ScanResult()
        targets = hosts(nets)
        for i in range(0, len(targets), CHUNK):
            for ip in targets[i : i + CHUNK]:
                if ip not in world:
                    continue
                dev, dev_community, ports = world[ip]
                info = HostInfo(ip, ping=dev.seen_by == "ping", tcp=bool(ports), open_ports=set(ports))
                if dev_community and dev_community == community:
                    found = FoundDevice(**{**vars(dev), "open_ports": []})
                else:
                    # No SNMP: no name, model or switch tables; the type comes from ports and MAC.
                    found = FoundDevice(ip=ip, mac=dev.mac, seen_by=dev.seen_by)
                classify.apply(found, info)
                result.devices.append(found)
            progress(min(i + CHUNK, len(targets)))
            if self._delay:
                await asyncio.sleep(self._delay)

        found = {d.ip: d for d in result.devices}

        def visible(link: FoundLink) -> bool:
            # Switch tables are read from the switch end (a), which must answer SNMP;
            # a host learned from the MAC table must itself have answered ping.
            sw = found.get(link.a.ip or "")
            if sw is None or not sw.snmp:
                return False
            return link.source != "fdb" or (link.b.ip in found)

        links = [lk for lk in inv.links if visible(lk)]
        links += [lk for lk in extra_links if visible(lk) and (lk.a.ip in new_ips or lk.b.ip in new_ips)]
        # Hosts learned from MAC tables show under the name they answered with.
        result.links = [
            FoundLink(lk.a, End(found[lk.b.ip].name or lk.b.name, lk.b.port, lk.b.ip), lk.source)
            if lk.source == "fdb"
            else lk
            for lk in links
        ]
        return result
