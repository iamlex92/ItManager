from datetime import UTC, datetime

from fastapi import APIRouter, Request, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from ..models import ScriptAuditLog, Vlan
from ..schemas import VlanCreate, VlanOut, VlanUpdate
from .deps import SessionDep, conflict, not_found

router = APIRouter(prefix="/vlans", tags=["vlans"])

# The VLAN list is the server's own dictionary (names, subnets, colours); changes are audited
# so the history shows who renamed or removed a VLAN. Nothing is sent to switches.
ACTIONS = {
    "vlan_create": "Добавление VLAN",
    "vlan_update": "Изменение VLAN",
    "vlan_delete": "Удаление VLAN",
}


def _audit(request: Request, action: str, vlan: Vlan, changed: dict | None = None) -> ScriptAuditLog:
    client = request.client.host if request.client else "unknown"
    params = {"vlan_id": vlan.vlan_id, "name": vlan.name, "subnet": str(vlan.subnet_cidr) if vlan.subnet_cidr else None}
    if changed:
        params["changed"] = changed
    return ScriptAuditLog(
        script_name=action,
        triggered_by=f"api:{client}",
        parameters=params,
        executed_at=datetime.now(UTC),
        exit_code=0,
        output_log=f"{ACTIONS[action]} {vlan.vlan_id} «{vlan.name}»",
    )


@router.get("", response_model=list[VlanOut])
async def list_vlans(session: SessionDep):
    return (await session.scalars(select(Vlan).order_by(Vlan.vlan_id))).all()


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
