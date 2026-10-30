"""Domain computers: everything Connect-DomainPC.ps1 offered, from the phone and the web console.

GET  /pcs/status                 mode, whether changes are allowed and the login is set
POST /pcs/sweep                  ping a subnet and name what answers (menu item 1)
POST /pcs/{host}/{action}        info, disks, folders, processes, cleanup, gpupdate, reboot, kill, command,
                                 assist (remote help); Astra Linux also update, admin_add, admin_remove

Windows computers are reached over WinRM, Astra Linux ones over SSH with sudo (pcs/astra.py): whichever port
answers decides, so the same page serves both.
POST /pcs/keys                   key inventory over AD or a subnet (menu item 3); GET /pcs/keys/{id}, .../csv

Every action on a computer goes to the audit log («pc:<action>»), with who asked and whether it was emulated.
"""

import asyncio
import time
import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, HTTPException, Request, Response

from ..config import DiscoveryMode
from ..models import ScriptAuditLog
from ..pcs.astra import ASTRA_ACTIONS, program
from ..pcs.backend import (
    CONFIRM_ACTIONS,
    READ_ACTIONS,
    Action,
    EmulatedPcs,
    PcError,
    PcResult,
    RealPcs,
    script_for,
    valid_host,
)
from ..pcs.service import (
    KeyJob,
    allowed_host,
    detect_platform,
    inventory_pcs,
    office_text,
    one_c_text,
    run_key_job,
    sweep,
    to_csv,
)
from ..runbooks.real import LdapDirectory, RunbookConfigError
from ..schemas import (
    PcActionIn,
    PcActionOut,
    PcKeyJobOut,
    PcKeyRowOut,
    PcKeysIn,
    PcStatusOut,
    PcSweepHitOut,
    PcSweepIn,
)
from ..services import heartbeat
from .deps import SessionDep, not_found
from .runbooks import linux_login, runbook_login

router = APIRouter(prefix="/pcs", tags=["domain computers"])

AUDIT_PREFIX = "pc:"


def _bad(code: str, message: str, status: int = 422) -> HTTPException:
    return HTTPException(status_code=status, detail={"code": code, "message": message})


def _backend(request: Request) -> RealPcs | EmulatedPcs:
    return request.app.state.pcs


def _pinger(request: Request):
    s = request.app.state.settings
    if s.discovery_mode is DiscoveryMode.REAL and heartbeat.ping_available():
        return heartbeat.icmp_ping
    return None


@router.get("/status", response_model=PcStatusOut)
async def status(request: Request, session: SessionDep):
    s = request.app.state.settings
    backend = _backend(request)
    return PcStatusOut(
        mode="real" if isinstance(backend, RealPcs) else "emulator",
        writes_enabled=backend.writes_enabled,
        login_configured=await runbook_login(session, s) is not None,
        ad_configured=bool(s.ad_ldap_url.strip()),
    )


@router.post("/sweep", response_model=list[PcSweepHitOut])
async def sweep_subnet(body: PcSweepIn, request: Request, session: SessionDep):
    """Ping and reverse DNS only: no login is sent anywhere."""
    try:
        hits = await sweep(session, body.prefix, _pinger(request), isinstance(_backend(request), EmulatedPcs))
    except PcError as e:
        raise _bad("invalid_subnet", str(e)) from e
    return [PcSweepHitOut(ip=h.ip, name=h.name, device_id=h.device_id, device_name=h.device_name) for h in hits]


