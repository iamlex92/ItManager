from fastapi import APIRouter, Response
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from ..models import Connection, Device, Floor, Vlan
from ..netbox.export import build_zip
from .deps import SessionDep

router = APIRouter(prefix="/export", tags=["export"])


@router.get("/netbox.zip")
async def netbox_export(session: SessionDep):
    """The inventory as NetBox CSV import files. Read-only; secrets are not included."""
    floors = (await session.scalars(select(Floor).order_by(Floor.building_name, Floor.floor_number))).all()
    vlans = (await session.scalars(select(Vlan).order_by(Vlan.vlan_id))).all()
    devices = (await session.scalars(select(Device).options(selectinload(Device.ports)).order_by(Device.name))).all()
    connections = (await session.scalars(select(Connection))).all()
    return Response(
        build_zip(floors, vlans, devices, connections),
        media_type="application/zip",
        headers={"Content-Disposition": 'attachment; filename="itmanager-netbox.zip"'},
    )
