"""Port throughput samples for the utilization charts.

Real mode reads the 64-bit IF-MIB octet counters with SNMP GET/GETNEXT (read-only) and turns the
difference between two polls into bits per second. The emulator makes up a plausible working day,
so the charts have something to show before the server is on the plant network.
"""

import asyncio
import hashlib
import logging
import time
import uuid
from collections.abc import Sequence
from datetime import UTC, datetime, timedelta, timezone
from typing import Protocol

from sqlalchemy import delete, func, insert, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..config import MonitorMode, Settings
from ..models import Device, Port, PortTraffic
from .switches import is_managed_switch, load_switch

log = logging.getLogger(__name__)

IF_NAME = "1.3.6.1.2.1.31.1.1.1.1"
IF_HC_IN_OCTETS = "1.3.6.1.2.1.31.1.1.1.6"
IF_HC_OUT_OCTETS = "1.3.6.1.2.1.31.1.1.1.10"

Rates = dict[uuid.UUID, tuple[int, int]]


class TrafficSource(Protocol):
    async def rates(self, device: Device, ports: Sequence[Port], at: datetime) -> Rates: ...


# --- emulator ---

# The emulated plant works on Moscow time, so the curve peaks in the local working day.
PLANT_TZ = timezone(timedelta(hours=3))


def _noise(*parts: object) -> float:
    """Stable pseudo-random number in [0, 1) for the same inputs."""
    digest = hashlib.blake2b(":".join(map(str, parts)).encode(), digest_size=8).digest()
    return int.from_bytes(digest, "big") / 2**64


def workday_load(at: datetime) -> float:
    """0..1: near zero at night and weekends, full during working hours, a dip at lunch."""
    local = at.astimezone(PLANT_TZ)
    h = local.hour + local.minute / 60
    if local.weekday() >= 5:
        return 0.08
    if h < 7 or h >= 20:
        return 0.04
    if h < 9:
        return 0.04 + 0.96 * (h - 7) / 2
    if 12 <= h < 13:
        return 0.55
    if h >= 17:
        return 1.0 - 0.96 * (h - 17) / 3
    return 1.0


