from fastapi import APIRouter, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from ..models import Vlan
from ..schemas import VlanCreate, VlanOut, VlanUpdate
from .deps import SessionDep, conflict, not_found

router = APIRouter(prefix="/vlans", tags=["vlans"])


@router.get("", response_model=list[VlanOut])
async def list_vlans(session: SessionDep):
    return (await session.scalars(select(Vlan).order_by(Vlan.vlan_id))).all()


@router.post("", response_model=VlanOut, status_code=status.HTTP_201_CREATED)
async def create_vlan(body: VlanCreate, session: SessionDep):
    if await session.get(Vlan, body.vlan_id):
        raise conflict("vlan_exists", f"VLAN {body.vlan_id} already exists")
    vlan = Vlan(**body.model_dump())
    vlan.subnet_cidr = str(body.subnet_cidr)
    session.add(vlan)
    await session.commit()
    await session.refresh(vlan)
    return vlan


@router.get("/{vlan_id}", response_model=VlanOut)
async def get_vlan(vlan_id: int, session: SessionDep):
    vlan = await session.get(Vlan, vlan_id)
    if vlan is None:
        raise not_found("vlan")
    return vlan


@router.patch("/{vlan_id}", response_model=VlanOut)
async def update_vlan(vlan_id: int, body: VlanUpdate, session: SessionDep):
    vlan = await session.get(Vlan, vlan_id)
    if vlan is None:
        raise not_found("vlan")
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(vlan, k, str(v) if k == "subnet_cidr" else v)
    await session.commit()
    await session.refresh(vlan)
    return vlan


@router.delete("/{vlan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_vlan(vlan_id: int, session: SessionDep):
    vlan = await session.get(Vlan, vlan_id)
    if vlan is None:
        raise not_found("vlan")
    await session.delete(vlan)
    try:
        await session.commit()
    except IntegrityError:
        raise conflict("vlan_in_use", f"VLAN {vlan_id} is assigned to ports") from None
