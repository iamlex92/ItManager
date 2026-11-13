"""Do the names in the inventory match what DNS says? A forward lookup of each device's name and a reverse lookup of its
address, compared with the inventory. Read-only; the lookups are passed in, so the check itself is testable."""

import asyncio
import ipaddress
import socket
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

Resolve = Callable[[str], Awaitable[list[str]]]
Reverse = Callable[[str], Awaitable[str | None]]

# Types whose name is expected in DNS: they are on the network and have an address.
SKIP_TYPES = {"SOCKET", "PATCH_PANEL", "RACK"}


@dataclass
class DnsRow:
    device_id: str
    name: str
    ip: str
    forward: str  # ok | mismatch | missing
    forward_ips: list[str] = field(default_factory=list)
    reverse: str = "ok"  # ok | mismatch | missing
    reverse_name: str | None = None


async def system_resolve(name: str, timeout: float = 3.0) -> list[str]:
    """The IPv4 addresses the server's resolver gives for [name] (empty when it does not know it)."""
    try:
        infos = await asyncio.wait_for(
            asyncio.get_running_loop().getaddrinfo(name, None, family=socket.AF_INET), timeout
        )
    except (OSError, TimeoutError):
        return []
    return sorted({info[4][0] for info in infos})


async def system_reverse(ip: str, timeout: float = 3.0) -> str | None:
    try:
        host, _ = await asyncio.wait_for(asyncio.get_running_loop().getnameinfo((ip, 0), socket.NI_NAMEREQD), timeout)
    except (OSError, TimeoutError):
        return None
    return host


def _short(name: str) -> str:
    return name.strip().rstrip(".").split(".")[0].lower()


async def check(
    devices: list[tuple[str, str, str, str, list[str]]],
    resolve: Resolve,
    reverse: Reverse,
    suffix: str = "",
    concurrency: int = 16,
) -> list[DnsRow]:
    """devices: (id, name, type, ip, extra ips). A name is looked up as written, and with [suffix] added when it has no
    dot (a computer is «PC-BUH-01» in the inventory and «pc-buh-01.factory.local» in the domain)."""
    sem = asyncio.Semaphore(concurrency)
    suffix = suffix.strip().strip(".")

    async def one(dev_id: str, name: str, ip: str, extra: list[str]) -> DnsRow:
        async with sem:
            query = f"{name}.{suffix}" if suffix and "." not in name else name
            ips = await resolve(query)
            wanted = {ip, *extra}
            fwd = "missing" if not ips else "ok" if wanted & set(ips) else "mismatch"
            rev_name = await reverse(ip)
            rev = "missing" if rev_name is None else "ok" if _short(rev_name) == _short(name) else "mismatch"
            return DnsRow(dev_id, name, ip, fwd, ips, rev, rev_name)

    jobs = []
    for dev_id, name, dtype, ip, extra in devices:
        if dtype in SKIP_TYPES or not ip:
            continue
        try:
            ipaddress.ip_address(ip)
        except ValueError:
            continue
        jobs.append(one(dev_id, name, ip, extra))
    rows = await asyncio.gather(*jobs)
    # The ones with a problem first.
    return sorted(rows, key=lambda r: (r.forward == "ok" and r.reverse == "ok", r.name.lower()))
