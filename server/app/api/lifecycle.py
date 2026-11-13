"""Licenses with their keys, other dated items, and the list of everything that is about to expire."""

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Query, Request, Response, status
from sqlalchemy import delete, select

from ..models import Deadline, Device, License, LicenseDevice, ScriptAuditLog
from ..schemas import (
    DeadlineCreate,
    DeadlineOut,
    DeadlineUpdate,
    ExpirationOut,
    LicenseCreate,
    LicenseOut,
    LicenseUpdate,
)
from ..services import lifecycle
from .deps import SessionDep, conflict, not_found, who

router = APIRouter(tags=["lifecycle"])

# Journal entries of this module (shown under «Безопасность»: a key was created, changed, looked at).
ACTIONS = {
    "license_create": "Лицензия: добавление",
    "license_update": "Лицензия: изменение",
    "license_delete": "Лицензия: удаление",
    "license_reveal": "Лицензия: просмотр ключа",
}


def _journal(request: Request, action: str, lic: License, detail: str = "") -> ScriptAuditLog:
    # The key itself is never written to the journal.
    return ScriptAuditLog(
        script_name=action,
        triggered_by=who(request),
        parameters={"license": lic.software, "id": str(lic.id)},
        executed_at=datetime.now(UTC),
        exit_code=0,
        output_log=f"{ACTIONS[action]}: {lic.software}{detail}",
    )


def _hint(key: str | None) -> str | None:
    if not key:
        return None
    tail = key.strip()[-4:]
    return f"••••{tail}" if len(key.strip()) > 4 else "••••"


async def _device_ids(session, lic_id: uuid.UUID) -> list[uuid.UUID]:
    return list(
        (await session.scalars(select(LicenseDevice.device_id).where(LicenseDevice.license_id == lic_id))).all()
    )


async def _out(session, lic: License, ids: list[uuid.UUID] | None = None) -> LicenseOut:
    ids = await _device_ids(session, lic.id) if ids is None else ids
    return LicenseOut(
        id=lic.id,
        software=lic.software,
        vendor=lic.vendor,
        kind=lic.kind,
        seats=lic.seats,
        has_key=bool(lic.key),
        key_hint=_hint(lic.key),
        valid_until=lic.valid_until,
        purchased_on=lic.purchased_on,
        supplier=lic.supplier,
        contract=lic.contract,
        notes=lic.notes,
        device_ids=ids,
        used=len(ids),
        over=lic.seats is not None and len(ids) > lic.seats,
    )


async def _set_devices(session, lic: License, ids: list[uuid.UUID]) -> list[uuid.UUID]:
    ids = list(dict.fromkeys(ids))
    if ids:
        found = set((await session.scalars(select(Device.id).where(Device.id.in_(ids)))).all())
        if missing := [i for i in ids if i not in found]:
            raise not_found(f"device {missing[0]}")
    await session.execute(delete(LicenseDevice).where(LicenseDevice.license_id == lic.id))
    session.add_all([LicenseDevice(license_id=lic.id, device_id=i) for i in ids])
    return ids


async def _get(session, lic_id: uuid.UUID) -> License:
    lic = await session.get(License, lic_id)
    if lic is None:
        raise not_found("license")
    return lic


@router.get("/licenses", response_model=list[LicenseOut])
async def list_licenses(session: SessionDep):
    lics = (await session.scalars(select(License).order_by(License.software))).all()
    pairs = (await session.execute(select(LicenseDevice.license_id, LicenseDevice.device_id))).all()
    by: dict[uuid.UUID, list[uuid.UUID]] = {}
    for lid, did in pairs:
        by.setdefault(lid, []).append(did)
    return [await _out(session, lic, by.get(lic.id, [])) for lic in lics]


@router.post("/licenses", response_model=LicenseOut, status_code=status.HTTP_201_CREATED)
async def create_license(body: LicenseCreate, request: Request, session: SessionDep):
    lic = License(**body.model_dump(exclude={"device_ids"}))
    lic.key = (lic.key or "").strip() or None
    session.add(lic)
    await session.flush()
    ids = await _set_devices(session, lic, body.device_ids)
    session.add(_journal(request, "license_create", lic))
    await session.commit()
    await session.refresh(lic)
    return await _out(session, lic, ids)


