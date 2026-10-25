"""Real scan: ICMP echo to every address, then SNMP GET/GETNEXT to the hosts
that answered. Never sends SET or anything else that changes a device."""

import asyncio
import ipaddress
import logging
from collections.abc import Awaitable, Callable
from typing import Any

from ..hal.printers import snmp_auth
from ..services.heartbeat import icmp_ping
from .model import (
    End,
    FoundDevice,
    FoundLink,
    Progress,
    ScanResult,
    guess_type,
    hosts,
    short_name,
    vendor_from_oid,
)

log = logging.getLogger(__name__)

SYS_DESCR = "1.3.6.1.2.1.1.1.0"
SYS_OBJECT_ID = "1.3.6.1.2.1.1.2.0"
SYS_NAME = "1.3.6.1.2.1.1.5.0"
IF_NAME = "1.3.6.1.2.1.31.1.1.1.1"
# LLDP-MIB: local port ids, and the remote table indexed by timeMark.localPortNum.index
LLDP_LOC_PORT_ID = "1.0.8802.1.1.2.1.3.7.1.3"
LLDP_REM = "1.0.8802.1.1.2.1.4.1.1"
LLDP_REM_PORT_ID = 7
LLDP_REM_SYS_NAME = 9
# CISCO-CDP-MIB cache, indexed by ifIndex.deviceIndex
CDP_CACHE = "1.3.6.1.4.1.9.9.23.1.2.1.1"
CDP_ADDRESS = 4
CDP_DEVICE_ID = 6
CDP_DEVICE_PORT = 7
# ipNetToMediaPhysAddress, indexed by ifIndex.a.b.c.d
ARP = "1.3.6.1.2.1.4.22.1.2"
# BRIDGE-MIB / Q-BRIDGE-MIB forwarding tables and bridge port -> ifIndex
BASE_PORT_IF_INDEX = "1.3.6.1.2.1.17.1.4.1.2"
DOT1D_FDB_PORT = "1.3.6.1.2.1.17.4.3.1.2"
DOT1Q_FDB_PORT = "1.3.6.1.2.1.17.7.1.2.2.1.2"

# A port that learned more MACs than this leads to another switch, not to one host.
EDGE_PORT_MAX_MACS = 4
MAX_ROWS = 20000

Pinger = Callable[[str], Awaitable[bool]]


def _text(value: Any) -> str:
    try:
        raw = value.asOctets()
    except AttributeError:
        return value.prettyPrint()
    try:
        s = raw.decode()
    except UnicodeDecodeError:
        return value.prettyPrint()
    return s if s.isprintable() else value.prettyPrint()


def _mac(value: Any) -> str | None:
    try:
        raw = value.asOctets()
    except AttributeError:
        return None
    return ":".join(f"{b:02x}" for b in raw) if len(raw) == 6 else None


def _port_id(value: Any) -> str:
    """LLDP port ids are a name on most switches and a MAC on some."""
    return _mac(value) if _text(value).startswith("0x") and _mac(value) else _text(value)


def _suffix(oid: str, prefix: str) -> str:
    return oid[len(prefix) + 1 :]


