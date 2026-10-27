"""A drawing of a device's front panel (SVG) with its ports marked on it, shown in the rack view."""

import re
import uuid
import xml.etree.ElementTree as ET

from fastapi import APIRouter, HTTPException, Request, Response, status
from sqlalchemy import select

from ..models import Device, DeviceFaceplate, Port
from ..schemas import FaceplateCopyIn, FaceplateOut, FaceplatePortOut, FaceplatePortsIn
from .deps import SessionDep, not_found

router = APIRouter(prefix="/devices", tags=["faceplates"])

MAX_SVG_BYTES = 2 * 1024 * 1024
_NUMBER = re.compile(r"^\s*([0-9]*\.?[0-9]+)")
_SVG_HEADERS = {"X-Content-Type-Options": "nosniff", "Content-Security-Policy": "sandbox; default-src 'none'"}


def _bad_svg(message: str) -> HTTPException:
    return HTTPException(415, detail={"code": "bad_svg", "message": message})


def svg_size(text: str) -> tuple[float, float]:
    """Width and height of an SVG drawing: its viewBox, else its width and height attributes.

    Refuses anything that is not a plain SVG document: entity declarations are not allowed."""
    if "<!ENTITY" in text or "<!DOCTYPE" in text.upper():
        raise _bad_svg("SVG с DOCTYPE или ENTITY не принимается")
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        raise _bad_svg("файл не читается как SVG") from None
    if root.tag not in ("svg", "{http://www.w3.org/2000/svg}svg"):
        raise _bad_svg("нужен файл SVG")
    box = (root.get("viewBox") or "").replace(",", " ").split()
    if len(box) == 4:
        try:
            w, h = float(box[2]), float(box[3])
        except ValueError:
            w = h = 0.0
        if w > 0 and h > 0:
            return w, h
    sizes = []
    for attr in ("width", "height"):
        m = _NUMBER.match(root.get(attr) or "")
        sizes.append(float(m.group(1)) if m else 0.0)
    if sizes[0] > 0 and sizes[1] > 0:
        return sizes[0], sizes[1]
    raise _bad_svg("у SVG нет размеров: задайте viewBox или width и height")


async def _device(session, device_id: uuid.UUID) -> Device:
    device = await session.get(Device, device_id)
    if device is None:
        raise not_found("device")
    return device


async def _out(session, plate: DeviceFaceplate) -> FaceplateOut:
    ports = {p.id: p for p in await session.scalars(select(Port).where(Port.device_id == plate.device_id))}
    marks = []
    for m in plate.ports or []:
        port = ports.get(uuid.UUID(m["port_id"]))
        if port is None:
            continue  # the port was removed since it was marked
        marks.append(
            FaceplatePortOut(
                port_id=port.id,
                port_name=port.port_name,
                port_number=port.port_number,
                x=m["x"],
                y=m["y"],
                w=m["w"],
                h=m["h"],
            )
        )
    marks.sort(key=lambda m: m.port_number)
    return FaceplateOut(
        device_id=plate.device_id, width=plate.width, height=plate.height, ports=marks, updated_at=plate.updated_at
    )


async def _plate(session, device_id: uuid.UUID) -> DeviceFaceplate:
    plate = await session.get(DeviceFaceplate, device_id)
    if plate is None:
        raise not_found("faceplate")
    return plate


@router.get("/{device_id}/faceplate", response_model=FaceplateOut)
async def get_faceplate(device_id: uuid.UUID, session: SessionDep):
    return await _out(session, await _plate(session, device_id))


@router.get("/{device_id}/faceplate/svg")
async def get_faceplate_svg(device_id: uuid.UUID, session: SessionDep):
    plate = await _plate(session, device_id)
    return Response(plate.svg, media_type="image/svg+xml", headers=_SVG_HEADERS)


