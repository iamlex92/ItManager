"""DHCP and DNS servers of the plant, read only: leases, pools and fixed hosts of ISC dhcpd; the A records of the zones
(Samba AD over SSH, or a BIND zone transfer). Compared with the inventory, never changed on the servers.

GET  /netsrv/status       what is configured
GET  /netsrv/dhcp         pools and how full they are, fixed hosts, what disagrees with the inventory, unknown clients
POST /netsrv/dhcp/import  copies the pools and the fixed hosts into the address plan (IP-адреса), what is not there yet
GET  /netsrv/dns          inventory names without a record, records with another address, records of nothing
"""

import time
from datetime import UTC, datetime

from fastapi import APIRouter, HTTPException, Request
from sqlalchemy import select

from ..models import Device, IpRange, IpReservation, ScriptAuditLog
from ..netsrv import compare
from ..netsrv.dhcpd import parse_conf, parse_leases
from ..netsrv.source import NetSrvError, zones_of
from .deps import SessionDep, who
from .runbooks import _login

router = APIRouter(prefix="/netsrv", tags=["netsrv"])


def _bad(message: str, status: int = 409) -> HTTPException:
    return HTTPException(status_code=status, detail={"code": "netsrv_failed", "message": message})


async def _inventory(session) -> list[compare.Dev]:
    rows = (await session.scalars(select(Device).where(Device.is_active.is_(True)))).all()
    out = []
    for d in rows:
        ips = [str(ip) for ip in [d.ip_address, *(d.extra_ips or [])] if ip is not None]
        out.append(compare.Dev(str(d.id), d.name, d.device_type, tuple(ips)))
    return out


async def _login_for(session, settings):
    return await _login(session, settings.netsrv_credential or settings.linux_credential or settings.runbook_credential)


@router.get("/status")
async def status(request: Request):
    svc = request.app.state.netsrv
    s = request.app.state.settings
    try:
        zones = zones_of(s)
    except NetSrvError:
        zones = []
    return {
        "simulated": svc.simulated,
        "dhcp": bool(svc.dhcp_host),
        "dns": bool(svc.dns_host) and (bool(zones) or svc.simulated),
        "zones": zones or (["factory.local"] if svc.simulated else []),
        "dns_method": s.dns_method,
    }


@router.get("/dhcp")
async def dhcp(request: Request, session: SessionDep):
    svc = request.app.state.netsrv
    s = request.app.state.settings
    inv = await _inventory(session)
    login = await _login_for(session, s)
    try:
        conf, leases_text = await svc.dhcp_texts(login, inv)
    except NetSrvError as e:
        raise _bad(str(e)) from e
    scopes, hosts = parse_conf(conf)
    leases = parse_leases(leases_text)
    known_ranges = {(str(r.start_ip), str(r.end_ip)) for r in (await session.scalars(select(IpRange))).all()}
    known_res = {str(r.ip) for r in (await session.scalars(select(IpReservation))).all()}
    rep = compare.dhcp_report(scopes, hosts, leases, inv, known_ranges, known_res)
    return {
        "simulated": svc.simulated,
        "host": svc.dhcp_host,
        "scopes": rep.scopes,
        "findings": rep.findings,
        "leases": rep.leases,
        "reservations": rep.reservations,
        "unknown_leases": rep.unknown_leases[:300],
        "unknown_count": len(rep.unknown_leases),
        "new_ranges": rep.new_ranges,
        "new_reservations": rep.new_reservations,
        "hosts": [{"name": h.name, "ip": h.ip, "mac": h.mac} for h in hosts],
    }


@router.post("/dhcp/import")
async def dhcp_import(request: Request, session: SessionDep):
    """Pools become «dhcp» ranges and the fixed hosts become reservations of the address plan; what is there stays."""
    data = await dhcp(request, session)
    started, t0 = datetime.now(UTC), time.monotonic()
    for r in data["new_ranges"]:
        session.add(IpRange(name=f"DHCP {r['network']}", kind="dhcp", start_ip=r["start"], end_ip=r["end"]))
    for r in data["new_reservations"]:
        session.add(IpReservation(ip=r["ip"], name=r["name"], mac=r["mac"] or None, note="Из резервов DHCP"))
    session.add(
        ScriptAuditLog(
            script_name="netsrv:dhcp_import",
            triggered_by=who(request),
            parameters={"ranges": len(data["new_ranges"]), "reservations": len(data["new_reservations"])},
            executed_at=started,
            exit_code=0,
            output_log="ok",
            duration_ms=int((time.monotonic() - t0) * 1000),
        )
    )
    await session.commit()
    return {"ranges": len(data["new_ranges"]), "reservations": len(data["new_reservations"])}


@router.get("/dns")
async def dns(request: Request, session: SessionDep):
    svc = request.app.state.netsrv
    s = request.app.state.settings
    try:
        zones = zones_of(s)
    except NetSrvError as e:
        raise _bad(str(e), 422) from e
    if svc.simulated and not zones:
        zones = ["factory.local"]
    if not zones:
        raise _bad("Не заданы зоны DNS: «Настройки сервера» → «DHCP и DNS».")
    inv = await _inventory(session)
    login = await _login_for(session, s)
    records = []
    try:
        for z in zones:
            records += await svc.dns_records(z, login, inv)
    except NetSrvError as e:
        raise _bad(str(e)) from e
    return {"simulated": svc.simulated, "host": svc.dns_host, "zones": zones, **compare.dns_report(records, inv)}
