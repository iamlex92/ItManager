"""IP address management over the inventory: how full each VLAN subnet is, which addresses clash, and the
next free one. Pure functions over plain tuples, so they are easy to test; nothing here writes anything."""

import ipaddress
from dataclasses import dataclass, field

# Hosts listed per subnet are capped: a /16 has 65k addresses and the app only needs the first free ones.
MAX_FREE_LISTED = 20


@dataclass
class Host:
    device_id: str
    name: str
    ip: str


@dataclass
class SubnetUsage:
    vlan_id: int
    name: str
    cidr: str
    total: int
    used: int
    free: int
    next_free: list[str] = field(default_factory=list)
    hosts: list[Host] = field(default_factory=list)


@dataclass
class Conflict:
    kind: str  # duplicate | outside_subnets | network_address | broadcast_address
    ip: str
    devices: list[str]
    detail: str = ""


def usable_hosts(net: ipaddress.IPv4Network | ipaddress.IPv6Network) -> int:
    if net.version == 4 and net.prefixlen >= 31:
        return net.num_addresses
    return max(net.num_addresses - 2, 0)


def parse_ip(text: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    try:
        return ipaddress.ip_address(str(text).split("/")[0])
    except ValueError:
        return None


def analyze(vlans: list[tuple[int, str, str | None]], hosts: list[Host]) -> tuple[list[SubnetUsage], list[Conflict]]:
    """vlans: (vlan_id, name, cidr or None); hosts: devices that have an IP address."""
    parsed = [(h, parse_ip(h.ip)) for h in hosts]
    parsed = [(h, ip) for h, ip in parsed if ip is not None]

    nets: list[tuple[int, str, ipaddress.IPv4Network | ipaddress.IPv6Network]] = []
    for vlan_id, name, cidr in vlans:
        if not cidr:
            continue
        try:
            nets.append((vlan_id, name, ipaddress.ip_network(str(cidr), strict=False)))
        except ValueError:
            continue

    usage: list[SubnetUsage] = []
    for vlan_id, name, net in nets:
        inside = [(h, ip) for h, ip in parsed if ip.version == net.version and ip in net]
        taken = {ip for _, ip in inside}
        total = usable_hosts(net)
        free_ips: list[str] = []
        if net.num_addresses <= 2**24:
            candidates = net.hosts() if not (net.version == 4 and net.prefixlen >= 31) else iter(net)
            for cand in candidates:
                if cand not in taken:
                    free_ips.append(str(cand))
                    if len(free_ips) >= MAX_FREE_LISTED:
                        break
        used = len({ip for ip in taken if _is_host(ip, net)})
        usage.append(
            SubnetUsage(
                vlan_id, name, str(net), total, used, max(total - used, 0), free_ips,
                [Host(h.device_id, h.name, str(ip)) for h, ip in sorted(inside, key=lambda x: x[1])],
            )
        )  # fmt: skip

    conflicts: list[Conflict] = []
    by_ip: dict[object, list[Host]] = {}
    for h, ip in parsed:
        by_ip.setdefault(ip, []).append(h)
    for ip, group in sorted(by_ip.items(), key=lambda kv: kv[0]):
        if len(group) > 1:
            conflicts.append(
                Conflict("duplicate", str(ip), [h.name for h in group], "один адрес у нескольких устройств")
            )
    for h, ip in parsed:
        containing = [n for _, _, n in nets if ip.version == n.version and ip in n]
        if nets and not containing:
            conflicts.append(Conflict("outside_subnets", str(ip), [h.name], "адрес не входит ни в одну подсеть VLAN"))
        for n in containing:
            if n.num_addresses > 2 and n.version == 4 and n.prefixlen < 31:
                if ip == n.network_address:
                    conflicts.append(Conflict("network_address", str(ip), [h.name], f"адрес сети {n}"))
                elif ip == n.broadcast_address:
                    conflicts.append(Conflict("broadcast_address", str(ip), [h.name], f"широковещательный адрес {n}"))
    return usage, conflicts


def _is_host(
    ip: ipaddress.IPv4Address | ipaddress.IPv6Address, net: ipaddress.IPv4Network | ipaddress.IPv6Network
) -> bool:
    if net.version == 4 and net.prefixlen < 31:
        return ip != net.network_address and ip != net.broadcast_address
    return True


def check_address(ip_text: str, vlans: list[tuple[int, str, str | None]], hosts: list[Host]) -> dict:
    """Would assigning this address clash? Used before typing an IP into a device."""
    ip = parse_ip(ip_text)
    if ip is None:
        return {"valid": False, "free": False, "owners": [], "vlan_id": None, "problems": ["не IP-адрес"]}
    owners = [h.name for h in hosts if (p := parse_ip(h.ip)) is not None and p == ip]
    vlan_id = None
    problems = []
    for vid, _name, cidr in vlans:
        if not cidr:
            continue
        net = ipaddress.ip_network(str(cidr), strict=False)
        if ip.version == net.version and ip in net:
            vlan_id = vid
            if not _is_host(ip, net):
                problems.append("адрес сети или широковещательный")
            break
    if vlan_id is None and any(c for _, _, c in vlans):
        problems.append("адрес не входит ни в одну подсеть VLAN")
    if owners:
        problems.append("адрес уже занят")
    return {"valid": True, "free": not owners, "owners": owners, "vlan_id": vlan_id, "problems": problems}
