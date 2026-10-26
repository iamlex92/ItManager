import ipaddress
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime

MAX_SUBNETS = 8
# Largest single range: a /22 (1022 hosts).
MIN_PREFIX = 22
MAX_HOSTS = 2048


class InvalidScan(ValueError):
    pass


@dataclass
class FoundDevice:
    ip: str
    name: str | None = None
    mac: str | None = None
    vendor: str | None = None
    model: str | None = None
    device_type: str = "UNKNOWN"
    description: str | None = None
    # Answered SNMP with the given community.
    snmp: bool = False
    # How the host was found: ping, tcp (ICMP blocked, a port answered) or arp (only the ARP cache knows it).
    seen_by: str = "ping"
    open_ports: list[int] = field(default_factory=list)
    # Reverse DNS name, as it is.
    hostname: str | None = None


@dataclass(frozen=True)
class End:
    name: str
    port: str
    ip: str | None = None


@dataclass(frozen=True)
class FoundLink:
    a: End
    b: End
    # lldp, cdp or fdb (a host's MAC learned on a switch access port)
    source: str

    def key(self) -> frozenset:
        return frozenset({(self.a.name.lower(), self.a.port.lower()), (self.b.name.lower(), self.b.port.lower())})


@dataclass
class ScanResult:
    devices: list[FoundDevice] = field(default_factory=list)
    links: list[FoundLink] = field(default_factory=list)
    # What limited this scan, in plain words: no ping, the server outside the subnet and so on.
    warnings: list[str] = field(default_factory=list)


@dataclass
class Scan:
    subnets: list[str]
    mode: str
    triggered_by: str
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    status: str = "running"
    started_at: datetime | None = None
    finished_at: datetime | None = None
    total: int = 0
    probed: int = 0
    result: ScanResult = field(default_factory=ScanResult)
    error: str | None = None


Progress = Callable[[int], None]


def parse_subnets(raw: list[str]) -> list[ipaddress.IPv4Network]:
    """Private IPv4 ranges only, each no bigger than a /22, in total at most MAX_HOSTS hosts."""
    nets: list[ipaddress.IPv4Network] = []
    for item in raw:
        text = item.strip()
        if not text:
            continue
        try:
            net = ipaddress.ip_network(text, strict=False)
        except ValueError as e:
            raise InvalidScan(f"«{text}» не похоже на подсеть, пример: 10.10.20.0/24") from e
        if not isinstance(net, ipaddress.IPv4Network):
            raise InvalidScan(f"{text}: поддерживается только IPv4")
        if not net.is_private or net.is_loopback or net.is_link_local or net.is_multicast:
            raise InvalidScan(f"{net}: сканировать можно только внутренние адреса (10.x, 172.16-31.x, 192.168.x)")
        if net.prefixlen < MIN_PREFIX:
            raise InvalidScan(f"{net}: слишком большой диапазон, максимум /{MIN_PREFIX}")
        if net not in nets:
            nets.append(net)
    if not nets:
        raise InvalidScan("укажите хотя бы одну подсеть")
    if len(nets) > MAX_SUBNETS:
        raise InvalidScan(f"не больше {MAX_SUBNETS} подсетей за раз")
    if sum(host_count(n) for n in nets) > MAX_HOSTS:
        raise InvalidScan(f"слишком много адресов, максимум {MAX_HOSTS} за одно сканирование")
    return nets


def host_count(net: ipaddress.IPv4Network) -> int:
    return net.num_addresses - 2 if net.prefixlen < 31 else net.num_addresses


def hosts(nets: list[ipaddress.IPv4Network]) -> list[str]:
    seen: set[str] = set()
    out = []
    for n in nets:
        for h in n.hosts():
            s = str(h)
            if s not in seen:
                seen.add(s)
                out.append(s)
    return out


# sysObjectID enterprise number -> vendor as stored in the inventory
ENTERPRISES = {9: "CISCO", 35265: "ELTEX", 1347: "KYOCERA", 11: "HP", 2435: "BROTHER", 25506: "H3C", 2011: "HUAWEI"}


def vendor_from_oid(oid: str | None) -> str | None:
    if not oid or not oid.startswith("1.3.6.1.4.1."):
        return None
    try:
        return ENTERPRISES.get(int(oid.split(".")[6]))
    except (IndexError, ValueError):
        return None


def guess_type(descr: str | None, has_bridge: bool = False) -> str:
    low = (descr or "").lower()
    if has_bridge or any(w in low for w in ("switch", "ios", "mes2", "mes3", "catalyst", "routeros")):
        return "SWITCH"
    if any(w in low for w in ("printer", "laserjet", "ecosys", "taskalfa", "mfp", "jetdirect")):
        return "PRINTER"
    if any(w in low for w in ("camera", "hikvision", "axis", "dahua")):
        return "CAMERA"
    if any(w in low for w in ("access point", "unifi", "aironet")):
        return "ACCESS_POINT"
    if "windows" in low:
        return "WORKSTATION"
    if any(w in low for w in ("linux", "vmware", "esxi", "freebsd")):
        return "SERVER"
    return "UNKNOWN"


def short_name(name: str) -> str:
    """LLDP/CDP names often carry a domain or a serial in brackets: SW-01.corp.local, SW-01(FOC123)."""
    name = name.split("(")[0].strip()
    try:
        ipaddress.ip_address(name)
        return name
    except ValueError:
        return name.split(".")[0]
