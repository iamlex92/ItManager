import uuid

from fastapi import APIRouter, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from ..models import DeviceTemplate, Port
from ..schemas import (
    DeviceCreate,
    DeviceFromTemplate,
    DeviceOut,
    DeviceTemplateCreate,
    DeviceTemplateOut,
    DeviceTemplateUpdate,
)
from .deps import SessionDep, conflict, not_found
from .devices import create_device

router = APIRouter(prefix="/device-templates", tags=["device templates"])


async def _get(session, template_id: uuid.UUID) -> DeviceTemplate:
    template = await session.get(DeviceTemplate, template_id)
    if template is None:
        raise not_found("device template")
    return template


async def _commit(session) -> None:
    try:
        await session.commit()
    except IntegrityError as e:
        await session.rollback()
        raise conflict("name_taken", "Шаблон с таким именем уже есть") from e


@router.get("", response_model=list[DeviceTemplateOut])
async def list_templates(session: SessionDep):
    return (await session.scalars(select(DeviceTemplate).order_by(DeviceTemplate.name))).all()


@router.post("", response_model=DeviceTemplateOut, status_code=status.HTTP_201_CREATED)
async def create_template(body: DeviceTemplateCreate, session: SessionDep):
    template = DeviceTemplate(**body.model_dump())
    session.add(template)
    await _commit(session)
    await session.refresh(template)
    return template


@router.patch("/{template_id}", response_model=DeviceTemplateOut)
async def update_template(template_id: uuid.UUID, body: DeviceTemplateUpdate, session: SessionDep):
    template = await _get(session, template_id)
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(template, k, v)
    await _commit(session)
    await session.refresh(template)
    return template


@router.delete("/{template_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_template(template_id: uuid.UUID, session: SessionDep):
    await session.delete(await _get(session, template_id))
    await session.commit()


@router.post("/{template_id}/create-device", response_model=DeviceOut, status_code=status.HTTP_201_CREATED)
async def create_device_from_template(template_id: uuid.UUID, body: DeviceFromTemplate, session: SessionDep):
    """Adds a device to the inventory from a template: its type, vendor, model, rack height and ports."""
    t = await _get(session, template_id)
    device = await create_device(
        DeviceCreate(
            name=body.name,
            type=t.device_type,
            vendor=t.vendor,
            model=t.model,
            floor_id=body.floor_id,
            x=body.x,
            y=body.y,
            ip=body.ip,
            comment=t.comment,
            rack_id=body.rack_id,
            rack_u=body.rack_u,
            rack_h=t.rack_h if body.rack_id else None,
        ),
        session,
    )
    for n in range(1, t.port_count + 1):
        session.add(
            Port(device_id=device.id, port_number=n, port_name=f"{t.port_prefix}{n}", speed_mbps=t.port_speed_mbps)
        )
    if t.port_count:
        await session.commit()
    return device
