import uuid

from fastapi import APIRouter, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from ..hal.base import PortConfig
from ..hal.commands import port_config_commands
from ..models import Device, Port, Vlan
from ..schemas import (
    CommandsOut,
    PortConfigChange,
    PortConfigRequest,
    PortConfigResult,
    PortCreate,
    PortOut,
    PortUpdate,
    PortWriteConfirmation,
    TraceHop,
    TraceOut,
)
from ..services import switches
from ..services.serialize import port_out
from ..services.topology import load_graph
from .deps import DriverDep, HubDep, SessionDep, SettingsDep, conflict, not_found
from .guarded import audit, require_confirmation, require_managed_switch, require_uplink_ack

router = APIRouter(prefix="/ports", tags=["ports"])


async def _get(session, port_id: uuid.UUID) -> Port:
    port = await session.get(Port, port_id)
    if port is None:
        raise not_found("port")
    return port


async def _check_vlans(session, ids: list[int]) -> None:
    if not ids:
        return
    known = set((await session.scalars(select(Vlan.vlan_id).where(Vlan.vlan_id.in_(ids)))).all())
    missing = sorted(set(ids) - known)
    if missing:
        raise HTTPException(
            status_code=422, detail={"code": "unknown_vlan", "message": f"VLANs not defined: {missing}"}
        )


def _to_hal(change: PortConfigChange) -> PortConfig:
    return PortConfig(
        mode=change.mode,
        access_vlan=change.access_vlan,
        tagged_vlans=tuple(change.tagged_vlans) if change.tagged_vlans is not None else None,
        is_admin_up=change.is_admin_up,
    )


def _require_change(cmds: list[str]) -> None:
    if not cmds:
        raise HTTPException(status_code=422, detail={"code": "empty_change", "message": "nothing to change"})


@router.post("", response_model=PortOut, status_code=status.HTTP_201_CREATED)
async def create_port(body: PortCreate, session: SessionDep):
    """Inventory only: records a port in the database, does not touch any device."""
    if await session.get(Device, body.device_id) is None:
        raise not_found("device")
    await _check_vlans(session, [v for v in [body.access_vlan, *body.tagged_vlans] if v is not None])
    port = Port(
        device_id=body.device_id,
        port_number=body.number,
        port_name=body.name,
        is_uplink=body.is_uplink,
        mode=body.mode,
        access_vlan=body.access_vlan,
        tagged_vlans=body.tagged_vlans,
        is_admin_up=body.is_admin_up,
        speed_mbps=body.speed_mbps,
        poe_enabled=body.poe_enabled,
        connected_mac=body.connected_mac,
        if_index=body.if_index,
    )
    session.add(port)
    try:
        await session.commit()
    except IntegrityError:
        raise conflict("port_exists", f"port number {body.number} already exists on this device") from None
    await session.refresh(port)
    return port_out(port)


@router.get("/{port_id}", response_model=PortOut)
async def get_port(port_id: uuid.UUID, session: SessionDep):
    port = await _get(session, port_id)
    return port_out(port, await load_graph(session))


@router.patch("/{port_id}", response_model=PortOut)
async def update_port(port_id: uuid.UUID, body: PortUpdate, session: SessionDep):
    """Edits the inventory record of a port by hand. Inventory only: nothing is sent to the switch."""
    port = await _get(session, port_id)
    data = body.model_dump(exclude_unset=True)
    await _check_vlans(
        session, [v for v in [data.get("access_vlan"), *(data.get("tagged_vlans") or [])] if v is not None]
    )
    fields = {"number": "port_number", "name": "port_name"}
    for k, v in data.items():
        if k in ("number", "name", "mode", "speed_mbps", "is_uplink", "poe_enabled") and v is None:
            continue
        setattr(port, fields.get(k, k), v if v is not None or k == "access_vlan" else [])
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise conflict("port_exists", "порт с таким номером уже есть на этом устройстве") from None
    await session.refresh(port)
    return port_out(port, await load_graph(session))


