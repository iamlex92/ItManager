import uuid

from fastapi import APIRouter, status
from sqlalchemy import select

from ..models import Floor
from ..schemas import FloorCreate, FloorOut, FloorUpdate
from .deps import SessionDep, not_found

router = APIRouter(prefix="/floors", tags=["floors"])


@router.get("", response_model=list[FloorOut])
async def list_floors(session: SessionDep):
    return (await session.scalars(select(Floor).order_by(Floor.building_name, Floor.floor_number))).all()


@router.post("", response_model=FloorOut, status_code=status.HTTP_201_CREATED)
async def create_floor(body: FloorCreate, session: SessionDep):
    floor = Floor(**body.model_dump())
    session.add(floor)
    await session.commit()
    await session.refresh(floor)
    return floor


@router.get("/{floor_id}", response_model=FloorOut)
async def get_floor(floor_id: uuid.UUID, session: SessionDep):
    floor = await session.get(Floor, floor_id)
    if floor is None:
        raise not_found("floor")
    return floor


@router.patch("/{floor_id}", response_model=FloorOut)
async def update_floor(floor_id: uuid.UUID, body: FloorUpdate, session: SessionDep):
    floor = await session.get(Floor, floor_id)
    if floor is None:
        raise not_found("floor")
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(floor, k, v)
    await session.commit()
    await session.refresh(floor)
    return floor


@router.delete("/{floor_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_floor(floor_id: uuid.UUID, session: SessionDep):
    floor = await session.get(Floor, floor_id)
    if floor is None:
        raise not_found("floor")
    await session.delete(floor)
    await session.commit()
