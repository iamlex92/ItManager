"""PoE: how much power each switch has and how much its ports draw. Read-only: `show power inline` on the switch."""

import asyncio
import uuid
from typing import Any

from fastapi import APIRouter
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from ..cliimport.parse import normalize_if_name
from ..hal.base import SwitchError
from ..hal.poe import PoeReport, level_of
from ..models import Device
from ..services import switches
from .deps import DriverDep, SessionDep, not_found

router = APIRouter(prefix="/poe", tags=["poe"])

CONCURRENCY = 4


def _out(device: Device, report: PoeReport | None, error: str = "", with_ports: bool = True) -> dict[str, Any]:
    names = {normalize_if_name(p.port_name): p for p in device.ports}
    pct = report.percent if report else None
    ports = []
    if report and with_ports:
        for p in report.ports:
            known = names.get(p.name)
            ports.append({
                "port": known.port_name if known else p.name, "port_id": str(known.id) if known else None,
                "admin": p.admin, "state": p.state, "power_w": p.power_w, "device": p.device, "max_w": p.max_w,
            })  # fmt: skip
    return {
        "device_id": str(device.id),
        "name": device.name,
        "ok": report is not None and not error,
        "supported": report is not None,
        "error": error,
        "budget_w": report.budget_w if report else None,
        "used_w": report.used_w if report else None,
        "percent": pct,
        "level": level_of(pct),
        "powered": sum(1 for p in report.ports if p.state == "on") if report else 0,
        "faults": sum(1 for p in report.ports if p.state == "fault") if report else 0,
        "ports": ports,
    }


async def _read(driver, device: Device, with_ports: bool) -> dict[str, Any]:
    try:
        report = await driver.get_poe(switches.device_target(device))
    except SwitchError as e:
        return _out(device, None, str(e), with_ports)
    except Exception as e:  # one switch must not stop the round
        return _out(device, None, f"{type(e).__name__}: {e}", with_ports)
    return _out(device, report, "", with_ports)


@router.get("")
async def poe_all(session: SessionDep, driver: DriverDep):
    """Every managed switch with its budget; the switches that have no PoE are left out of the answer's `rows`
    and counted in `without_poe`."""
    devices = (
        await session.scalars(
            select(Device)
            .where(Device.device_type == "SWITCH", Device.is_active.is_(True))
            .options(selectinload(Device.ports))
        )
    ).all()
    managed = [d for d in devices if switches.is_managed_switch(d) and d.ip_address is not None]
    sem = asyncio.Semaphore(CONCURRENCY)

    async def one(d: Device):
        async with sem:
            return await _read(driver, d, with_ports=False)

    rows = await asyncio.gather(*(one(d) for d in managed))
    shown = [r for r in rows if r["supported"] or r["error"]]
    shown.sort(key=lambda r: (-(r["percent"] if r["percent"] is not None else -1), r["name"].lower()))
    return {"rows": shown, "without_poe": sum(1 for r in rows if not r["supported"] and not r["error"])}


@router.get("/{device_id}")
async def poe_one(device_id: uuid.UUID, session: SessionDep, driver: DriverDep):
    device = await switches.load_switch(session, device_id)
    if device is None or not switches.is_managed_switch(device):
        raise not_found("switch")
    return await _read(driver, device, with_ports=True)