@router.post("/keys", response_model=PcKeyJobOut, status_code=202)
async def start_keys(body: PcKeysIn, request: Request, session: SessionDep):
    s = request.app.state.settings
    backend = _backend(request)
    login = await runbook_login(session, s)
    real = isinstance(backend, RealPcs)
    if real and login is None:
        raise _bad("no_login", "Не задана учётная запись администратора домена (RUNBOOK_CREDENTIAL).", 409)
    pinger = _pinger(request)
    if body.source == "ad":
        if real:
            if not s.ad_ldap_url.strip():
                raise _bad("no_ad", "Чтобы взять компьютеры из AD, задайте AD_LDAP_URL на сервере.", 409)
            try:
                hosts = await asyncio.to_thread(lambda: LdapDirectory(s, login).windows_computers())
            except RunbookConfigError as e:
                raise _bad("ad_failed", str(e), 502) from e
        else:
            # No AD here: the inventory's computers stand in for it.
            pcs = await inventory_pcs(session)
            hosts = [f"{d.name.lower()}.factory.local" for d in pcs if d.device_type != "VIRTUAL"]
    else:
        if not body.prefix:
            raise _bad("invalid_subnet", "Укажите подсеть, например 192.168.82")
        try:
            found = await sweep(session, body.prefix, pinger, not real)
        except PcError as e:
            raise _bad("invalid_subnet", str(e)) from e
        # As the script: an address DNS does not know is not a domain computer (a printer, a phone).
        hosts = [h.name for h in found if h.name]

    job = KeyJob(id=uuid.uuid4(), source=body.source, started_at=datetime.now(UTC), simulated=not real)
    sm = request.app.state.sessionmaker

    async def allowed(host: str) -> bool:
        if not real:
            return True
        async with sm() as s2:
            return await allowed_host(s2, s, login, host)

    async def work() -> None:
        # Hosts from AD are pinged first; the subnet ones answered a moment ago.
        await run_key_job(job, hosts, backend, login, pinger if body.source == "ad" else None, allowed)
        async with sm() as s3:
            s3.add(
                ScriptAuditLog(
                    script_name=f"{AUDIT_PREFIX}keys",
                    triggered_by=(body.operator or "api")[:100],
                    parameters={"source": body.source, "prefix": body.prefix, "simulated": not real},
                    executed_at=job.started_at,
                    exit_code=0 if job.status == "done" else 1,
                    output_log=f"компьютеров: {job.total}, включено: {job.online}, "
                    f"собрано: {sum(r.ok for r in job.rows)}",
                    stderr_log=job.error or None,
                    duration_ms=int(((job.finished_at or job.started_at) - job.started_at).total_seconds() * 1000),
                )
            )
            await s3.commit()

    request.app.state.pc_keys.start(job, work())
    return job_out(job)


def job_out(job: KeyJob) -> PcKeyJobOut:
    return PcKeyJobOut(
        id=job.id, source=job.source, status=job.status, stage=job.stage, total=job.total, online=job.online,
        done=job.done, error=job.error, simulated=job.simulated, started_at=job.started_at,
        finished_at=job.finished_at,
        rows=[
            PcKeyRowOut(
                host=r.host, ok=r.ok, error=r.error, computer=r.data.get("computer"), user=r.data.get("user"),
                os=r.data.get("os"), windows_bios_key=r.data.get("windows_bios_key"),
                windows_registry_key=r.data.get("windows_registry_key"),
                office=office_text(r.data) if r.ok else "", one_c=one_c_text(r.data) if r.ok else "",
            )
            for r in job.rows
        ],
    )  # fmt: skip


@router.get("/keys/{job_id}", response_model=PcKeyJobOut)
async def get_keys(job_id: uuid.UUID, request: Request):
    job = request.app.state.pc_keys.get(job_id)
    if job is None:
        raise not_found("key inventory")
    return job_out(job)


@router.get("/keys/{job_id}/csv")
async def keys_csv(job_id: uuid.UUID, request: Request):
    job = request.app.state.pc_keys.get(job_id)
    if job is None:
        raise not_found("key inventory")
    name = f"Keys_Inventory_{job.started_at.astimezone().strftime('%Y-%m-%d_%H-%M')}.csv"
    return Response(
        to_csv(job),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{name}"'},
    )


