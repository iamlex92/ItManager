"""ICMP heartbeat for real mode: one echo request per device per round.
Read-only. In emulator mode reachability comes from switch ports instead
(see switches.refresh_end_devices)."""

import asyncio
import logging
import shutil
import sys
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..models import Device, DeviceAlert
from . import alerts
from .realtime import Hub
from .topology import PASSIVE_TYPES

log = logging.getLogger(__name__)

Pinger = Callable[[str], Awaitable[bool]]


async def icmp_ping(ip: str, timeout_s: int = 1) -> bool:
    """One echo request via the system ping binary (no raw sockets needed)."""
    # ip comes from an INET column and is passed as an argv item, never through a shell.
    if sys.platform == "win32":
        # Windows ping: -n is the count, -w the timeout in ms. It exits 0 on "destination host unreachable"
        # from a router too, so only a reply line (it has "TTL=" in every language) counts.
        proc = await asyncio.create_subprocess_exec(
            "ping", "-n", "1", "-w", str(timeout_s * 1000), ip,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )  # fmt: skip
        out, _ = await proc.communicate()
        return proc.returncode == 0 and b"TTL=" in out.upper()
    proc = await asyncio.create_subprocess_exec(
        "ping", "-n", "-c", "1", "-W", str(timeout_s), ip,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.DEVNULL,
    )  # fmt: skip
    return await proc.wait() == 0


def ping_available() -> bool:
    return shutil.which("ping") is not None


async def heartbeat_once(sm: async_sessionmaker[AsyncSession], hub: Hub, pinger: Pinger, concurrency: int = 32) -> None:
    async with sm() as session:
        rows = (
            await session.execute(
                select(Device.id, Device.ip_address).where(
                    Device.is_active.is_(True),
                    Device.ip_address.is_not(None),
                    Device.device_type.not_in(PASSIVE_TYPES),
                )
            )
        ).all()
    sem = asyncio.Semaphore(concurrency)

    async def probe(ip: str) -> bool:
        async with sem:
            try:
                return await pinger(ip)
            except Exception:
                log.exception("ping %s failed", ip)
                return False

    results = await asyncio.gather(*(probe(str(ip)) for _, ip in rows))
    now = datetime.now(UTC)
    async with sm() as session:
        flipped: list[Device] = []
        touched: list[DeviceAlert] = []
        for (device_id, _), up in zip(rows, results, strict=True):
            d = await session.get(Device, device_id)
            if d is None:
                continue
            if up:
                d.last_seen_at = now
            if up != d.is_online:
                d.is_online = up
                flipped.append(d)
                touched += await alerts.reachability_changed(session, d, up, now)
        await session.commit()
        for d in flipped:
            alerts.publish_device_status(hub, d)
        await alerts.publish(session, hub, touched)


async def heartbeat(
    sm: async_sessionmaker[AsyncSession], hub: Hub, interval: float, concurrency: int, pinger: Pinger = icmp_ping
) -> None:
    while True:
        try:
            await heartbeat_once(sm, hub, pinger, concurrency)
        except Exception:
            log.exception("heartbeat round failed")
        await asyncio.sleep(interval)