@router.put("/{device_id}/faceplate", response_model=FaceplateOut)
async def upload_faceplate(device_id: uuid.UUID, request: Request, session: SessionDep):
    """Stores the panel drawing (raw SVG body). Port marks already set stay where they were."""
    device = await _device(session, device_id)
    if device.device_type == "RACK":
        raise HTTPException(422, detail={"code": "bad_device", "message": "Схема панели задаётся устройству в стойке"})
    data = await request.body()
    if not data.strip():
        raise _bad_svg("пустой файл")
    if len(data) > MAX_SVG_BYTES:
        raise HTTPException(413, detail={"code": "svg_too_big", "message": "SVG больше 2 МБ"})
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise _bad_svg("SVG должен быть в кодировке UTF-8") from None
    width, height = svg_size(text)
    plate = await session.get(DeviceFaceplate, device_id)
    if plate is None:
        plate = DeviceFaceplate(device_id=device_id, ports=[])
        session.add(plate)
    plate.svg, plate.width, plate.height = text, width, height
    await session.commit()
    await session.refresh(plate)
    return await _out(session, plate)


@router.put("/{device_id}/faceplate/ports", response_model=FaceplateOut)
async def set_faceplate_ports(device_id: uuid.UUID, body: FaceplatePortsIn, session: SessionDep):
    """Replaces the port marks. Each port of this device may be marked once."""
    plate = await _plate(session, device_id)
    own = set(await session.scalars(select(Port.id).where(Port.device_id == device_id)))
    seen: set[uuid.UUID] = set()
    marks = []
    for m in body.ports:
        if m.port_id not in own:
            raise HTTPException(422, detail={"code": "bad_port", "message": "Порт не принадлежит этому устройству"})
        if m.port_id in seen:
            raise HTTPException(422, detail={"code": "duplicate_port", "message": "Порт отмечен на схеме дважды"})
        seen.add(m.port_id)
        marks.append(
            {
                "port_id": str(m.port_id),
                "x": round(min(m.x, 1 - m.w), 5),
                "y": round(min(m.y, 1 - m.h), 5),
                "w": round(m.w, 5),
                "h": round(m.h, 5),
            }
        )
    plate.ports = marks
    await session.commit()
    await session.refresh(plate)
    return await _out(session, plate)


@router.post("/{device_id}/faceplate/copy", response_model=FaceplateOut)
async def copy_faceplate(device_id: uuid.UUID, body: FaceplateCopyIn, session: SessionDep):
    """Takes the drawing and port marks of another device, usually one of the same model. Ports are
    matched by name, then by number."""
    await _device(session, device_id)
    if body.from_device_id == device_id:
        raise HTTPException(422, detail={"code": "same_device", "message": "Нужно другое устройство"})
    source = await _plate(session, body.from_device_id)
    theirs = {p.id: p for p in await session.scalars(select(Port).where(Port.device_id == body.from_device_id))}
    mine = list(await session.scalars(select(Port).where(Port.device_id == device_id)))
    by_name = {p.port_name.lower(): p for p in mine}
    by_number = {p.port_number: p for p in mine}
    marks, used = [], set()
    for m in source.ports or []:
        src = theirs.get(uuid.UUID(m["port_id"]))
        if src is None:
            continue
        dst = by_name.get(src.port_name.lower()) or by_number.get(src.port_number)
        if dst is None or dst.id in used:
            continue
        used.add(dst.id)
        marks.append({**m, "port_id": str(dst.id)})
    plate = await session.get(DeviceFaceplate, device_id)
    if plate is None:
        plate = DeviceFaceplate(device_id=device_id)
        session.add(plate)
    plate.svg, plate.width, plate.height, plate.ports = source.svg, source.width, source.height, marks
    await session.commit()
    await session.refresh(plate)
    return await _out(session, plate)


@router.delete("/{device_id}/faceplate", status_code=status.HTTP_204_NO_CONTENT)
async def delete_faceplate(device_id: uuid.UUID, session: SessionDep):
    plate = await _plate(session, device_id)
    await session.delete(plate)
    await session.commit()
