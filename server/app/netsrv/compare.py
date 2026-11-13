"""The inventory against what the DHCP and DNS servers say. Pure: lists in, findings out."""

import ipaddress
from dataclasses import dataclass, field
from typing import Any

from .dhcpd import Lease, Reservation, Scope
from .dnsdata import ARecord

# Not on the network as a host: nothing to find in DNS or DHCP.
PASSIVE = {"SOCKET", "PATCH_PANEL", "RACK"}


@dataclass(frozen=True)
class Dev:
    id: str
    name: str
    type: str
    ips: tuple[str, ...]


def short(name: str | None) -> str:
    return (name or "").strip().rstrip(".").split(".")[0].lower()


def _in(ip: str, a: str, b: str) -> bool:
    try:
        n = int(ipaddress.ip_address(ip))
        return int(ipaddress.ip_address(a)) <= n <= int(ipaddress.ip_address(b))
    except ValueError:
        return False


def level_of(percent: int) -> str:
    return "critical" if percent >= 90 else "warn" if percent >= 75 else "ok"


@dataclass
class DhcpReport:
    scopes: list[dict[str, Any]] = field(default_factory=list)
    findings: list[dict[str, str]] = field(default_factory=list)
    unknown_leases: list[dict[str, Any]] = field(default_factory=list)
    new_ranges: list[dict[str, str]] = field(default_factory=list)
    new_reservations: list[dict[str, str]] = field(default_factory=list)
    leases: int = 0
    reservations: int = 0


def dhcp_report(
    scopes: list[Scope],
    hosts: list[Reservation],
    leases: list[Lease],
    devices: list[Dev],
    known_ranges: set[tuple[str, str]],
    known_reservations: set[str],
) -> DhcpReport:
    rep = DhcpReport(leases=len(leases), reservations=len(hosts))
    by_ip: dict[str, Dev] = {ip: d for d in devices if d.type not in PASSIVE for ip in d.ips}
    for sc in scopes:
        inside = [lease for lease in leases if any(_in(lease.ip, a, b) for a, b in sc.ranges)]
        total = sc.total
        pct = round(len(inside) / total * 100) if total else 0
        rep.scopes.append({
            "network": sc.network, "ranges": [{"start": a, "end": b} for a, b in sc.ranges], "total": total,
            "leased": len(inside), "free": max(0, total - len(inside)), "percent": pct,
            "level": level_of(pct) if total else "ok", "routers": sc.routers, "dns": sc.dns, "domain": sc.domain,
        })  # fmt: skip
        if total and pct >= 90:
            rep.findings.append({
                "kind": "scope_full", "severity": "critical", "ip": sc.network,
                "text": f"Область {sc.network} заполнена на {pct} %: свободно {max(0, total - len(inside))} адресов",
            })  # fmt: skip
        for a, b in sc.ranges:
            if (a, b) not in known_ranges:
                rep.new_ranges.append({"network": sc.network, "start": a, "end": b})
    for d in devices:
        if d.type in PASSIVE:
            continue
        for ip in d.ips:
            if any(_in(ip, a, b) for sc in scopes for a, b in sc.ranges) and not any(h.ip == ip for h in hosts):
                rep.findings.append({
                    "kind": "static_in_pool", "severity": "warn", "ip": ip,
                    "text": f"{d.name}: адрес {ip} из пула DHCP и не закреплён за ним: сервер может выдать его другому",
                })  # fmt: skip
    for h in hosts:
        owner = by_ip.get(h.ip)
        if owner and short(owner.name) != short(h.name):
            rep.findings.append({
                "kind": "reservation_conflict", "severity": "critical", "ip": h.ip,
                "text": f"Резерв DHCP {h.name} на {h.ip}, а в учёте этот адрес у {owner.name}",
            })  # fmt: skip
        elif owner is None:
            same = next((d for d in devices if short(d.name) == short(h.name) and d.type not in PASSIVE), None)
            if same and same.ips:
                rep.findings.append({
                    "kind": "reservation_moved", "severity": "warn", "ip": h.ip,
                    "text": f"Резерв DHCP {h.name} на {h.ip}, а в учёте у него {', '.join(same.ips)}",
                })  # fmt: skip
        if h.ip not in known_reservations:
            rep.new_reservations.append({"name": h.name, "ip": h.ip, "mac": h.mac or ""})
    for lease in leases:
        owner = by_ip.get(lease.ip)
        if owner is None:
            rep.unknown_leases.append({"ip": lease.ip, "mac": lease.mac, "hostname": lease.hostname})
        elif lease.hostname and short(owner.name) != short(lease.hostname) and not any(h.ip == lease.ip for h in hosts):
            rep.findings.append({
                "kind": "lease_conflict", "severity": "warn", "ip": lease.ip,
                "text": f"Аренда {lease.ip} у «{lease.hostname}», а в учёте адрес у {owner.name}",
            })  # fmt: skip
    order = {"critical": 0, "warn": 1}
    rep.findings.sort(key=lambda f: (order.get(f["severity"], 2), f["ip"]))
    return rep


def dns_report(records: list[ARecord], devices: list[Dev]) -> dict[str, Any]:
    by_name: dict[str, list[str]] = {}
    for r in records:
        by_name.setdefault(short(r.name), []).append(r.ip)
    inv_ips = {ip for d in devices if d.type not in PASSIVE for ip in d.ips}
    inv_names = {short(d.name) for d in devices}
    missing, wrong = [], []
    for d in devices:
        if d.type in PASSIVE or not d.ips:
            continue
        found = by_name.get(short(d.name))
        if not found:
            missing.append({"name": d.name, "ip": d.ips[0]})
        elif not set(found) & set(d.ips):
            wrong.append({"name": d.name, "inventory": d.ips[0], "dns": sorted(set(found))})
    stale = [
        {"name": r.name, "ip": r.ip}
        for r in records
        if r.name not in ("@", "")
        and not r.name.startswith("_")
        and short(r.name) not in inv_names
        and r.ip not in inv_ips
    ]
    return {
        "records": len(records),
        "missing": sorted(missing, key=lambda x: x["name"].lower()),
        "wrong": sorted(wrong, key=lambda x: x["name"].lower()),
        "stale": sorted(stale, key=lambda x: x["name"].lower())[:300],
        "stale_count": len(stale),
    }
