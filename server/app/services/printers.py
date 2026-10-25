"""Printer supply polling: store the snapshot on the device, raise and
resolve TONER_LOW alerts, push printer_status events."""

import asyncio
import logging
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..hal.printers import PrinterDriver, PrinterTarget
from ..models import Device, DeviceAlert
from ..schemas import Consumable, PrinterOut
from . import alerts
from .realtime import Hub

log = logging.getLogger(__name__)

LOW_PERCENT = 10


def printer_out(p: Device) -> PrinterOut:
    snmp = (p.metadata_ or {}).get("snmp", {})
    consumables = [Consumable(**c) for c in snmp.get("consumables", [])]
    polled = snmp.get("polled_at")
    return PrinterOut(
        id=p.id,
        name=p.name,
        ip=str(p.ip_address) if p.ip_address is not None else None,
        model=p.model,
        is_online=p.is_online,
        total_pages=snmp.get("total_pages"),
        consumables=consumables,
        low_supply=any(c.level_percent < LOW_PERCENT for c in consumables),
        polled_at=datetime.fromisoformat(polled) if polled else None,
    )


def printer_target(p: Device) -> PrinterTarget:
    return PrinterTarget(
        id=p.id,
        name=p.name,
        ip=str(p.ip_address) if p.ip_address is not None else None,
        community=p.snmp_community or "public",
        last=(p.metadata_ or {}).get("snmp", {}),
    )


async def poll_printer(session: AsyncSession, driver: PrinterDriver, hub: Hub, p: Device) -> PrinterOut:
    """Read supplies (SNMP GET only) and store them. Commits."""
    snap = await driver.poll(printer_target(p))
    now = datetime.now(UTC)
    meta = dict(p.metadata_ or {})
    meta["snmp"] = {
        "total_pages": snap.total_pages,
        "consumables": [
            {"name": s.name, "color_hex": s.color_hex, "kind": s.kind, "level_percent": s.level_percent}
            for s in snap.supplies
        ],
        "polled_at": now.isoformat(),
    }
    p.metadata_ = meta
    was_online = p.is_online
    p.is_online = True
    p.last_seen_at = now

    touched: list[DeviceAlert] = []
    if not was_online:
        touched += await alerts.reachability_changed(session, p, True, now)
    for s in snap.supplies:
        key = f"{p.name}: {s.name} "
        if s.level_percent < LOW_PERCENT:
            what = "free space" if s.kind == "WASTE" else "left"
            a = await alerts.raise_once(
                session, p, "TONER_LOW", "WARNING", f"{key}{s.level_percent}% {what}", key_prefix=key
            )
            touched += [a] if a else []
        else:
            touched += await alerts.resolve(session, p, "TONER_LOW", now, key_prefix=key)
    await session.commit()

    out = printer_out(p)
    hub.publish(p.id, {"type": "printer_status", "device_id": str(p.id), "printer": out.model_dump(mode="json")})
    if not was_online:
        alerts.publish_device_status(hub, p)
    await alerts.publish(session, hub, touched)
    return out


async def poll_all(sm: async_sessionmaker[AsyncSession], driver: PrinterDriver, hub: Hub) -> None:
    async with sm() as session:
        ids = list(
            (
                await session.scalars(
                    select(Device.id).where(Device.device_type == "PRINTER", Device.is_active.is_(True))
                )
            ).all()
        )
    for printer_id in ids:
        async with sm() as session:
            p = await session.get(Device, printer_id)
            if p is None:
                continue
            try:
                await poll_printer(session, driver, hub, p)
            except Exception as e:
                # Reachability is the heartbeat's business; just note it.
                log.warning("printer poll %s failed: %s", p.name, e)
                await session.rollback()


async def poller(sm: async_sessionmaker[AsyncSession], driver: PrinterDriver, hub: Hub, interval: float) -> None:
    while True:
        try:
            await poll_all(sm, driver, hub)
        except Exception:
            log.exception("printer poll failed")
        await asyncio.sleep(interval)
