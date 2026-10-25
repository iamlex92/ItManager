from fastapi import APIRouter, Query
from sqlalchemy import select

from ..models import Device
from ..schemas import Consumable, DeviceOut, PrinterOut
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


@router.get("/printers", response_model=list[PrinterOut], tags=["printers"])
async def list_printers(session: SessionDep):
    """Printer supplies as last read over SNMP (Printer-MIB). In emulator
    mode these come from the seeded device metadata."""
    printers = (
        await session.scalars(select(Device).where(Device.device_type == "PRINTER").order_by(Device.name))
    ).all()
    out = []
    for p in printers:
        snmp = (p.metadata_ or {}).get("snmp", {})
        out.append(
            PrinterOut(
                id=p.id,
                name=p.name,
                ip=str(p.ip_address) if p.ip_address is not None else None,
                model=p.model,
                is_online=p.is_online,
                total_pages=snmp.get("total_pages"),
                consumables=[Consumable(**c) for c in snmp.get("consumables", [])],
            )
        )
    return out
