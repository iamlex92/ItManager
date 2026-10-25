"""NFC passports (spec 3.4): resolve a scanned tag, bind and unbind tags."""

import uuid

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import select

from ..models import Device
from ..schemas import DeviceOut, NfcBindRequest, NfcPayload, NfcResolveRequest
from ..services.serialize import device_out
from ..services.topology import load_graph
from .deps import SessionDep, not_found

router = APIRouter(tags=["nfc"])

NFC_APP = "netadmin"
NFC_VERSION = 1


@router.post("/nfc/resolve", response_model=DeviceOut)
async def resolve_tag(body: NfcResolveRequest, session: SessionDep):
    """Find the device for a scanned tag: by the payload's device id, else by tag UID."""
    device = None
    if body.payload is not None:
        if body.payload.app != NFC_APP:
            raise HTTPException(422, detail={"code": "foreign_tag", "message": "tag was not written by NetAdmin"})
        device = await session.get(Device, body.payload.id)
    if device is None and body.uid:
        device = await session.scalar(select(Device).where(Device.nfc_tag_uid == body.uid))
    if body.payload is None and not body.uid:
        raise HTTPException(422, detail={"code": "empty_request", "message": "send payload or uid"})
    if device is None:
        raise not_found("device for this tag")
    return device_out(device, await load_graph(session))


@router.get("/devices/{device_id}/nfc-payload", response_model=NfcPayload)
async def nfc_payload(device_id: uuid.UUID, session: SessionDep):
    """JSON to write into the tag's NDEF record."""
    device = await session.get(Device, device_id)
    if device is None:
        raise not_found("device")
    return NfcPayload(app=NFC_APP, v=NFC_VERSION, id=device.id, type=device.device_type.lower(), name=device.name)


@router.put("/devices/{device_id}/nfc", response_model=DeviceOut)
async def bind_tag(device_id: uuid.UUID, body: NfcBindRequest, session: SessionDep):
    device = await session.get(Device, device_id)
    if device is None:
        raise not_found("device")
    holder = await session.scalar(select(Device).where(Device.nfc_tag_uid == body.uid, Device.id != device_id))
    if holder is not None:
        if not body.force:
            raise HTTPException(
                409,
                detail={
                    "code": "tag_bound_elsewhere",
                    "message": f"tag is bound to {holder.name}",
                    "device_id": str(holder.id),
                    "device_name": holder.name,
                },
            )
        holder.nfc_tag_uid = None
        await session.flush()
    device.nfc_tag_uid = body.uid
    await session.commit()
    await session.refresh(device)
    return device_out(device, await load_graph(session))


@router.delete("/devices/{device_id}/nfc", status_code=status.HTTP_204_NO_CONTENT)
async def unbind_tag(device_id: uuid.UUID, session: SessionDep):
    device = await session.get(Device, device_id)
    if device is None:
        raise not_found("device")
    device.nfc_tag_uid = None
    await session.commit()
