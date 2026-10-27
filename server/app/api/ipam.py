from dataclasses import asdict

from fastapi import APIRouter
from sqlalchemy import select

from ..ipam.analyze import Host, analyze, check_address
from ..models import Device, Vlan
from .deps import SessionDep

router = APIRouter(prefix="/ipam", tags=["ipam"])


async def _load(session) -> tuple[list[tuple[int, str, str | None]], list[Host]]:
    vlans = [
        (v.vlan_id, v.name, str(v.subnet_cidr) if v.subnet_cidr else None)
        for v in (await session.scalars(select(Vlan).order_by(Vlan.vlan_id))).all()
    ]
    hosts = [
        Host(str(d.id), d.name, str(d.ip_address))
        for d in (
            await session.scalars(select(Device).where(Device.ip_address.is_not(None)).order_by(Device.name))
        ).all()
    ]
    return vlans, hosts


@router.get("")
async def ipam_overview(session: SessionDep):
    """Per VLAN subnet: used and free addresses, the next free ones, and address conflicts. Read-only."""
    vlans, hosts = await _load(session)
    usage, conflicts = analyze(vlans, hosts)
    return {"subnets": [asdict(u) for u in usage], "conflicts": [asdict(c) for c in conflicts]}


@router.get("/check")
async def ipam_check(ip: str, session: SessionDep):
    """Is this address free and inside a known subnet? Checks only; assigns nothing."""
    vlans, hosts = await _load(session)
    return check_address(ip, vlans, hosts)
