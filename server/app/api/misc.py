from fastapi import APIRouter, Query
from sqlalchemy import select

from ..models import Device
from ..schemas import DeviceOut
from ..services.serialize import device_out
from ..services.topology import load_graph
from .deps import SessionDep
from .devices import _search_clause

router = APIRouter(tags=["search"])


@router.get("/search", response_model=list[DeviceOut])
async def search(session: SessionDep, q: str = Query(min_length=2, description="Name, IP, MAC or NFC tag UID")):
    devices = (await session.scalars(select(Device).where(_search_clause(q)).order_by(Device.name).limit(50))).all()
    graph = await load_graph(session)
    return [device_out(d, graph) for d in devices]
