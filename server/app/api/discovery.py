import logging
import time
import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, HTTPException, Request, status

from ..discovery.importer import import_found
from ..discovery.inventory import Inventory, load_inventory
from ..discovery.manager import IMPORT_ACTION, ScanManager, ScanRunning
from ..discovery.model import End, InvalidScan, Scan, parse_subnets
from ..models import ScriptAuditLog
from ..schemas import (
    FoundDeviceOut,
    FoundLinkOut,
    ImportedDeviceOut,
    ImportIn,
    ImportOut,
    LinkEndOut,
    ScanIn,
    ScanOut,
)
from .deps import SessionDep, conflict, not_found

log = logging.getLogger(__name__)
router = APIRouter(prefix="/discovery", tags=["discovery"])


def _manager(request: Request) -> ScanManager:
    return request.app.state.discovery


def _who(request: Request, operator: str | None) -> str:
    client = request.client.host if request.client else "unknown"
    return f"{operator} · {client}" if operator else f"api:{client}"


def _end(end: End, inv: Inventory) -> LinkEndOut:
    known = inv.resolve(end.name, end.ip)
    name = end.name or (known.name if known else end.ip or "?")
    return LinkEndOut(name=name, port=end.port, ip=end.ip, inventory_id=known.id if known else None)


def scan_out(scan: Scan, inv: Inventory) -> ScanOut:
    devices = []
    for d in sorted(scan.result.devices, key=lambda d: tuple(int(x) for x in d.ip.split("."))):
        known = inv.by_ip(d.ip)
        devices.append(
            FoundDeviceOut(
                ip=d.ip,
                name=d.name,
                mac=d.mac,
                vendor=d.vendor,
                model=d.model,
                type=d.device_type,
                description=d.description,
                snmp=d.snmp,
                seen_by=d.seen_by,
                open_ports=d.open_ports,
                hostname=d.hostname,
                inventory_id=known.id if known else None,
                inventory_name=known.name if known else None,
            )
        )
    links = []
    for lk in scan.result.links:
        a, b = _end(lk.a, inv), _end(lk.b, inv)
        known = a.inventory_id is not None and b.inventory_id is not None and inv.has_link(a.name, b.name)
        links.append(FoundLinkOut(a=a, b=b, source=lk.source, known=known))
    running = scan.status == "running"
    return ScanOut(
        id=scan.id,
        status=scan.status,
        mode=scan.mode,
        subnets=scan.subnets,
        started_at=scan.started_at,
        finished_at=scan.finished_at,
        total=scan.total,
        probed=scan.probed,
        # Partial results are not shown while the scan runs.
        devices=[] if running else devices,
        links=[] if running else links,
        error=scan.error,
        warnings=[] if running else scan.result.warnings,
    )


@router.post("/scans", response_model=ScanOut, status_code=status.HTTP_202_ACCEPTED)
async def start_scan(body: ScanIn, request: Request, session: SessionDep):
    """Starts a scan of the given private subnets: ICMP echo and a TCP probe of common ports to every
    address, then ports, names, banners and SNMP GET/GETNEXT (system group, LLDP, CDP, ARP and MAC
    tables) to those that answered.
    Read-only; in emulator mode nothing leaves the server. Poll GET /discovery/scans/{id}."""
    try:
        nets = parse_subnets(body.subnets)
    except InvalidScan as e:
        raise HTTPException(status_code=422, detail={"code": "invalid_subnets", "message": str(e)}) from e
    try:
        scan = await _manager(request).start(nets, body.community, _who(request, body.operator))
    except ScanRunning as e:
        raise conflict("scan_running", f"уже идёт сканирование {', '.join(e.scan.subnets)}") from e
    return scan_out(scan, await load_inventory(session))


@router.get("/scans/latest", response_model=ScanOut)
async def latest_scan(request: Request, session: SessionDep):
    scan = _manager(request).latest()
    if scan is None:
        raise not_found("scan")
    return scan_out(scan, await load_inventory(session))


@router.get("/scans/{scan_id}", response_model=ScanOut)
async def get_scan(scan_id: uuid.UUID, request: Request, session: SessionDep):
    scan = _manager(request).get(scan_id)
    if scan is None:
        raise not_found("scan")
    return scan_out(scan, await load_inventory(session))


@router.post("/scans/{scan_id}/import", response_model=ImportOut)
async def import_scan(scan_id: uuid.UUID, body: ImportIn, request: Request, session: SessionDep):
    """Adds the chosen devices to the inventory on the first floor, next to a cabled
    neighbor where one is known, and records the links found between them as cables.
    Writes only to the database."""
    scan = _manager(request).get(scan_id)
    if scan is None:
        raise not_found("scan")
    if scan.status != "done":
        raise conflict("scan_not_done", "сканирование ещё не закончилось")
    started_at = datetime.now(UTC)
    t0 = time.monotonic()
    result = await import_found(session, scan, body.ips, body.links)
    created = [ImportedDeviceOut(id=d.id, name=d.name, ip=str(d.ip_address)) for d in result.created]
    session.add(
        ScriptAuditLog(
            script_name=IMPORT_ACTION,
            triggered_by=_who(request, body.operator)[:100],
            parameters={"scan_id": str(scan.id), "ips": body.ips, "links": body.links},
            executed_at=started_at,
            exit_code=0,
            output_log="\n".join([f"+ {d.name} {d.ip}" for d in created] + [f"+ связь {lk}" for lk in result.links])
            or "Новых устройств и связей нет",
            stderr_log="\n".join(result.skipped) or None,
            duration_ms=int((time.monotonic() - t0) * 1000),
        )
    )
    await session.commit()
    return ImportOut(created=created, links=result.links, skipped=result.skipped)
