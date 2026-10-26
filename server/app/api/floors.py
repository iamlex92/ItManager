import hashlib
import uuid

from fastapi import APIRouter, HTTPException, Request, Response, status
from sqlalchemy import select

from ..models import Floor, FloorPlanImage
from ..schemas import FloorCreate, FloorOut, FloorUpdate
from .deps import SessionDep, not_found

# A phone photo of a plan or a PDF page rendered by the app fits; anything bigger is a mistake.
MAX_PLAN_BYTES = 15 * 1024 * 1024
PLAN_TYPES = ("image/png", "image/jpeg", "image/svg+xml")

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


def plan_type(data: bytes) -> str | None:
    """The type by content, not by the client's word: only PNG, JPEG and SVG are kept."""
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    head = data[:2048].lstrip(b"\xef\xbb\xbf \t\r\n").lower()
    if (head.startswith(b"<?xml") or head.startswith(b"<svg") or head.startswith(b"<!--")) and b"<svg" in data[
        :65536
    ].lower():
        return "image/svg+xml"
    return None


@router.put("/{floor_id}/plan-image", response_model=FloorOut)
async def upload_plan_image(
    floor_id: uuid.UUID,
    request: Request,
    session: SessionDep,
    width: float | None = None,
    height: float | None = None,
):
    """Stores a plan image (raw PNG, JPEG or SVG body) and lays it over the whole floor unless it has a
    placement already. width/height: the image's pixel size, so a new floor gets its proportions."""
    floor = await session.get(Floor, floor_id)
    if floor is None:
        raise not_found("floor")
    data = await request.body()
    if not data:
        raise HTTPException(422, detail={"code": "empty_plan", "message": "файл пустой"})
    if len(data) > MAX_PLAN_BYTES:
        raise HTTPException(413, detail={"code": "plan_too_big", "message": "план больше 15 МБ"})
    kind = plan_type(data)
    if kind is None:
        raise HTTPException(415, detail={"code": "plan_type", "message": "нужен PNG, JPEG или SVG"})
    image = await session.get(FloorPlanImage, floor_id)
    if image is None:
        image = FloorPlanImage(floor_id=floor_id)
        session.add(image)
    image.content_type = kind
    image.data = data
    digest = hashlib.sha256(data).hexdigest()[:12]
    # The version in the URL lets the app tell a new plan from the one it has drawn.
    floor.plan_image_url = f"/api/v1/floors/{floor_id}/plan-image?v={digest}"
    if floor.plan_w is None or floor.plan_h is None:
        plan_w = float(floor.width_px or 1600)
        ratio = (height / width) if width and height else ((floor.height_px or 1000) / plan_w)
        floor.plan_x, floor.plan_y, floor.plan_w, floor.plan_h = 0.0, 0.0, plan_w, round(plan_w * ratio, 1)
    floor.width_px = max(floor.width_px or 0, round((floor.plan_x or 0) + floor.plan_w))
    floor.height_px = max(floor.height_px or 0, round((floor.plan_y or 0) + floor.plan_h))
    await session.commit()
    await session.refresh(floor)
    return floor


@router.get("/{floor_id}/plan-image")
async def get_plan_image(floor_id: uuid.UUID, session: SessionDep):
    image = await session.get(FloorPlanImage, floor_id)
    if image is None:
        raise not_found("plan image")
    # nosniff and a sandbox CSP: an SVG opened in a browser cannot run anything.
    return Response(
        image.data,
        media_type=image.content_type,
        headers={"X-Content-Type-Options": "nosniff", "Content-Security-Policy": "sandbox; default-src 'none'"},
    )


@router.delete("/{floor_id}/plan-image", response_model=FloorOut)
async def delete_plan_image(floor_id: uuid.UUID, session: SessionDep):
    floor = await session.get(Floor, floor_id)
    if floor is None:
        raise not_found("floor")
    image = await session.get(FloorPlanImage, floor_id)
    if image is not None:
        await session.delete(image)
    floor.plan_image_url = ""
    floor.plan_x = floor.plan_y = floor.plan_w = floor.plan_h = None
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
