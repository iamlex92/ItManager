import ipaddress
import uuid
from dataclasses import asdict

from fastapi import APIRouter, Request, Response, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from ..ipam import dns as dns_check
from ..ipam.analyze import Host, Reservation, analyze, check_address
from ..ipam.analyze import IpRange as RangeTuple
from ..models import Device, IpRange, IpReservation, Vlan
from ..schemas import (
    DnsCheckIn,
    IpRangeCreate,
    IpRangeOut,
    IpRangeUpdate,
    IpReservationCreate,
    IpReservationOut,
    IpReservationUpdate,
)
from .deps import SessionDep, conflict, not_found

router = APIRouter(prefix="/ipam", tags=["ipam"])


async def _load(session):
    vlans = [
        (v.vlan_id, v.name, str(v.subnet_cidr) if v.subnet_cidr else None)
        for v in (await session.scalars(select(Vlan).order_by(Vlan.vlan_id))).all()
    ]
    devices = (await session.scalars(select(Device).order_by(Device.name))).all()
    # Every address of a device is in use, its extra ones too.
    hosts = [
        Host(str(d.id), d.name, str(ip))
        for d in devices
        for ip in [d.ip_address, *(d.extra_ips or [])]
        if ip is not None
    ]
    reservations = [
        Reservation(str(r.ip), r.name, str(r.device_id) if r.device_id else None)
        for r in (await session.scalars(select(IpReservation))).all()
    ]
    ranges = [
        RangeTuple(r.name, r.kind, str(r.start_ip), str(r.end_ip))
        for r in (await session.scalars(select(IpRange))).all()
    ]
    return vlans, hosts, reservations, ranges


@router.get("")
async def ipam_overview(session: SessionDep):
    """Per VLAN subnet: used, reserved and free addresses, the next free ones (not in a DHCP pool, not held for anyone),
    and address conflicts. Read-only."""
    vlans, hosts, reservations, ranges = await _load(session)
    usage, conflicts = analyze(vlans, hosts, reservations, ranges)
    return {"subnets": [asdict(u) for u in usage], "conflicts": [asdict(c) for c in conflicts]}


@router.get("/check")
async def ipam_check(ip: str, session: SessionDep):
    """Is this address free and inside a known subnet? Checks only; assigns nothing."""
    vlans, hosts, reservations, ranges = await _load(session)
    return check_address(ip, vlans, hosts, reservations, ranges)


# --- reservations -----------------------------------------------------------------------------------------------


async def _names(session, ids) -> dict:
    ids = {i for i in ids if i}
    if not ids:
        return {}
    return dict((await session.execute(select(Device.id, Device.name).where(Device.id.in_(ids)))).all())


def _res_out(r: IpReservation, names: dict) -> IpReservationOut:
    return IpReservationOut(
        id=r.id,
        ip=str(r.ip),
        name=r.name,
        mac=str(r.mac) if r.mac else None,
        device_id=r.device_id,
        device_name=names.get(r.device_id) if r.device_id else None,
        note=r.note,
    )


@router.get("/reservations", response_model=list[IpReservationOut])
async def list_reservations(session: SessionDep):
    rows = (await session.scalars(select(IpReservation))).all()
    rows = sorted(rows, key=lambda r: ipaddress.ip_address(str(r.ip)))
    names = await _names(session, (r.device_id for r in rows))
    return [_res_out(r, names) for r in rows]


@router.post("/reservations", response_model=IpReservationOut, status_code=status.HTTP_201_CREATED)
async def create_reservation(body: IpReservationCreate, session: SessionDep):
    if body.device_id and await session.get(Device, body.device_id) is None:
        raise not_found("device")
    r = IpReservation(ip=str(body.ip), name=body.name, mac=body.mac, device_id=body.device_id, note=body.note)
    session.add(r)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise conflict("ip_reserved", f"Адрес {body.ip} уже зарезервирован") from None
    await session.refresh(r)
    return _res_out(r, await _names(session, [r.device_id]))


@router.patch("/reservations/{rid}", response_model=IpReservationOut)
async def update_reservation(rid: uuid.UUID, body: IpReservationUpdate, session: SessionDep):
    r = await session.get(IpReservation, rid)
    if r is None:
        raise not_found("reservation")
    data = body.model_dump(exclude_unset=True)
    if data.get("device_id") and await session.get(Device, data["device_id"]) is None:
        raise not_found("device")
    for k, v in data.items():
        setattr(r, k, str(v) if k == "ip" and v is not None else v)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise conflict("ip_reserved", "Этот адрес уже зарезервирован") from None
    await session.refresh(r)
    return _res_out(r, await _names(session, [r.device_id]))


