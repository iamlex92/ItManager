"""Glue between the database and the HAL: build driver targets, pull live
port state into the database, raise/resolve link alerts, push events."""

import asyncio
import logging
import uuid
from datetime import UTC, datetime

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import selectinload

from ..hal.base import DeviceTarget, PortRef, PortStatus, SwitchDriver
from ..hal.commands import SUPPORTED_VENDORS
from ..models import Device, DeviceAlert, Port
from .realtime import Hub
from .serialize import alert_out, port_out
from .topology import PASSIVE_TYPES, load_graph

log = logging.getLogger(__name__)


def port_ref(p: Port) -> PortRef:
    return PortRef(
        number=p.port_number,
        name=p.port_name,
        is_uplink=p.is_uplink,
        mode=p.mode,
        access_vlan=p.access_vlan,
        tagged_vlans=tuple(p.tagged_vlans or ()),
        is_admin_up=p.is_admin_up,
        is_oper_up=p.is_oper_up,
        speed_mbps=p.speed_mbps,
        poe_enabled=p.poe_enabled,
        connected_mac=str(p.connected_mac) if p.connected_mac else None,
    )


def is_managed_switch(d: Device) -> bool:
    return d.device_type == "SWITCH" and (d.vendor or "").upper() in SUPPORTED_VENDORS


def device_target(d: Device) -> DeviceTarget:
    return DeviceTarget(
        id=d.id,
        name=d.name,
        vendor=(d.vendor or "").upper(),
        ip=str(d.ip_address) if d.ip_address is not None else None,
        model=d.model,
        ports=tuple(port_ref(p) for p in d.ports),
    )


async def load_switch(session: AsyncSession, device_id: uuid.UUID) -> Device | None:
    return await session.scalar(select(Device).where(Device.id == device_id).options(selectinload(Device.ports)))


def _changed(p: Port, s: PortStatus) -> bool:
    return (
        p.mode != s.mode
        or p.access_vlan != s.access_vlan
        or list(p.tagged_vlans or []) != list(s.tagged_vlans)
        or p.is_admin_up != s.is_admin_up
        or p.is_oper_up != s.is_oper_up
        or p.speed_mbps != s.speed_mbps
        or p.poe_enabled != s.poe_enabled
    )


async def sync_device(session: AsyncSession, driver: SwitchDriver, hub: Hub, device: Device) -> list[Port]:
    """Read live port state of one switch and store it. Read-only on the device.
    Returns the ports whose state changed. Commits."""
    statuses = await driver.get_port_statuses(device_target(device))
    by_number = {p.port_number: p for p in device.ports}
    now = datetime.now(UTC)
    changed: list[Port] = []
    new_alerts: list[DeviceAlert] = []
    for s in statuses:
        p = by_number.get(s.number)
        if p is None or not _changed(p, s):
            continue
        went_down = p.is_oper_up and not s.is_oper_up and s.is_admin_up
        came_up = not p.is_oper_up and s.is_oper_up
        p.mode = s.mode
        p.access_vlan = s.access_vlan
        p.tagged_vlans = list(s.tagged_vlans)
        p.is_admin_up = s.is_admin_up
        p.is_oper_up = s.is_oper_up
        p.speed_mbps = s.speed_mbps
        p.poe_enabled = s.poe_enabled
        changed.append(p)
        if went_down:
            new_alerts.append(
                DeviceAlert(
                    device_id=device.id,
                    port_id=p.id,
                    severity="CRITICAL" if p.is_uplink else "WARNING",
                    alert_type="LINK_DOWN",
                    message=f"{device.name} {p.port_name}: link down",
                )
            )
        if came_up:
            await session.execute(
                update(DeviceAlert)
                .where(
                    DeviceAlert.port_id == p.id,
                    DeviceAlert.alert_type == "LINK_DOWN",
                    DeviceAlert.is_resolved.is_(False),
                )
                .values(is_resolved=True, resolved_at=now)
            )
    device.is_online = True
    device.last_seen_at = now
    session.add_all(new_alerts)
    await session.commit()

    if changed or new_alerts:
        graph = await load_graph(session)
        for p in changed:
            hub.publish(
                device.id,
                {
                    "type": "port_status",
                    "device_id": str(device.id),
                    "port": port_out(p, graph).model_dump(mode="json"),
                },
            )
        for a in new_alerts:
            await session.refresh(a)
            hub.publish(device.id, {"type": "alert", "alert": alert_out(a).model_dump(mode="json")})
        if changed:
            await refresh_end_devices(session, hub)
    return changed


async def refresh_end_devices(session: AsyncSession, hub: Hub) -> None:
    """End devices (PCs, printers, ...) are online when the switch port they
    are cabled to is up. Stands in for ICMP heartbeat until that exists."""
    graph = await load_graph(session)
    now = datetime.now(UTC)
    for d in graph.devices.values():
        if d.device_type == "SWITCH" or d.device_type in PASSIVE_TYPES:
            continue
        sp = graph.switch_port_for(d.id)
        if sp is None:
            continue
        online = sp.is_oper_up and d.is_active
        if online != d.is_online:
            d.is_online = online
            if online:
                d.last_seen_at = now
            hub.publish(d.id, {"type": "device_status", "device_id": str(d.id), "is_online": online})
    await session.commit()


async def poll_once(sm: async_sessionmaker[AsyncSession], driver: SwitchDriver, hub: Hub) -> None:
    async with sm() as session:
        ids = list(
            (
                await session.scalars(
                    select(Device.id).where(Device.device_type == "SWITCH", Device.is_active.is_(True))
                )
            ).all()
        )
    for device_id in ids:
        async with sm() as session:
            device = await load_switch(session, device_id)
            if device is None or not is_managed_switch(device):
                continue
            try:
                await sync_device(session, driver, hub, device)
            except NotImplementedError:
                raise
            except Exception:
                log.exception("polling %s failed", device.name)
                await session.rollback()
                await mark_unreachable(session, hub, device)


async def mark_unreachable(session: AsyncSession, hub: Hub, device: Device) -> None:
    if not device.is_online:
        return
    device.is_online = False
    alert = DeviceAlert(
        device_id=device.id,
        severity="CRITICAL",
        alert_type="HOST_DOWN",
        message=f"{device.name} is not responding",
    )
    session.add(alert)
    await session.commit()
    await session.refresh(alert)
    hub.publish(device.id, {"type": "device_status", "device_id": str(device.id), "is_online": False})
    hub.publish(device.id, {"type": "alert", "alert": alert_out(alert).model_dump(mode="json")})


async def poller(sm: async_sessionmaker[AsyncSession], driver: SwitchDriver, hub: Hub, interval: float) -> None:
    while True:
        try:
            await poll_once(sm, driver, hub)
        except NotImplementedError:
            log.warning("switch driver has no read support yet, port polling stopped")
            return
        except Exception:
            log.exception("port poll failed")
        await asyncio.sleep(interval)
