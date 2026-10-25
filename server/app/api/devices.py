import difflib
import hashlib
import uuid
from typing import Any

from fastapi import APIRouter, Query, Request, status
from sqlalchemy import String, cast, or_, select
from sqlalchemy.exc import IntegrityError

from ..models import Device, DeviceConfig
from ..schemas import (
    CommandsOut,
    ConfigDiffOut,
    Confirmation,
    DeviceConfigMeta,
    DeviceConfigOut,
    DeviceCreate,
    DeviceOut,
    DeviceType,
    DeviceUpdate,
    PortOut,
)
from ..services import switches
from ..services.serialize import device_out, port_out
from ..services.topology import load_graph
from .deps import DriverDep, HubDep, SessionDep, SettingsDep, conflict, not_found
from .guarded import audit, require_confirmation, require_managed_switch

router = APIRouter(prefix="/devices", tags=["devices"])

_FIELD_MAP = {
    "type": "device_type",
    "x": "pos_x",
    "y": "pos_y",
    "ip": "ip_address",
    "mac": "mac_address",
    "metadata": "metadata_",
}


def _apply(device: Device, data: dict[str, Any]) -> None:
    for k, v in data.items():
        if k == "ip" and v is not None:
            v = str(v)
        setattr(device, _FIELD_MAP.get(k, k), v)


async def _commit_device(session, device: Device) -> None:
    try:
        await session.commit()
    except IntegrityError as e:
        await session.rollback()
        raise conflict("integrity_error", "NFC tag already bound to another device or bad floor_id") from e


async def _get(session, device_id: uuid.UUID) -> Device:
    device = await session.get(Device, device_id)
    if device is None:
        raise not_found("device")
    return device


@router.get("", response_model=list[DeviceOut])
async def list_devices(
    session: SessionDep,
    floor_id: uuid.UUID | None = None,
    type: DeviceType | None = None,
    q: str | None = Query(default=None, description="Substring of name, IP or MAC"),
):
    stmt = select(Device).order_by(Device.device_type, Device.name)
    if floor_id:
        stmt = stmt.where(Device.floor_id == floor_id)
    if type:
        stmt = stmt.where(Device.device_type == type)
    if q:
        stmt = stmt.where(_search_clause(q))
    devices = (await session.scalars(stmt)).all()
    graph = await load_graph(session)
    return [device_out(d, graph) for d in devices]


def _search_clause(q: str):
    like = f"%{q}%"
    return or_(
        Device.name.ilike(like),
        cast(Device.ip_address, String).ilike(like),
        cast(Device.mac_address, String).ilike(like.replace("-", ":")),
        Device.nfc_tag_uid.ilike(like),
    )


@router.post("", response_model=DeviceOut, status_code=status.HTTP_201_CREATED)
async def create_device(body: DeviceCreate, session: SessionDep):
    device = Device()
    _apply(device, body.model_dump())
    session.add(device)
    await _commit_device(session, device)
    await session.refresh(device)
    return device_out(device, await load_graph(session))


@router.get("/by-nfc/{uid}", response_model=DeviceOut)
async def get_device_by_nfc(uid: str, session: SessionDep):
    device = await session.scalar(select(Device).where(Device.nfc_tag_uid == uid))
    if device is None:
        raise not_found("device with this NFC tag")
    return device_out(device, await load_graph(session))


@router.get("/{device_id}", response_model=DeviceOut)
async def get_device(device_id: uuid.UUID, session: SessionDep):
    device = await _get(session, device_id)
    return device_out(device, await load_graph(session))


@router.patch("/{device_id}", response_model=DeviceOut)
async def update_device(device_id: uuid.UUID, body: DeviceUpdate, session: SessionDep):
    device = await _get(session, device_id)
    _apply(device, body.model_dump(exclude_unset=True))
    await _commit_device(session, device)
    await session.refresh(device)
    return device_out(device, await load_graph(session))