@router.get("/licenses/{lic_id}", response_model=LicenseOut)
async def get_license(lic_id: uuid.UUID, session: SessionDep):
    return await _out(session, await _get(session, lic_id))


@router.patch("/licenses/{lic_id}", response_model=LicenseOut)
async def update_license(lic_id: uuid.UUID, body: LicenseUpdate, request: Request, session: SessionDep):
    lic = await _get(session, lic_id)
    data = body.model_dump(exclude_unset=True)
    ids = data.pop("device_ids", None)
    key_changed = "key" in data
    for k, v in data.items():
        setattr(lic, k, ((v or "").strip() or None) if k == "key" else v)
    new_ids = await _set_devices(session, lic, ids) if ids is not None else None
    session.add(_journal(request, "license_update", lic, " (ключ изменён)" if key_changed else ""))
    await session.commit()
    await session.refresh(lic)
    return await _out(session, lic, new_ids)


@router.delete("/licenses/{lic_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_license(lic_id: uuid.UUID, request: Request, session: SessionDep):
    lic = await _get(session, lic_id)
    session.add(_journal(request, "license_delete", lic))
    await session.delete(lic)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/licenses/{lic_id}/reveal")
async def reveal_license_key(lic_id: uuid.UUID, request: Request, session: SessionDep):
    """The key in the clear. A POST, and written to the journal: who looked at which key, and when."""
    lic = await _get(session, lic_id)
    if not lic.key:
        raise conflict("no_key", "У этой лицензии ключ не задан")
    session.add(_journal(request, "license_reveal", lic))
    await session.commit()
    return {"key": lic.key}


# --- other dated items -----------------------------------------------------------------------------------------


@router.get("/deadlines", response_model=list[DeadlineOut])
async def list_deadlines(session: SessionDep, include_done: bool = False):
    stmt = select(Deadline).order_by(Deadline.due_date)
    if not include_done:
        stmt = stmt.where(Deadline.is_done.is_(False))
    return (await session.scalars(stmt)).all()


@router.post("/deadlines", response_model=DeadlineOut, status_code=status.HTTP_201_CREATED)
async def create_deadline(body: DeadlineCreate, session: SessionDep):
    if body.device_id and await session.get(Device, body.device_id) is None:
        raise not_found("device")
    dl = Deadline(**body.model_dump())
    session.add(dl)
    await session.commit()
    await session.refresh(dl)
    return dl


@router.patch("/deadlines/{dl_id}", response_model=DeadlineOut)
async def update_deadline(dl_id: uuid.UUID, body: DeadlineUpdate, session: SessionDep):
    dl = await session.get(Deadline, dl_id)
    if dl is None:
        raise not_found("deadline")
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(dl, k, v)
    await session.commit()
    await session.refresh(dl)
    return dl


@router.delete("/deadlines/{dl_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_deadline(dl_id: uuid.UUID, session: SessionDep):
    dl = await session.get(Deadline, dl_id)
    if dl is None:
        raise not_found("deadline")
    await session.delete(dl)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


# --- what is about to run out ----------------------------------------------------------------------------------


@router.get("/lifecycle/expirations", response_model=list[ExpirationOut])
async def expirations(
    request: Request,
    session: SessionDep,
    within_days: int = Query(default=90, ge=0, le=3650),
    include_expired: bool = True,
):
    """Warranties, licenses, other deadlines and the controller certificate, soonest first. Overdue ones come first."""
    return await lifecycle.collect(session, request.app.state.settings, within_days, include_expired=include_expired)


@router.get("/lifecycle/summary")
async def lifecycle_summary(request: Request, session: SessionDep):
    """Counts for the start screen."""
    items = await lifecycle.collect(session, request.app.state.settings, lifecycle.SOON)
    over = sum(1 for x in await list_licenses(session) if x.over)
    return {
        "expired": sum(1 for i in items if i["level"] == "expired"),
        "soon": sum(1 for i in items if i["level"] != "expired"),
        "over_licensed": over,
    }
