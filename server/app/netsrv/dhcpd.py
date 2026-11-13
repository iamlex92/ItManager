"""ISC dhcpd: the configuration (subnets, address ranges, fixed hosts) and the lease file (who has what now).

Read as plain text: no include files are followed, and only what the plant's inventory is compared with is taken:
`subnet`, `range`, `host { hardware ethernet; fixed-address; }`. Comments and `group`/`shared-network` wrappers are
skipped over, so hosts and subnets inside them are still found.
"""

import ipaddress
import re
from dataclasses import dataclass, field
from datetime import UTC, datetime

IP = r"\d{1,3}(?:\.\d{1,3}){3}"


@dataclass
class Scope:
    network: str  # 10.10.10.0/24
    ranges: list[tuple[str, str]] = field(default_factory=list)
    routers: str | None = None
    dns: str | None = None
    domain: str | None = None

    @property
    def total(self) -> int:
        return sum(int(ipaddress.ip_address(b)) - int(ipaddress.ip_address(a)) + 1 for a, b in self.ranges)


@dataclass
class Reservation:
    name: str
    mac: str | None
    ip: str


@dataclass
class Lease:
    ip: str
    mac: str | None
    hostname: str | None
    ends: datetime | None  # None: never
    active: bool


def _strip_comments(text: str) -> str:
    return re.sub(r"(?m)#.*$", "", text)


def _mac(text: str | None) -> str | None:
    if not text:
        return None
    m = text.strip().lower().replace("-", ":")
    return m if re.fullmatch(r"([0-9a-f]{2}:){5}[0-9a-f]{2}", m) else None


def parse_conf(text: str) -> tuple[list[Scope], list[Reservation]]:
    text = _strip_comments(text)
    scopes: list[Scope] = []
    for m in re.finditer(rf"subnet\s+({IP})\s+netmask\s+({IP})\s*\{{(.*?)\}}", text, re.DOTALL):
        try:
            net = ipaddress.ip_network(f"{m.group(1)}/{m.group(2)}", strict=False)
        except ValueError:
            continue
        body = m.group(3)
        scope = Scope(str(net))
        for r in re.finditer(rf"\brange\s+(?:dynamic-bootp\s+)?({IP})(?:\s+({IP}))?\s*;", body):
            scope.ranges.append((r.group(1), r.group(2) or r.group(1)))
        if g := re.search(rf"option\s+routers\s+({IP})", body):
            scope.routers = g.group(1)
        if g := re.search(r"option\s+domain-name-servers\s+([^;]+);", body):
            scope.dns = ", ".join(x.strip() for x in g.group(1).split(","))
        if g := re.search(r'option\s+domain-name\s+"([^"]+)"', body):
            scope.domain = g.group(1)
        scopes.append(scope)
    hosts: list[Reservation] = []
    for m in re.finditer(r"\bhost\s+([^\s{]+)\s*\{(.*?)\}", text, re.DOTALL):
        body = m.group(2)
        ip = re.search(rf"fixed-address\s+({IP})\s*;", body)
        if not ip:
            continue  # a fixed address given by name is not read
        mac = re.search(r"hardware\s+ethernet\s+([0-9A-Fa-f:\-]+)\s*;", body)
        hosts.append(Reservation(m.group(1), _mac(mac.group(1) if mac else None), ip.group(1)))
    return scopes, hosts


def _when(value: str) -> datetime | None:
    # «ends 4 2026/11/12 14:03:07;»: weekday, date, time (UTC).
    m = re.match(r"\d\s+(\d{4})/(\d{2})/(\d{2})\s+(\d{2}):(\d{2}):(\d{2})", value.strip())
    if not m:
        return None
    y, mo, d, h, mi, s = (int(x) for x in m.groups())
    return datetime(y, mo, d, h, mi, s, tzinfo=UTC)


def parse_leases(text: str, now: datetime | None = None) -> list[Lease]:
    """The last block of each address wins (the file is appended to). Only leases that are active now are returned."""
    now = now or datetime.now(UTC)
    latest: dict[str, Lease] = {}
    for m in re.finditer(rf"lease\s+({IP})\s*\{{(.*?)\n\}}", text, re.DOTALL):
        body = m.group(2)
        state = re.search(r"binding state\s+(\w+)", body)
        ends = re.search(r"\n?\s*ends\s+(never|[^;]+);", body)
        mac = re.search(r"hardware ethernet\s+([0-9A-Fa-f:]+)\s*;", body)
        name = re.search(r'client-hostname\s+"([^"]*)"', body)
        end = None if not ends or ends.group(1).strip() == "never" else _when(ends.group(1))
        active = bool(state and state.group(1) == "active") and (end is None or end > now)
        host = (name.group(1) if name else None) or None
        latest[m.group(1)] = Lease(m.group(1), _mac(mac.group(1) if mac else None), host, end, active)
    return [lease for lease in latest.values() if lease.active]
