import uuid

from fastapi import APIRouter, HTTPException, Request
from sqlalchemy import select

from ..hal.printers import PrinterUnreachable
from ..models import Device
from ..schemas import PrinterOut
from ..services.printers import poll_printer, printer_out
from .deps import HubDep, SessionDep, not_found

router = APIRouter(prefix="/printers", tags=["printers"])


async def _get(session, printer_id: uuid.UUID) -> Device:
    p = await session.get(Device, printer_id)
    if p is None or p.device_type != "PRINTER":
        raise not_found("printer")
    return p


@router.get("", response_model=list[PrinterOut])
async def list_printers(session: SessionDep):
    """Supplies as of the last Printer-MIB poll."""
    printers = (
        await session.scalars(select(Device).where(Device.device_type == "PRINTER").order_by(Device.name))
    ).all()
    return [printer_out(p) for p in printers]


@router.get("/{printer_id}", response_model=PrinterOut)
async def get_printer(printer_id: uuid.UUID, session: SessionDep):
    return printer_out(await _get(session, printer_id))


@router.post("/{printer_id}/refresh", response_model=PrinterOut)
async def refresh_printer(printer_id: uuid.UUID, request: Request, session: SessionDep, hub: HubDep):
    """Poll the printer now (SNMP GET only)."""
    p = await _get(session, printer_id)
    try:
        return await poll_printer(session, request.app.state.printer_driver, hub, p)
    except PrinterUnreachable as e:
        raise HTTPException(504, detail={"code": "printer_unreachable", "message": str(e)}) from None