class SnmpScanner:
    def __init__(self, settings, pinger: Pinger = icmp_ping, ping_concurrency: int = 64) -> None:
        self._s = settings
        self._ping = pinger
        self._ping_conc = ping_concurrency
        self._engine = None

    async def _target(self, ip: str):
        from pysnmp.hlapi.v3arch import asyncio as h

        return await h.UdpTransportTarget.create((ip, 161), timeout=self._s.snmp_timeout_seconds, retries=0)

    async def _get(self, ip: str, auth, oids: list[str]) -> dict[str, Any] | None:
        from pysnmp.hlapi.v3arch import asyncio as h

        err_ind, err_stat, _idx, var_binds = await h.get_cmd(
            self._engine,
            auth,
            await self._target(ip),
            h.ContextData(),
            *(h.ObjectType(h.ObjectIdentity(o)) for o in oids),
        )
        if err_ind or err_stat:
            return None
        return {str(n): v for n, v in var_binds}

    async def _walk(self, ip: str, auth, prefix: str) -> dict[str, Any]:
        from pysnmp.hlapi.v3arch import asyncio as h

        rows: dict[str, Any] = {}
        async for err_ind, err_stat, _idx, var_binds in h.walk_cmd(
            self._engine,
            auth,
            await self._target(ip),
            h.ContextData(),
            h.ObjectType(h.ObjectIdentity(prefix)),
            lexicographicMode=False,
        ):
            if err_ind or err_stat:
                break
            for name, value in var_binds:
                if value.__class__.__name__ in ("NoSuchObject", "NoSuchInstance", "EndOfMibView"):
                    continue
                rows[str(name)] = value
            if len(rows) >= MAX_ROWS:
                log.warning("%s: %s truncated at %d rows", ip, prefix, MAX_ROWS)
                break
        return rows

    async def scan(self, nets: list[ipaddress.IPv4Network], community: str, progress: Progress) -> ScanResult:
        from pysnmp.hlapi.v3arch import asyncio as h

        if self._engine is None:
            self._engine = h.SnmpEngine()
        auth = snmp_auth(self._s, community)
        targets = hosts(nets)

        sem = asyncio.Semaphore(self._ping_conc)
        done = 0

        async def ping(ip: str) -> bool:
            nonlocal done
            async with sem:
                try:
                    ok = await self._ping(ip)
                except Exception:
                    log.exception("ping %s failed", ip)
                    ok = False
            done += 1
            progress(done)
            return ok

        alive = [ip for ip, ok in zip(targets, await asyncio.gather(*(ping(ip) for ip in targets)), strict=True) if ok]

        snmp_sem = asyncio.Semaphore(16)

        async def identify(ip: str) -> FoundDevice:
            async with snmp_sem:
                try:
                    got = await self._get(ip, auth, [SYS_DESCR, SYS_OBJECT_ID, SYS_NAME])
                except Exception:
                    log.debug("snmp %s failed", ip, exc_info=True)
                    got = None
            if not got:
                return FoundDevice(ip=ip)
            descr = _text(got.get(SYS_DESCR, "")) if SYS_DESCR in got else None
            name = _text(got[SYS_NAME]) if SYS_NAME in got else None
            oid = str(got[SYS_OBJECT_ID]) if SYS_OBJECT_ID in got else None
            first_line = (descr or "").splitlines()[0][:100] if descr else None
            return FoundDevice(
                ip=ip,
                name=short_name(name) if name else None,
                vendor=vendor_from_oid(oid),
                model=first_line,
                device_type=guess_type(descr),
                description=descr,
                snmp=True,
            )

        devices = list(await asyncio.gather(*(identify(ip) for ip in alive)))
        result = ScanResult(devices=devices)
        snmp_devices = [d for d in devices if d.snmp]

        tables: dict[str, dict[str, dict[str, Any]]] = {}

        async def collect(dev: FoundDevice) -> None:
            async with snmp_sem:
                t: dict[str, dict[str, Any]] = {}
                for prefix in (
                    IF_NAME,
                    LLDP_LOC_PORT_ID,
                    LLDP_REM,
                    CDP_CACHE,
                    ARP,
                    BASE_PORT_IF_INDEX,
                    DOT1Q_FDB_PORT,
                    DOT1D_FDB_PORT,
                ):
                    try:
                        t[prefix] = await self._walk(dev.ip, auth, prefix)
                    except Exception:
                        log.debug("walk %s %s failed", dev.ip, prefix, exc_info=True)
                        t[prefix] = {}
                tables[dev.ip] = t

        await asyncio.gather(*(collect(d) for d in snmp_devices))
        result.links = build_links(devices, tables)
        return result


