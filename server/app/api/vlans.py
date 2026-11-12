from datetime import UTC, datetime

from fastapi import APIRouter, Request, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from ..models import Port, ScriptAuditLog, Vlan
from ..schemas import VlanCreate, VlanOut, VlanUpdate
from .deps import SessionDep, conflict, not_found, who

router = APIRouter(prefix="/vlans", tags=["vlans"])

# The VLAN list is the server's own dictionary (names, subnets, colours); changes are audited
# so the history shows who renamed or removed a VLAN. Nothing is sent to switches.
ACTIONS = {
    "vlan_create": "Добавление VLAN",
    "vlan_update": "Изменение VLAN",
    "vlan_delete": "Удаление VLAN",
    "vlan_cleanup": "Удаление неиспользуемых VLAN",
}


def _audit(request: Request, action: str, vlan: Vlan, changed: dict | None = None) -> ScriptAuditLog:
    params = {"vlan_id": vlan.vlan_id, "name": vlan.name, "subnet": str(vlan.subnet_cidr) if vlan.subnet_cidr else None}
    if changed:
        params["changed"] = changed
    return ScriptAuditLog(
        script_name=action,
        triggered_by=who(request),
        parameters=params,
        executed_at=datetime.now(UTC),
        exit_code=0,
        output_log=f"{ACTIONS[action]} {vlan.vlan_id} «{vlan.name}»",
    )


@router.get("", response_model=list[VlanOut])
async def list_vlans(session: SessionDep):
    return (await session.scalars(select(Vlan).order_by(Vlan.vlan_id))).all()


async def unused_vlans(session) -> list[Vlan]:
    """VLANs nothing refers to: no port has them (access or tagged), no subnet and no description is set. VLAN 1 is the
    switches' default and stays."""
    used = set((await session.scalars(select(Port.access_vlan).where(Port.access_vlan.is_not(None)))).all())
    tagged = await session.scalars(select(func.unnest(Port.tagged_vlans)).distinct())
    used |= set(tagged.all())
    rows = (await session.scalars(select(Vlan).order_by(Vlan.vlan_id))).all()
    return [
        v
        for v in rows
        if v.vlan_id != 1 and v.vlan_id not in used and not v.subnet_cidr and not (v.description or "").strip()
    ]


@router.post("/cleanup")
async def cleanup_vlans(request: Request, session: SessionDep, dry_run: bool = True):
    """VLANs that no port uses and that have no subnet: usually what a switch config listed but never used.
    By default only says which (dry_run); `?dry_run=false` removes them and writes one journal entry."""
    victims = await unused_vlans(session)
    listed = [{"vlan_id": v.vlan_id, "name": v.name} for v in victims]
    if dry_run or not victims:
        return {"dry_run": dry_run, "count": len(listed), "vlans": listed}
    for v in victims:
        await session.delete(v)
    try:
        await session.flush()
    except IntegrityError:
        raise conflict("vlan_in_use", "Какой-то VLAN появился на порту во время очистки: повторите") from None
    ids = ", ".join(str(x["vlan_id"]) for x in listed)
    session.add(
        ScriptAuditLog(
            script_name="vlan_cleanup",
            triggered_by=who(request),
            parameters={"vlans": listed},
            executed_at=datetime.now(UTC),
            exit_code=0,
            output_log=f"{ACTIONS['vlan_cleanup']}: {len(listed)} ({ids[:500]})",
        )
    )
    await session.commit()
    return {"dry_run": False, "count": len(listed), "vlans": listed}


@router.post("", response_model=VlanOut, status_code=status.HTTP_201_CREATED)
async def create_vlan(body: VlanCreate, request: Request, session: SessionDep):
    if await session.get(Vlan, body.vlan_id):
        raise conflict("vlan_exists", f"VLAN {body.vlan_id} already exists")
    vlan = Vlan(**body.model_dump())
    vlan.subnet_cidr = str(body.subnet_cidr) if body.subnet_cidr is not None else None
    session.add(vlan)
    session.add(_audit(request, "vlan_create", vlan))
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
async def update_vlan(vlan_id: int, body: VlanUpdate, request: Request, session: SessionDep):
    vlan = await session.get(Vlan, vlan_id)
    if vlan is None:
        raise not_found("vlan")
    changed = {}
    for k, v in body.model_dump(exclude_unset=True).items():
        new = str(v) if k == "subnet_cidr" and v is not None else v
        old = getattr(vlan, k)
        if (str(old) if k == "subnet_cidr" and old is not None else old) != new:
            changed[k] = [str(old) if k == "subnet_cidr" and old is not None else old, new]
        setattr(vlan, k, new)
    if changed:
        session.add(_audit(request, "vlan_update", vlan, changed))
    await session.commit()
    await session.refresh(vlan)
    return vlan


@router.delete("/{vlan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_vlan(vlan_id: int, request: Request, session: SessionDep):
    vlan = await session.get(Vlan, vlan_id)
    if vlan is None:
        raise not_found("vlan")
    entry = _audit(request, "vlan_delete", vlan)
    await session.delete(vlan)
    try:
        await session.flush()
    except IntegrityError:
        raise conflict("vlan_in_use", f"VLAN {vlan_id} is assigned to ports") from None
    session.add(entry)
    await session.commit()