@router.delete("/{device_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_device(device_id: uuid.UUID, session: SessionDep):
    device = await _get(session, device_id)
    await session.delete(device)
    await session.commit()


@router.get("/{device_id}/ports", response_model=list[PortOut])
async def list_device_ports(device_id: uuid.UUID, session: SessionDep):
    device = await switches.load_switch(session, device_id)
    if device is None:
        raise not_found("device")
    graph = await load_graph(session)
    return [port_out(p, graph) for p in device.ports]


@router.post("/{device_id}/refresh", response_model=list[PortOut])
async def refresh_device(device_id: uuid.UUID, session: SessionDep, driver: DriverDep, hub: HubDep):
    """Re-read live port state from the switch now. Read-only on the device."""
    device = await switches.load_switch(session, device_id)
    if device is None:
        raise not_found("device")
    require_managed_switch(device)
    await switches.sync_device(session, driver, hub, device)
    graph = await load_graph(session)
    return [port_out(p, graph) for p in device.ports]


# --- configuration backups (NCM) ---------------------------------------------


@router.get("/{device_id}/configs", response_model=list[DeviceConfigMeta])
async def list_configs(device_id: uuid.UUID, session: SessionDep):
    await _get(session, device_id)
    return (
        await session.scalars(
            select(DeviceConfig).where(DeviceConfig.device_id == device_id).order_by(DeviceConfig.captured_at.desc())
        )
    ).all()


@router.post("/{device_id}/configs/backup", response_model=DeviceConfigOut, status_code=status.HTTP_201_CREATED)
async def backup_config(device_id: uuid.UUID, session: SessionDep, driver: DriverDep):
    """Read running-config from the switch and store it. Read-only on the device."""
    device = await switches.load_switch(session, device_id)
    if device is None:
        raise not_found("device")
    require_managed_switch(device)
    content = await driver.get_running_config(switches.device_target(device))
    cfg = DeviceConfig(
        device_id=device.id,
        config_type="RUNNING",
        content=content,
        config_hash=hashlib.sha256(content.encode()).hexdigest(),
        triggered_by="MANUAL",
    )
    session.add(cfg)
    await session.commit()
    await session.refresh(cfg)
    return cfg


@router.get("/{device_id}/configs/diff", response_model=ConfigDiffOut)
async def diff_config(
    device_id: uuid.UUID,
    session: SessionDep,
    driver: DriverDep,
    base_id: uuid.UUID | None = Query(default=None, description="Stored config to compare with; latest if omitted"),
):
    """Compare a stored config with the live running-config. Read-only on the device."""
    device = await switches.load_switch(session, device_id)
    if device is None:
        raise not_found("device")
    require_managed_switch(device)
    stmt = select(DeviceConfig).where(DeviceConfig.device_id == device_id)
    stmt = (
        stmt.where(DeviceConfig.id == base_id) if base_id else stmt.order_by(DeviceConfig.captured_at.desc()).limit(1)
    )
    base = await session.scalar(stmt)
    if base is None:
        raise not_found("stored config")
    current = await driver.get_running_config(switches.device_target(device))
    current_hash = hashlib.sha256(current.encode()).hexdigest()
    diff = "".join(
        difflib.unified_diff(
            base.content.splitlines(keepends=True),
            current.splitlines(keepends=True),
            fromfile=f"stored {base.captured_at.isoformat()}",
            tofile="running (live)",
        )
    )
    return ConfigDiffOut(
        base_id=base.id,
        base_hash=base.config_hash,
        current_hash=current_hash,
        changed=current_hash != base.config_hash,
        diff=diff,
    )


@router.post("/{device_id}/write-memory", response_model=CommandsOut)
async def write_memory(
    device_id: uuid.UUID,
    body: Confirmation,
    request: Request,
    session: SessionDep,
    driver: DriverDep,
    settings: SettingsDep,
):
    """Save running-config to startup-config. State-changing: guarded."""
    device = await switches.load_switch(session, device_id)
    if device is None:
        raise not_found("device")
    require_managed_switch(device)
    require_confirmation(body.confirm)
    params = {"device": device.name}
    async with audit(session, request, settings, "write_memory", params) as rec:
        rec.commands = await driver.save_config(switches.device_target(device))
    return CommandsOut(commands=rec.commands)