@router.delete("/{port_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_port(port_id: uuid.UUID, session: SessionDep):
    """Inventory only."""
    port = await _get(session, port_id)
    await session.delete(port)
    await session.commit()


@router.get("/{port_id}/trace", response_model=TraceOut)
async def trace_port(port_id: uuid.UUID, session: SessionDep):
    await _get(session, port_id)
    graph = await load_graph(session)
    return TraceOut(hops=[TraceHop(**h.__dict__) for h in graph.trace(port_id)])


async def _switch_port(session, port_id: uuid.UUID) -> tuple[Device, Port]:
    port = await _get(session, port_id)
    device = await switches.load_switch(session, port.device_id)
    require_managed_switch(device)
    port = next(p for p in device.ports if p.id == port_id)
    return device, port


@router.post("/{port_id}/config/preview", response_model=CommandsOut)
async def preview_port_config(port_id: uuid.UUID, body: PortConfigChange, session: SessionDep):
    """Show the CLI commands a change would send. Never contacts the device."""
    device, port = await _switch_port(session, port_id)
    await _check_vlans(session, [v for v in [body.access_vlan, *(body.tagged_vlans or [])] if v is not None])
    cmds = port_config_commands(device.vendor, port.port_name, _to_hal(body))
    _require_change(cmds)
    return CommandsOut(commands=cmds)


@router.post("/{port_id}/config", response_model=PortConfigResult)
async def apply_port_config(
    port_id: uuid.UUID,
    body: PortConfigRequest,
    request: Request,
    session: SessionDep,
    settings: SettingsDep,
    driver: DriverDep,
    hub: HubDep,
):
    """Change mode / VLANs / admin state of a switch port. State-changing: guarded."""
    device, port = await _switch_port(session, port_id)
    change = PortConfigChange.model_validate(body.model_dump(include=set(PortConfigChange.model_fields)))
    await _check_vlans(session, [v for v in [change.access_vlan, *(change.tagged_vlans or [])] if v is not None])
    _require_change(port_config_commands(device.vendor, port.port_name, _to_hal(change)))
    require_confirmation(body.confirm)
    require_uplink_ack(port.is_uplink, body.confirm_uplink)
    params = {
        "device": device.name,
        "device_id": str(device.id),
        "port": port.port_name,
        "change": change.model_dump(exclude_none=True),
        # What the port looked like before, for the change history.
        "before": {
            "mode": port.mode,
            "access_vlan": port.access_vlan,
            "tagged_vlans": list(port.tagged_vlans or []),
            "is_admin_up": port.is_admin_up,
        },
    }
    async with audit(session, request, settings, "port_config", params) as rec:
        rec.commands = await driver.apply_port_config(
            switches.device_target(device), switches.port_ref(port), _to_hal(change)
        )
    await switches.sync_device(session, driver, hub, device)
    await session.refresh(port)
    return PortConfigResult(port=port_out(port, await load_graph(session)), commands=rec.commands)


@router.post("/{port_id}/bounce", response_model=CommandsOut)
async def bounce_port(
    port_id: uuid.UUID,
    body: PortWriteConfirmation,
    request: Request,
    session: SessionDep,
    settings: SettingsDep,
    driver: DriverDep,
    hub: HubDep,
):
    """shutdown, 2 s pause, no shutdown. State-changing: guarded."""
    device, port = await _switch_port(session, port_id)
    require_confirmation(body.confirm)
    require_uplink_ack(port.is_uplink, body.confirm_uplink)
    params = {"device": device.name, "device_id": str(device.id), "port": port.port_name}
    async with audit(session, request, settings, "port_bounce", params) as rec:
        rec.commands = await driver.bounce_port(switches.device_target(device), switches.port_ref(port))
    await switches.sync_device(session, driver, hub, device)
    return CommandsOut(commands=rec.commands)
