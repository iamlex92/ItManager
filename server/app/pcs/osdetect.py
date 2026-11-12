"""Which computers run Windows and which run Astra Linux, remembered on the device (metadata «os»).

Signals, strongest first: the ports a computer answers on (WinRM, RDP or RPC mean Windows; SSH means Linux), then the
TTL of a ping reply (Windows answers with 128, Linux with 64: a guess, marked as such). A value the operator set by
hand is never overwritten. Nothing is sent to a computer except a connection attempt and one ping."""

import asyncio
import re
import sys
from collections.abc import Awaitable, Callable
from typing import Any

from ..config import Settings
from ..models import Device

OS_KEY = "os"
SOURCE_KEY = "os_source"
LABELS = {"windows": "Windows", "astra": "Astra Linux"}
# ports: WinRM/RDP/RPC or SSH; ttl: a guess from the ping reply; name: emulator mode; manual: set by the operator
SOURCES = ("ports", "ttl", "name", "manual")
RDP_PORT = 3389
RPC_PORT = 135
CONCURRENCY = 32

Probe = Callable[[str, int], Awaitable[bool]]
TtlProbe = Callable[[str], Awaitable[int | None]]


def os_of(device: Device) -> str | None:
    value = (device.metadata_ or {}).get(OS_KEY)
    return value if value in LABELS else None


def is_manual(device: Device) -> bool:
    return (device.metadata_ or {}).get(SOURCE_KEY) == "manual"


def remember(device: Device, os_name: str, source: str) -> bool:
    """Writes the detected system unless the operator set one by hand; True when something changed."""
    if is_manual(device) or os_name not in LABELS:
        return False
    meta = device.metadata_ or {}
    if meta.get(OS_KEY) == os_name and meta.get(SOURCE_KEY) == source:
        return False
    device.metadata_ = {**meta, OS_KEY: os_name, SOURCE_KEY: source}
    return True


async def ping_ttl(ip: str, timeout_s: int = 1) -> int | None:
    """The TTL of one ping reply, or None when nothing answered."""
    if sys.platform == "win32":
        args = ["ping", "-n", "1", "-w", str(timeout_s * 1000), ip]
    else:
        args = ["ping", "-n", "-c", "1", "-W", str(timeout_s), ip]
    try:
        proc = await asyncio.create_subprocess_exec(
            *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
        )
        out, _ = await proc.communicate()
    except OSError:
        return None
    found = re.search(rb"ttl[=:]\s*(\d+)", out, re.IGNORECASE)
    return int(found.group(1)) if found and proc.returncode == 0 else None


def from_ttl(ttl: int) -> str:
    """Routers lower the TTL on the way: up to 64 is Linux, up to 128 is Windows."""
    return "astra" if ttl <= 64 else "windows"


async def detect(host: str, settings: Settings, probe: Probe, ttl: TtlProbe) -> tuple[str, str] | None:
    """(system, source) for one computer, or None when it answers nothing."""
    winrm = settings.winrm_port or (5986 if settings.winrm_https else 5985)
    windows_ports = [winrm, RDP_PORT, RPC_PORT]
    results = await asyncio.gather(*(probe(host, p) for p in [*windows_ports, settings.linux_ssh_port]))
    if any(results[:-1]):
        return "windows", "ports"
    if results[-1]:
        return "astra", "ports"
    value = await ttl(host)
    return (from_ttl(value), "ttl") if value is not None else None


def from_name(name: str) -> str:
    """Emulator mode has no network: the name decides, as in the PC emulator."""
    return "astra" if re.search(r"astra|lnx|linux", name, re.IGNORECASE) else "windows"


async def detect_all(
    devices: list[Device], settings: Settings, emulated: bool, probe: Probe, ttl: TtlProbe
) -> dict[str, Any]:
    """Detects and remembers the system of every computer; returns what was found."""
    sem = asyncio.Semaphore(CONCURRENCY)
    summary: dict[str, Any] = {"checked": 0, "windows": 0, "astra": 0, "unknown": 0, "manual": 0, "changed": 0}

    async def one(d: Device) -> None:
        if is_manual(d):
            summary["manual"] += 1
            return
        if emulated:
            found: tuple[str, str] | None = (from_name(d.name), "name")
        else:
            host = str(d.ip_address) if d.ip_address is not None else (d.extra_ips or [None])[0]
            if host is None:
                summary["unknown"] += 1
                return
            async with sem:
                found = await detect(str(host), settings, probe, ttl)
        summary["checked"] += 1
        if found is None:
            summary["unknown"] += 1
            return
        summary[found[0]] += 1
        if remember(d, *found):
            summary["changed"] += 1

    await asyncio.gather(*(one(d) for d in devices))
    return summary
