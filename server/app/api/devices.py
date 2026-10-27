import difflib
import hashlib
import uuid
from typing import Any

from fastapi import APIRouter, HTTPException, Query, Request, status
from sqlalchemy import String, cast, delete, func, or_, select
from sqlalchemy.exc import IntegrityError

from ..models import Device, DeviceConfig, Floor
from ..schemas import (
    BulkOut,
    CommandsOut,
    ConfigDiffOut,
    Confirmation,
    DeviceBulkDelete,
    DeviceBulkUpdate,
    DeviceConfigMeta,
    DeviceConfigOut,
    DeviceCreate,
    DeviceOut,
    DeviceType,
    DeviceUpdate,
    DiagOut,
    PingRequest,
    PortOut,
    TracerouteRequest,
    normalize_nfc_uid,
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


async def _check_host(session, host_id: uuid.UUID | None, own: set[uuid.UUID]) -> None:
    """A virtual machine's host must be another device that exists."""
    if host_id is None:
        return
    if host_id in own:
        raise HTTPException(422, detail={"code": "bad_host", "message": "Устройство не может быть хостом самому себе"})
    if await session.get(Device, host_id) is None:
        raise not_found("host device")


DEFAULT_RACK_UNITS = 42


async def _check_rack(session, device: Device) -> None:
    """A device mounted in a rack needs a rack that exists, units inside it and no neighbour on them."""
    if device.device_type != "RACK" and device.rack_units is not None:
        device.rack_units = None
    if device.device_type == "RACK":
        device.rack_id = None
        # A cabinet has nothing to ping: it is always «in the network», like a patch panel.
        device.is_online = True
        if device.rack_units is None:
            device.rack_units = DEFAULT_RACK_UNITS
        if device.id is not None:
            top = await session.scalar(
                select(func.max(Device.rack_u + func.coalesce(Device.rack_h, 1) - 1)).where(Device.rack_id == device.id)
            )
            if top and top > device.rack_units:
                raise conflict("rack_too_small", f"в стойке заняты юниты до {top}: она не может быть ниже")
    if device.rack_id is None:
        device.rack_u = device.rack_h = None
        return
    if device.rack_id == device.id:
        raise HTTPException(422, detail={"code": "bad_rack", "message": "Устройство не может стоять само в себе"})
    rack = await session.get(Device, device.rack_id)
    if rack is None or rack.device_type != "RACK":
        raise HTTPException(
            422, detail={"code": "bad_rack", "message": "Стойка не найдена: нужно устройство типа «Стойка»"}
        )
    if device.device_type == "RACK":
        raise HTTPException(422, detail={"code": "bad_rack", "message": "Стойка не ставится в стойку"})
    if device.rack_u is None:
        raise HTTPException(422, detail={"code": "rack_unit_required", "message": "Укажите юнит стойки"})
    device.rack_h = device.rack_h or 1
    last = device.rack_u + device.rack_h - 1
    size = rack.rack_units or DEFAULT_RACK_UNITS
    if last > size:
        raise HTTPException(
            422, detail={"code": "rack_overflow", "message": f"В стойке {size} U: устройство не помещается"}
        )
    stmt = select(Device).where(Device.rack_id == rack.id)
    if device.id is not None:
        stmt = stmt.where(Device.id != device.id)
    others = await session.scalars(stmt)
    for other in others:
        o_last = (other.rack_u or 1) + (other.rack_h or 1) - 1
        if device.rack_u <= o_last and (other.rack_u or 1) <= last:
            raise conflict("rack_unit_busy", f"Юниты {other.rack_u}–{o_last} в стойке заняты: {other.name}")


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
    await _check_host(session, body.host_id, set())
    device = Device()
    _apply(device, body.model_dump())
    await _check_rack(session, device)
    session.add(device)
    await _commit_device(session, device)
    await session.refresh(device)
    return device_out(device, await load_graph(session))


@router.post("/bulk", response_model=BulkOut)
async def bulk_update(body: DeviceBulkUpdate, session: SessionDep):
    """One change on many devices at once: type, floor, vendor, model or active flag. Inventory only."""
    data = body.set.model_dump(exclude_unset=True)
    if not data:
        raise HTTPException(422, detail={"code": "nothing_to_change", "message": "Не выбрано, что менять"})
    if data.get("floor_id") and await session.get(Floor, data["floor_id"]) is None:
        raise not_found("floor")
    ids = set(body.ids)
    await _check_host(session, data.get("host_id"), ids)
    devices = (await session.scalars(select(Device).where(Device.id.in_(ids)))).all()
    for device in devices:
        _apply(device, data)
    await session.commit()
    return BulkOut(changed=len(devices), missing=len(ids) - len(devices))


@router.post("/bulk-delete", response_model=BulkOut)
async def bulk_delete(body: DeviceBulkDelete, session: SessionDep):
    """Removes many devices from the inventory, with their ports and cables. Inventory only."""
    ids = set(body.ids)
    result = await session.execute(delete(Device).where(Device.id.in_(ids)))
    await session.commit()
    return BulkOut(changed=result.rowcount, missing=len(ids) - result.rowcount)


@router.get("/by-nfc/{uid}", response_model=DeviceOut)
async def get_device_by_nfc(uid: str, session: SessionDep):
    try:
        uid = normalize_nfc_uid(uid)
    except ValueError as e:
        raise HTTPException(422, detail={"code": "bad_nfc_uid", "message": str(e)}) from None
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
    await _check_host(session, body.host_id, {device_id})
    _apply(device, body.model_dump(exclude_unset=True))
    await _check_rack(session, device)
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
    other_id: uuid.UUID | None = Query(default=None, description="Another stored config instead of the live one"),
):
    """Compare a stored config with the live running-config, or with another stored one.
    Read-only on the device (a stored-to-stored comparison does not touch it at all)."""
    device = await switches.load_switch(session, device_id)
    if device is None:
        raise not_found("device")
    stmt = select(DeviceConfig).where(DeviceConfig.device_id == device_id)
    stmt = (
        stmt.where(DeviceConfig.id == base_id) if base_id else stmt.order_by(DeviceConfig.captured_at.desc()).limit(1)
    )
    base = await session.scalar(stmt)
    if base is None:
        raise not_found("stored config")
    if other_id:
        other = await session.scalar(
            select(DeviceConfig).where(DeviceConfig.id == other_id, DeviceConfig.device_id == device_id)
        )
        if other is None:
            raise not_found("stored config")
        current, to_name = other.content, f"stored {other.captured_at.isoformat()}"
    else:
        require_managed_switch(device)
        current, to_name = await driver.get_running_config(switches.device_target(device)), "running (live)"
    current_hash = hashlib.sha256(current.encode()).hexdigest()
    diff = "".join(
        difflib.unified_diff(
            base.content.splitlines(keepends=True),
            current.splitlines(keepends=True),
            fromfile=f"stored {base.captured_at.isoformat()}",
            tofile=to_name,
        )
    )
    return ConfigDiffOut(
        base_id=base.id,
        base_hash=base.config_hash,
        current_hash=current_hash,
        changed=current_hash != base.config_hash,
        diff=diff,
    )


