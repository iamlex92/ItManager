"""Which computers run Windows and which run Astra Linux, remembered on the device (metadata «os»).

Signals: WinRM or RPC (135) open means Windows for certain. RDP and SSH say little on their own (xrdp runs on Linux,
OpenSSH on Windows), so the TTL of a ping reply decides next (Windows answers with 128, Linux with 64: a guess,
marked as such). Only when nothing answers ping are the ports used, and RDP together with SSH is left undecided
rather than guessed. What the verdict rests on is kept in the note. A value the operator set by hand is never
overwritten. Nothing is sent to a computer except connection attempts and one ping."""

import asyncio
import re
import sys
from collections.abc import Awaitable, Callable
from typing import Any

from ..config import Settings
from ..models import Device

OS_KEY = "os"
SOURCE_KEY = "os_source"
NOTE_KEY = "os_note"
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


def remember(device: Device, os_name: str, source: str, note: str = "") -> bool:
    """Writes the detected system unless the operator set one by hand; True when something changed."""
    if is_manual(device) or os_name not in LABELS:
        return False
    meta = device.metadata_ or {}
    if meta.get(OS_KEY) == os_name and meta.get(SOURCE_KEY) == source and meta.get(NOTE_KEY, "") == note:
        return False
    device.metadata_ = {**meta, OS_KEY: os_name, SOURCE_KEY: source, NOTE_KEY: note}
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


async def detect(host: str, settings: Settings, probe: Probe, ttl: TtlProbe) -> tuple[str, str, str] | None:
    """(system, source, note) for one computer, or None when it cannot be told."""
    winrm = settings.winrm_port or (5986 if settings.winrm_https else 5985)
    ports = {winrm: "WinRM", RPC_PORT: "RPC", RDP_PORT: "RDP", settings.linux_ssh_port: "SSH"}
    answers = await asyncio.gather(*(probe(host, p) for p in ports))
    opened = {p for p, ok in zip(ports, answers, strict=True) if ok}
    value = await ttl(host)
    note = "; ".join(
        [f"открыты порты: {', '.join(f'{ports[p]} {p}' for p in ports if p in opened)}" if opened else "порты закрыты"]
        + ([f"TTL {value}"] if value is not None else ["ping не отвечает"])
    )
    if winrm in opened or RPC_PORT in opened:
        return "windows", "ports", note
    if value is not None:
        return from_ttl(value), "ttl", note
    rdp, ssh = RDP_PORT in opened, settings.linux_ssh_port in opened
    if rdp and not ssh:
        return "windows", "ports", note
    if ssh and not rdp:
        return "astra", "ports", note
    return None


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
            found: tuple[str, str, str] | None = (from_name(d.name), "name", "по имени (эмулятор)")
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