def emulated_rate(port: Port, at: datetime) -> tuple[int, int]:
    if not (port.is_admin_up and port.is_oper_up):
        return 0, 0
    key = str(port.id)
    bucket = int(at.timestamp() // 300)
    load = workday_load(at)
    if port.is_uplink:
        peak_mbps = 150 + 350 * _noise(key, "peak")
    else:
        # Most desks are light, a few ports (file copies, CCTV) are busy.
        peak_mbps = 3 + (80 if _noise(key, "heavy") > 0.85 else 25) * _noise(key, "peak")
    down = peak_mbps * load * (0.6 + 0.8 * _noise(key, bucket, "in"))
    up = down * (0.15 + 0.35 * _noise(key, bucket, "out"))
    cap = port.speed_mbps * 1_000_000
    return min(int(down * 1_000_000), cap), min(int(up * 1_000_000), cap)


class EmulatedTraffic:
    async def rates(self, device: Device, ports: Sequence[Port], at: datetime) -> Rates:
        return {p.id: emulated_rate(p, at) for p in ports}


# --- real switches (SNMP, read-only) ---


def _suffix_int(oid: str, prefix: str) -> int | None:
    try:
        return int(oid[len(prefix) + 1 :])
    except ValueError:
        return None


class SnmpTraffic:
    def __init__(self, settings: Settings, reader=None, clock=time.monotonic) -> None:
        from ..discovery.snmp import SnmpScanner

        self._s = settings
        self._reader = reader or SnmpScanner(settings)
        self._clock = clock
        self._last: dict[uuid.UUID, tuple[float, int, int]] = {}

    async def _if_indexes(self, ip: str, auth, ports: Sequence[Port]) -> dict[int, Port]:
        by_index = {p.if_index: p for p in ports if p.if_index is not None}
        if len(by_index) == len(ports):
            return by_index
        # Ports added by hand or by the scanner may lack ifIndex: match them by ifName (gi1/0/2 == Gi1/0/2).
        by_name = {p.port_name.lower(): p for p in ports if p.if_index is None}
        for oid, value in (await self._reader.walk(ip, auth, IF_NAME)).items():
            idx = _suffix_int(oid, IF_NAME)
            port = by_name.get(str(value).lower())
            if idx is not None and port is not None:
                by_index.setdefault(idx, port)
        return by_index

    async def rates(self, device: Device, ports: Sequence[Port], at: datetime) -> Rates:
        from ..hal.printers import snmp_auth

        if device.ip_address is None:
            return {}
        ip = str(device.ip_address)
        auth = snmp_auth(self._s, device.snmp_community or "public")
        index = await self._if_indexes(ip, auth, ports)
        counters_in = await self._reader.walk(ip, auth, IF_HC_IN_OCTETS)
        counters_out = await self._reader.walk(ip, auth, IF_HC_OUT_OCTETS)
        now = self._clock()
        out: Rates = {}
        for oid, value in counters_in.items():
            idx = _suffix_int(oid, IF_HC_IN_OCTETS)
            port = index.get(idx) if idx is not None else None
            sent = counters_out.get(f"{IF_HC_OUT_OCTETS}.{idx}")
            if port is None or sent is None:
                continue
            octets_in, octets_out = int(value), int(sent)
            last = self._last.get(port.id)
            self._last[port.id] = (now, octets_in, octets_out)
            if last is None:
                continue
            dt = now - last[0]
            d_in, d_out = octets_in - last[1], octets_out - last[2]
            # A counter that went back means the switch rebooted: skip this interval.
            if dt <= 0 or d_in < 0 or d_out < 0:
                continue
            out[port.id] = (int(d_in * 8 / dt), int(d_out * 8 / dt))
        return out


def build_source(settings: Settings) -> TrafficSource:
    return SnmpTraffic(settings) if settings.monitor_mode is MonitorMode.REAL else EmulatedTraffic()


# --- sampling ---


async def _switch_ids(sm: async_sessionmaker[AsyncSession]) -> list[uuid.UUID]:
    async with sm() as s:
        return list(
            (await s.scalars(select(Device.id).where(Device.device_type == "SWITCH", Device.is_active.is_(True)))).all()
        )


async def sample_once(sm: async_sessionmaker[AsyncSession], source: TrafficSource, at: datetime, keep_days: int) -> int:
    """Stores one sample per switch port; returns how many rows were written."""
    written = 0
    for device_id in await _switch_ids(sm):
        async with sm() as s:
            device = await load_switch(s, device_id)
            if device is None or not is_managed_switch(device):
                continue
            try:
                rates = await source.rates(device, device.ports, at)
            except Exception:
                log.warning("traffic of %s not read", device.name, exc_info=True)
                continue
            if rates:
                await s.execute(
                    insert(PortTraffic),
                    [{"port_id": pid, "sampled_at": at, "in_bps": i, "out_bps": o} for pid, (i, o) in rates.items()],
                )
                written += len(rates)
            await s.execute(delete(PortTraffic).where(PortTraffic.sampled_at < at - timedelta(days=keep_days)))
            await s.commit()
    return written


async def backfill_emulated(sm: async_sessionmaker[AsyncSession], interval: float, hours: int = 24) -> None:
    """The emulator starts with a day of history, so charts are not empty on a fresh server."""
    async with sm() as s:
        if await s.scalar(select(func.count()).select_from(PortTraffic)):
            return
    source = EmulatedTraffic()
    now = datetime.now(UTC).replace(microsecond=0)
    step = timedelta(seconds=max(interval, 60))
    at = now - timedelta(hours=hours)
    while at < now:
        await sample_once(sm, source, at, keep_days=hours // 24 + 1)
        at += step


async def sampler(sm: async_sessionmaker[AsyncSession], settings: Settings, source: TrafficSource) -> None:
    if isinstance(source, EmulatedTraffic):
        try:
            await backfill_emulated(sm, settings.traffic_interval_seconds)
        except Exception:
            log.exception("traffic backfill failed")
    while True:
        try:
            await sample_once(sm, source, datetime.now(UTC).replace(microsecond=0), settings.traffic_keep_days)
        except Exception:
            log.exception("traffic sampling failed")
        await asyncio.sleep(settings.traffic_interval_seconds)
