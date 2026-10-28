"""Real link state of switch ports over SNMP (IF-MIB), read-only.

Used when devices are monitored for real (MONITOR_MODE=real) but switches are not driven over SSH
(HAL_MODE=emulator): the ports' link, admin state and speed come from ifOperStatus, ifAdminStatus and
ifHighSpeed with the switch's SNMP community. Ports are matched by ifIndex, or by ifName for ports added by
hand or by a config import (Gi1/0/1, gi1/0/1 and GigabitEthernet1/0/1 are the same port); a matched ifIndex is
stored, so traps and the next polls find the port directly.
"""

import asyncio
import logging
import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..cliimport.parse import normalize_if_name
from ..config import Settings
from ..models import Device, DeviceAlert, Port
from . import alerts
from .realtime import Hub
from .switches import is_managed_switch, load_switch, publish_ports
from .traffic import _switch_ids

log = logging.getLogger(__name__)

IF_ADMIN_STATUS = "1.3.6.1.2.1.2.2.1.7"
IF_OPER_STATUS = "1.3.6.1.2.1.2.2.1.8"
IF_NAME = "1.3.6.1.2.1.31.1.1.1.1"
IF_HIGH_SPEED = "1.3.6.1.2.1.31.1.1.1.15"


@dataclass(frozen=True)
class LinkReading:
    if_index: int
    admin_up: bool
    oper_up: bool
    # Mbit/s; None when the switch reports 0 (a port without link).
    speed_mbps: int | None


def _by_index(table: dict[str, object], prefix: str) -> dict[int, object]:
    out: dict[int, object] = {}
    for oid, value in table.items():
        try:
            out[int(oid[len(prefix) + 1 :])] = value
        except ValueError:
            continue
    return out


def _int(value: object) -> int | None:
    try:
        return int(value)  # type: ignore[call-overload]
    except (TypeError, ValueError):
        return None


class SnmpLinks:
    def __init__(self, settings: Settings, reader=None) -> None:
        from ..discovery.snmp import SnmpScanner

        self._s = settings
        self._reader = reader or SnmpScanner(settings)

    async def read(self, device: Device, ports: Sequence[Port]) -> dict[uuid.UUID, LinkReading]:
        from ..hal.printers import snmp_auth

        if device.ip_address is None:
            return {}
        ip = str(device.ip_address)
        auth = snmp_auth(self._s, device.snmp_community or "public")
        oper = _by_index(await self._reader.walk(ip, auth, IF_OPER_STATUS), IF_OPER_STATUS)
        if not oper:
            raise TimeoutError(f"no IF-MIB answer from {ip} (SNMP community or ACL?)")
        admin = _by_index(await self._reader.walk(ip, auth, IF_ADMIN_STATUS), IF_ADMIN_STATUS)
        speed = _by_index(await self._reader.walk(ip, auth, IF_HIGH_SPEED), IF_HIGH_SPEED)
        index = {p.if_index: p for p in ports if p.if_index is not None}
        if len(index) < len(ports):
            names = _by_index(await self._reader.walk(ip, auth, IF_NAME), IF_NAME)
            by_name = {normalize_if_name(p.port_name): p for p in ports if p.if_index is None}
            for idx, name in names.items():
                p = by_name.get(normalize_if_name(str(name)))
                if p is not None and idx not in index:
                    index[idx] = p
        out: dict[uuid.UUID, LinkReading] = {}
        for idx, port in index.items():
            if idx not in oper:
                continue
            mbps = _int(speed.get(idx))
            out[port.id] = LinkReading(
                if_index=idx,
                admin_up=_int(admin.get(idx)) != 2,  # 1 up, 2 down, 3 testing
                oper_up=_int(oper[idx]) == 1,  # 1 up; down, dormant, notPresent, lowerLayerDown are not
                speed_mbps=mbps or None,
            )
        return out


async def apply_readings(
    session: AsyncSession, hub: Hub, device: Device, readings: dict[uuid.UUID, LinkReading], now: datetime
) -> list[Port]:
    """Store what changed, raise or resolve LINK_DOWN, push live updates. Commits."""
    changed: list[Port] = []
    touched: list[DeviceAlert] = []
    for p in device.ports:
        r = readings.get(p.id)
        if r is None:
            continue
        if p.if_index != r.if_index:
            p.if_index = r.if_index
        speed = r.speed_mbps if r.oper_up and r.speed_mbps else p.speed_mbps
        if (p.is_admin_up, p.is_oper_up, p.speed_mbps) == (r.admin_up, r.oper_up, speed):
            continue
        went_down = p.is_oper_up and not r.oper_up and r.admin_up
        came_up = not p.is_oper_up and r.oper_up
        p.is_admin_up, p.is_oper_up, p.speed_mbps = r.admin_up, r.oper_up, speed
        changed.append(p)
        touched += await alerts.link_changed(session, device, p, went_down, came_up, now)
    await session.commit()
    if changed:
        await publish_ports(session, hub, device, changed)
    await alerts.publish(session, hub, touched)
    return changed


async def poll_once(sm: async_sessionmaker[AsyncSession], source: SnmpLinks, hub: Hub, failing: set[uuid.UUID]) -> None:
    for device_id in await _switch_ids(sm):
        async with sm() as session:
            device = await load_switch(session, device_id)
            if device is None or not is_managed_switch(device) or device.ip_address is None:
                continue
            try:
                readings = await source.read(device, device.ports)
            except Exception as e:
                # Said once per outage, not every poll.
                if device_id not in failing:
                    log.warning("link state of %s not read over SNMP: %s", device.name, e)
                    failing.add(device_id)
                continue
            if device_id in failing:
                log.warning("link state of %s is read over SNMP again", device.name)
                failing.discard(device_id)
            await apply_readings(session, hub, device, readings, datetime.now(UTC))


async def poller(sm: async_sessionmaker[AsyncSession], source: SnmpLinks, hub: Hub, interval: float) -> None:
    failing: set[uuid.UUID] = set()
    while True:
        try:
            await poll_once(sm, source, hub, failing)
        except Exception:
            log.exception("link state poll failed")
        await asyncio.sleep(interval)
