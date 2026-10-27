"""Monitors, keyboards, docking stations and other equipment without a network port, attached to devices."""

import uuid
from typing import Any

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import or_, select

from ..models import Device, Peripheral
from ..schemas import PeripheralCreate, PeripheralOut, PeripheralUpdate
from .deps import SessionDep, not_found

router = APIRouter(prefix="/peripherals", tags=["peripherals"])


def _out(p: Peripheral, names: dict[uuid.UUID, str]) -> PeripheralOut:
    out = PeripheralOut.model_validate(p)
    out.device_name = names.get(p.device_id) if p.device_id else None
    return out


async def _names(session, ids: set[uuid.UUID | None]) -> dict[uuid.UUID, str]:
    ids = {i for i in ids if i is not None}
    if not ids:
        return {}
    rows = await session.execute(select(Device.id, Device.name).where(Device.id.in_(ids)))
    return {r.id: r.name for r in rows}


async def _check_device(session, device_id: uuid.UUID | None) -> None:
    if device_id is None:
        return
    device = await session.get(Device, device_id)
    if device is None:
        raise not_found("device")
    if device.device_type in {"RACK", "SOCKET", "PATCH_PANEL"}:
        raise HTTPException(
            422,
            detail={"code": "bad_device", "message": "К стойке, розетке или патч-панели периферию не подключают"},
        )


def _clean(data: dict[str, Any]) -> dict[str, Any]:
    # A field cleared in a form comes as "" or spaces: store it as empty.
    return {k: (v.strip() or None) if isinstance(v, str) and k != "kind" else v for k, v in data.items()}


@router.get("", response_model=list[PeripheralOut])
async def list_peripherals(
    session: SessionDep,
    device_id: uuid.UUID | None = None,
    unassigned: bool = False,
    q: str | None = Query(default=None, description="Substring of vendor, model, specs, serial or inventory number"),
):
    stmt = select(Peripheral).order_by(Peripheral.kind, Peripheral.vendor, Peripheral.model, Peripheral.created_at)
    if device_id:
        stmt = stmt.where(Peripheral.device_id == device_id)
    if unassigned:
        stmt = stmt.where(Peripheral.device_id.is_(None))
    if q:
        like = f"%{q.strip()}%"
        stmt = stmt.where(
            or_(
                Peripheral.vendor.ilike(like),
                Peripheral.model.ilike(like),
                Peripheral.specs.ilike(like),
                Peripheral.serial_number.ilike(like),
                Peripheral.inventory_number.ilike(like),
                Peripheral.comment.ilike(like),
            )
        )
    items = (await session.scalars(stmt)).all()
    names = await _names(session, {p.device_id for p in items})
    return [_out(p, names) for p in items]


@router.post("", response_model=PeripheralOut, status_code=status.HTTP_201_CREATED)
async def create_peripheral(body: PeripheralCreate, session: SessionDep):
    await _check_device(session, body.device_id)
    item = Peripheral(**_clean(body.model_dump()))
    session.add(item)
    await session.commit()
    await session.refresh(item)
    return _out(item, await _names(session, {item.device_id}))


async def _get(session, peripheral_id: uuid.UUID) -> Peripheral:
    item = await session.get(Peripheral, peripheral_id)
    if item is None:
        raise not_found("peripheral")
    return item


@router.get("/{peripheral_id}", response_model=PeripheralOut)
async def get_peripheral(peripheral_id: uuid.UUID, session: SessionDep):
    item = await _get(session, peripheral_id)
    return _out(item, await _names(session, {item.device_id}))


@router.patch("/{peripheral_id}", response_model=PeripheralOut)
async def update_peripheral(peripheral_id: uuid.UUID, body: PeripheralUpdate, session: SessionDep):
    """Changes the record; device_id moves it to another device, null puts it back in stock."""
    item = await _get(session, peripheral_id)
    data = _clean(body.model_dump(exclude_unset=True))
    if data.get("kind", "") is None:
        raise HTTPException(422, detail={"code": "kind_required", "message": "Укажите вид периферии"})
    if "device_id" in data:
        await _check_device(session, data["device_id"])
    for k, v in data.items():
        setattr(item, k, v)
    await session.commit()
    await session.refresh(item)
    return _out(item, await _names(session, {item.device_id}))


@router.delete("/{peripheral_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_peripheral(peripheral_id: uuid.UUID, session: SessionDep):
    item = await _get(session, peripheral_id)
    await session.delete(item)
    await session.commit()