@router.delete("/reservations/{rid}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_reservation(rid: uuid.UUID, session: SessionDep):
    r = await session.get(IpReservation, rid)
    if r is None:
        raise not_found("reservation")
    await session.delete(r)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


# --- ranges ---------------------------------------------------------------------------------------------------------


def _range_out(r: IpRange) -> IpRangeOut:
    a, b = ipaddress.ip_address(str(r.start_ip)), ipaddress.ip_address(str(r.end_ip))
    return IpRangeOut(
        id=r.id, name=r.name, kind=r.kind, start_ip=str(a), end_ip=str(b), size=max(int(b) - int(a) + 1, 0), note=r.note
    )


def _check_range(start, end) -> None:
    a, b = ipaddress.ip_address(str(start)), ipaddress.ip_address(str(end))
    if a.version != b.version:
        raise conflict("bad_range", "Начало и конец диапазона должны быть одного вида (IPv4 или IPv6)")
    if b < a:
        raise conflict("bad_range", "Конец диапазона раньше его начала")


@router.get("/ranges", response_model=list[IpRangeOut])
async def list_ranges(session: SessionDep):
    rows = (await session.scalars(select(IpRange))).all()
    return [_range_out(r) for r in sorted(rows, key=lambda r: ipaddress.ip_address(str(r.start_ip)))]


@router.post("/ranges", response_model=IpRangeOut, status_code=status.HTTP_201_CREATED)
async def create_range(body: IpRangeCreate, session: SessionDep):
    _check_range(body.start_ip, body.end_ip)
    r = IpRange(name=body.name, kind=body.kind, start_ip=str(body.start_ip), end_ip=str(body.end_ip), note=body.note)
    session.add(r)
    await session.commit()
    await session.refresh(r)
    return _range_out(r)


@router.patch("/ranges/{rid}", response_model=IpRangeOut)
async def update_range(rid: uuid.UUID, body: IpRangeUpdate, session: SessionDep):
    r = await session.get(IpRange, rid)
    if r is None:
        raise not_found("range")
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(r, k, str(v) if k in ("start_ip", "end_ip") and v is not None else v)
    _check_range(r.start_ip, r.end_ip)
    await session.commit()
    await session.refresh(r)
    return _range_out(r)


@router.delete("/ranges/{rid}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_range(rid: uuid.UUID, session: SessionDep):
    r = await session.get(IpRange, rid)
    if r is None:
        raise not_found("range")
    await session.delete(r)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


# --- DNS against the inventory ----------------------------------------------------------------------------------------


@router.post("/dns-check")
async def dns_check_run(body: DnsCheckIn, request: Request, session: SessionDep):
    """Looks each device's name up in DNS (and its address back) and compares with the inventory. Lookups come from this
    server's resolver, so the answer is what the plant's DNS says to the server. Read-only."""
    stmt = select(Device)
    if body.device_ids:
        stmt = stmt.where(Device.id.in_(body.device_ids))
    devices = (await session.scalars(stmt)).all()
    items = [
        (str(d.id), d.name, d.device_type, str(d.ip_address), [str(x) for x in d.extra_ips or []])
        for d in devices
        if d.ip_address is not None
    ]
    resolve = getattr(request.app.state, "dns_resolve", None) or dns_check.system_resolve
    reverse = getattr(request.app.state, "dns_reverse", None) or dns_check.system_reverse
    rows = await dns_check.check(items, resolve, reverse, body.suffix)
    summary = {
        "checked": len(rows),
        "ok": sum(1 for r in rows if r.forward == "ok" and r.reverse == "ok"),
        "forward_missing": sum(1 for r in rows if r.forward == "missing"),
        "forward_mismatch": sum(1 for r in rows if r.forward == "mismatch"),
        "reverse_missing": sum(1 for r in rows if r.reverse == "missing"),
        "reverse_mismatch": sum(1 for r in rows if r.reverse == "mismatch"),
    }
    return {"summary": summary, "rows": [asdict(r) for r in rows]}