@router.post("/{host}/{action}", response_model=PcActionOut)
async def act(host: str, action: Action, body: PcActionIn, request: Request, session: SessionDep):
    s = request.app.state.settings
    windows = _backend(request)
    real = isinstance(windows, RealPcs)
    try:
        host = valid_host(host)
    except PcError as e:
        raise _bad("invalid_request", str(e)) from e
    login = await runbook_login(session, s)
    # Before anything is sent to it, the probe for WinRM and SSH included.
    if real and not await allowed_host(session, s, login, host):
        raise _bad(
            "unknown_host",
            f"{host} нет ни в учёте, ни в AD: учётная запись администратора домена отправляется только известным "
            "компьютерам. Добавьте компьютер в учёт или задайте AD_LDAP_URL.",
            403,
        )
    try:
        platform = await detect_platform(host, s, not real, request.app.state.pc_platforms, request.app.state.pc_probe)
    except PcError as e:
        raise _bad("pc_failed", str(e), 409) from e
    backend = request.app.state.astra if platform == "astra" else windows
    try:
        # A bad process name, an empty command, a Linux-only action on Windows: refused before anything runs.
        if platform == "astra":
            if action not in ASTRA_ACTIONS:
                raise PcError("Для Astra Linux этого действия нет")
            program(action, body.arg)
        elif action is not Action.ASSIST:
            script_for(action, body.arg)
    except PcError as e:
        raise _bad("invalid_request", str(e)) from e
    # Offering help from Windows changes nothing on the server's side: msra runs on the helper's computer.
    read_only = action in READ_ACTIONS or (platform == "windows" and action is Action.ASSIST)
    if not read_only and not backend.writes_enabled:
        raise _bad(
            "writes_disabled",
            "Изменения на компьютерах выключены на сервере (ALLOW_RUNBOOK_WRITES=false): доступен только просмотр.",
            403,
        )
    if action in CONFIRM_ACTIONS and not body.confirm:
        raise _bad("confirmation_required", 'подтвердите действие и повторите с "confirm": true', 400)

    client = request.client.host if request.client else "unknown"
    started = datetime.now(UTC)
    t0 = time.monotonic()
    try:
        if platform == "windows" and action is Action.ASSIST:
            # msra runs on the helper's computer; the server only says how.
            result = PcResult(True, data=msra_offer(host), simulated=not real)
        elif platform == "astra":
            result = await backend.run(host, action, body.arg, await linux_login(session, s) if real else login)
        else:
            result = await backend.run(host, action, body.arg, login)
    except PcError as e:
        raise _bad("pc_failed", str(e), 409) from e
    duration_ms = int((time.monotonic() - t0) * 1000)

    # Reads are logged without what they returned (the keys stay out of the log); changes keep their output.
    output = None
    if action is Action.ASSIST and isinstance(result.data, dict):
        # Never the one-time VNC password.
        d = result.data
        output = f"{d.get('method')}: {d.get('command') or d.get('url')} · пользователь {d.get('user') or '—'}"
    elif action not in READ_ACTIONS and isinstance(result.data, dict):
        output = str(result.data.get("output") or result.data)[:20000]
    entry = ScriptAuditLog(
        script_name=f"{AUDIT_PREFIX}{action.value}",
        triggered_by=(f"{body.operator} · {client}" if body.operator else f"api:{client}")[:100],
        parameters={
            "host": host,
            "arg": body.arg if action in (Action.KILL, Action.COMMAND, Action.ADMIN_ADD, Action.ADMIN_REMOVE) else None,
            "platform": platform,
            "confirmed": body.confirm,
            "auth_method": body.auth_method,
            "simulated": result.simulated,
        },
        executed_at=started,
        exit_code=0 if result.ok else 1,
        output_log=output,
        stderr_log=result.error or None,
        duration_ms=duration_ms,
    )
    session.add(entry)
    await session.commit()
    return PcActionOut(
        host=host, action=action.value, ok=result.ok, data=result.data, error=result.error,
        simulated=result.simulated, audit_id=entry.id, platform=platform,
    )  # fmt: skip


def msra_offer(host: str) -> dict:
    """What the helper runs on their own Windows computer to offer help to [host]."""
    return {
        "method": "msra",
        "host": host,
        "command": f"msra.exe /offerra {host}",
        "needs": [
            "Предлагать удалённую помощь (с вами в списке помощников)",
            "Запрос удалённой помощи",
            "Брандмауэр: входящие подключения → Удалённый помощник",
            "Запросы UAC во время удалённой помощи — чтобы видеть окна UAC",
        ],
    }