def build_links(devices: list[FoundDevice], tables: dict[str, dict[str, dict[str, Any]]]) -> list[FoundLink]:
    """Neighbors from LLDP/CDP, then hosts attached to switch edge ports by MAC."""
    by_ip = {d.ip: d for d in devices}
    by_name = {d.name.lower(): d for d in devices if d.name}
    arp: dict[str, str] = {}
    for t in tables.values():
        for oid, value in t.get(ARP, {}).items():
            ip = ".".join(_suffix(oid, ARP).split(".")[1:])
            mac = _mac(value)
            if mac and ip in by_ip:
                arp[ip] = mac
    for d in devices:
        if not d.mac and d.ip in arp:
            d.mac = arp[d.ip]

    links: list[FoundLink] = []
    uplinks: set[tuple[str, str]] = set()

    def add(link: FoundLink) -> None:
        pair = {link.a.name.lower(), link.b.name.lower()}
        for other in links:
            if other.key() == link.key():
                return
            if {other.a.name.lower(), other.b.name.lower()} == pair and other.key() & link.key():
                return
        links.append(link)

    def end_for(name: str, port: str, ip: str | None = None) -> End:
        dev = by_ip.get(ip or "") or by_name.get(name.lower())
        return End(dev.name if dev and dev.name else name, port, dev.ip if dev else ip)

    # Address order, so the same network always gives the same link orientation.
    ordered = sorted(tables.items(), key=lambda kv: ipaddress.ip_address(kv[0]))
    for ip, t in ordered:
        dev = by_ip[ip]
        local = dev.name or ip
        if_names = {_suffix(o, IF_NAME): _text(v) for o, v in t.get(IF_NAME, {}).items()}
        loc_ports = {_suffix(o, LLDP_LOC_PORT_ID): _text(v) for o, v in t.get(LLDP_LOC_PORT_ID, {}).items()}

        rem: dict[str, dict[int, Any]] = {}
        for oid, value in t.get(LLDP_REM, {}).items():
            col, _, index = _suffix(oid, LLDP_REM).partition(".")
            rem.setdefault(index, {})[int(col)] = value
        for index, cols in rem.items():
            if LLDP_REM_SYS_NAME not in cols:
                continue
            local_num = index.split(".")[1]
            local_port = loc_ports.get(local_num) or if_names.get(local_num) or local_num
            neighbor = short_name(_text(cols[LLDP_REM_SYS_NAME]))
            remote_port = _port_id(cols[LLDP_REM_PORT_ID]) if LLDP_REM_PORT_ID in cols else ""
            uplinks.add((ip, local_port))
            add(FoundLink(End(local, local_port, ip), end_for(neighbor, remote_port), "lldp"))

        cdp: dict[str, dict[int, Any]] = {}
        for oid, value in t.get(CDP_CACHE, {}).items():
            col, _, index = _suffix(oid, CDP_CACHE).partition(".")
            cdp.setdefault(index, {})[int(col)] = value
        for index, cols in cdp.items():
            if CDP_DEVICE_ID not in cols:
                continue
            local_port = if_names.get(index.split(".")[0], index.split(".")[0])
            neighbor = short_name(_text(cols[CDP_DEVICE_ID]))
            remote_port = _text(cols[CDP_DEVICE_PORT]) if CDP_DEVICE_PORT in cols else ""
            addr = None
            if CDP_ADDRESS in cols:
                raw = cols[CDP_ADDRESS].asOctets()
                addr = ".".join(str(b) for b in raw) if len(raw) == 4 else None
            uplinks.add((ip, local_port))
            add(FoundLink(End(local, local_port, ip), end_for(neighbor, remote_port, addr), "cdp"))

    # MAC tables: which edge port each host's MAC was learned on.
    seen_on: dict[str, list[tuple[str, str]]] = {}
    macs_per_port: dict[tuple[str, str], int] = {}
    for ip, t in ordered:
        if_names = {_suffix(o, IF_NAME): _text(v) for o, v in t.get(IF_NAME, {}).items()}
        bridge = {_suffix(o, BASE_PORT_IF_INDEX): str(v) for o, v in t.get(BASE_PORT_IF_INDEX, {}).items()}
        entries: list[tuple[str, str]] = []
        for oid, value in t.get(DOT1Q_FDB_PORT, {}).items():
            parts = _suffix(oid, DOT1Q_FDB_PORT).split(".")[1:]
            entries.append((":".join(f"{int(x):02x}" for x in parts), str(value)))
        if not entries:
            for oid, value in t.get(DOT1D_FDB_PORT, {}).items():
                parts = _suffix(oid, DOT1D_FDB_PORT).split(".")
                entries.append((":".join(f"{int(x):02x}" for x in parts), str(value)))
        for mac, bport in entries:
            if_index = bridge.get(bport)
            if if_index is None or bport == "0":
                continue
            port = if_names.get(if_index, if_index)
            if (ip, port) in uplinks:
                continue
            macs_per_port[(ip, port)] = macs_per_port.get((ip, port), 0) + 1
            seen_on.setdefault(mac, []).append((ip, port))
        if bridge:
            by_ip[ip].device_type = "SWITCH"

    for dev in devices:
        if not dev.mac or dev.device_type == "SWITCH" or dev.mac not in seen_on:
            continue
        candidates = [c for c in seen_on[dev.mac] if macs_per_port[c] <= EDGE_PORT_MAX_MACS]
        if not candidates:
            continue
        sw_ip, port = min(candidates, key=lambda c: macs_per_port[c])
        sw = by_ip[sw_ip]
        add(FoundLink(End(sw.name or sw_ip, port, sw_ip), End(dev.name or "", "", dev.ip), "fdb"))
    return links