@router.get("/{device_id}/configs/{config_id}", response_model=DeviceConfigOut)
async def get_config(device_id: uuid.UUID, config_id: uuid.UUID, session: SessionDep):
    cfg = await session.scalar(select(DeviceConfig).where(DeviceConfig.id == config_id, DeviceConfig.device_id == device_id))
    if cfg is None:
        raise not_found("stored config")
    return cfg


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
    params = {"device": device.name, "device_id": str(device.id)}
    async with audit(session, request, settings, "write_memory", params) as rec:
        rec.commands = await driver.save_config(switches.device_target(device))
    return CommandsOut(commands=rec.commands)


# --- diagnostics from the switch (exec mode, no config change) ------------


@router.post("/{device_id}/ping", response_model=DiagOut)
async def ping_from_switch(
    device_id: uuid.UUID,
    body: PingRequest,
    request: Request,
    session: SessionDep,
    driver: DriverDep,
    settings: SettingsDep,
):
    """Ping an IP from the switch. Read-only, so not write-guarded, but audited."""
    device = await switches.load_switch(session, device_id)
    if device is None:
        raise not_found("device")
    require_managed_switch(device)
    params = {"device": device.name, "device_id": str(device.id), "target": str(body.target), "count": body.count}
    async with audit(session, request, settings, "switch_ping", params) as rec:
        res = await driver.ping(switches.device_target(device), str(body.target), body.count)
        rec.commands = [res.command, res.output]
        rec.exit_code = 0 if res.success else 1
    return DiagOut(command=res.command, output=res.output, success=res.success)


@router.post("/{device_id}/traceroute", response_model=DiagOut)
async def traceroute_from_switch(
    device_id: uuid.UUID,
    body: TracerouteRequest,
    request: Request,
    session: SessionDep,
    driver: DriverDep,
    settings: SettingsDep,
):
    """Traceroute to an IP from the switch. Read-only, so not write-guarded, but audited."""
    device = await switches.load_switch(session, device_id)
    if device is None:
        raise not_found("device")
    require_managed_switch(device)
    params = {"device": device.name, "device_id": str(device.id), "target": str(body.target)}
    async with audit(session, request, settings, "switch_traceroute", params) as rec:
        res = await driver.traceroute(switches.device_target(device), str(body.target))
        rec.commands = [res.command, res.output]
        rec.exit_code = 0 if res.success else 1
    return DiagOut(command=res.command, output=res.output, success=res.success)
