"""Group Policy («Групповые политики»): see the GPOs, where they apply, edit and apply them.

GET  /gpo                          every GPO with its state and links
GET  /gpo/ous                      the domain and its OUs, for linking
GET  /gpo/computer/{host}          what a computer really applied (gpresult)
GET  /gpo/apply/{job}              progress of «Применить»
GET  /gpo/templates[?gpo={id}]     ready-made settings («Шаблоны»), with a GPO: their state in it
GET  /gpo/{id}                     one GPO: links, registry settings, security filter
GET  /gpo/{id}/report              the GPMC HTML report
GET  /gpo/{id}/computers           the computers it reaches
POST /gpo                          create                               ┐
PUT  /gpo/{id}/status              enable or disable parts               │ changes: ALLOW_RUNBOOK_WRITES in
POST /gpo/{id}/links               link to an OU                         │ real mode, "confirm": true,
PATCH /gpo/{id}/links              enable or enforce a link              │ audited as gpo:<action>
POST /gpo/{id}/links/remove        unlink                                │
PUT  /gpo/{id}/registry            set a registry setting                │
POST /gpo/{id}/templates/{tid}     set a template, several values        │
POST /gpo/{id}/registry/remove     remove a registry setting             │
POST /gpo/{id}/delete              delete the GPO                        │
POST /gpo/{id}/apply               gpupdate /force on its computers      ┘
"""

import time
import uuid
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse

from ..gpo import scripts
from ..gpo.backend import EmulatedGpo, GpoError, RealGpo, check_id
from ..gpo.service import ApplyJob, run_apply
from ..gpo.templates import TEMPLATES, describe, plan, template
from ..models import ScriptAuditLog
from ..pcs.backend import PcError, RealPcs, as_list, valid_host
from ..pcs.service import allowed_host, inventory_pcs
from ..schemas import (
    GpoAppliedOut,
    GpoApplyIn,
    GpoApplyJobOut,
    GpoCreateIn,
    GpoDetailOut,
    GpoLinkIn,
    GpoLinkPatchIn,
    GpoListOut,
    GpoOuOut,
    GpoRegistryIn,
    GpoRegistryRemoveIn,
    GpoScopeOut,
    GpoStatusIn,
    GpoTemplateIn,
    GpoTemplateOut,
    GpoWriteIn,
)
from .deps import SessionDep
from .runbooks import runbook_login

router = APIRouter(prefix="/gpo", tags=["group policy"])

AUDIT_PREFIX = "gpo:"


def _bad(code: str, message: str, status: int = 422) -> HTTPException:
    return HTTPException(status_code=status, detail={"code": code, "message": message})


def _backend(request: Request) -> RealGpo | EmulatedGpo:
    return request.app.state.gpo


async def _inventory(session) -> list[tuple[str, str]]:
    return [(d.name, d.device_type) for d in await inventory_pcs(session)]


async def _read(coro):
    try:
        return await coro
    except GpoError as e:
        raise _bad("gpo_failed", str(e), 409) from e


def _gate(request: Request, body: GpoWriteIn) -> None:
    """Changes must be allowed on the server and confirmed; checked before anything is read from the domain."""
    if not _backend(request).writes_enabled:
        raise _bad(
            "writes_disabled",
            "Изменения выключены на сервере: «Настройки сервера» → «Разрешить изменения». Доступен только просмотр.",
            403,
        )
    if not body.confirm:
        raise _bad("confirmation_required", 'подтвердите изменение и повторите с "confirm": true', 400)


async def _write(request: Request, session, body: GpoWriteIn, action: str, params: dict[str, Any], work) -> Any:
    """The common gate for changes: allowed on the server, confirmed, audited whatever the outcome."""
    backend = _backend(request)
    _gate(request, body)
    login = await runbook_login(session, request.app.state.settings)
    client = request.client.host if request.client else "unknown"
    started, t0 = datetime.now(UTC), time.monotonic()
    error, result = None, None
    try:
        result = await work(login)
    except GpoError as e:
        error = str(e)
    session.add(
        ScriptAuditLog(
            script_name=f"{AUDIT_PREFIX}{action}",
            triggered_by=(f"{body.operator} · {client}" if body.operator else f"api:{client}")[:100],
            parameters={**params, "auth_method": body.auth_method, "simulated": backend.simulated},
            executed_at=started,
            exit_code=1 if error else 0,
            output_log=None if error else "ok",
            stderr_log=error,
            duration_ms=int((time.monotonic() - t0) * 1000),
        )
    )
    await session.commit()
    if error:
        raise _bad("gpo_failed", error, 409)
    return result


async def _detail(request: Request, session, gpo_id: str) -> GpoDetailOut:
    backend = _backend(request)
    login = await runbook_login(session, request.app.state.settings)
    d = await _read(backend.detail(gpo_id, login))
    if not d.get("gpo", {}).get("id"):
        raise _bad("not_found", "Такой групповой политики нет", 404)
    return GpoDetailOut(**d, simulated=backend.simulated)


@router.get("", response_model=GpoListOut)
async def list_gpos(request: Request, session: SessionDep):
    backend = _backend(request)
    login = await runbook_login(session, request.app.state.settings)
    gpos = await _read(backend.gpos(login))
    return GpoListOut(
        mode="emulator" if backend.simulated else "real",
        host=backend.host,
        writes_enabled=backend.writes_enabled,
        login_configured=backend.simulated or login is not None,
        can_create=True,
        gpos=gpos,
    )


@router.get("/ous", response_model=list[GpoOuOut])
async def list_ous(request: Request, session: SessionDep):
    login = await runbook_login(session, request.app.state.settings)
    return await _read(_backend(request).ous(login))


@router.get("/computer/{host}", response_model=GpoAppliedOut)
async def applied_on(host: str, request: Request, session: SessionDep):
    """What the computer applied at its last refresh: gpresult over WinRM, like «Сведения» of a domain computer."""
    try:
        host = valid_host(host)
    except PcError as e:
        raise _bad("invalid_request", str(e)) from e
    backend = _backend(request)
    if isinstance(backend, EmulatedGpo):
        kind = next(
            (d.device_type for d in await inventory_pcs(session) if d.name.lower() == host.split(".")[0].lower()),
            "WORKSTATION",
        )
        return GpoAppliedOut(host=host, **backend.applied(host, kind), simulated=True)
    s = request.app.state.settings
    login = await runbook_login(session, s)
    if not await allowed_host(session, s, login, host):
        raise _bad("unknown_host", f"{host} нет ни в учёте, ни в AD: учётная запись домена ему не отправляется.", 403)
    pcs = request.app.state.pcs if isinstance(request.app.state.pcs, RealPcs) else RealPcs(s)
    try:
        r = await pcs.run_script(host, scripts.GPRESULT, login)
    except PcError as e:
        raise _bad("pc_failed", str(e), 409) from e
    if not r.ok or not isinstance(r.data, dict):
        raise _bad("pc_failed", r.error or "gpresult не ответил", 409)
    return GpoAppliedOut(
        host=host, read_at=r.data.get("read_at"), site=r.data.get("site"), gpos=as_list(r.data.get("gpos"))
    )


@router.get("/apply/{job_id}", response_model=GpoApplyJobOut)
async def apply_job(job_id: uuid.UUID, request: Request):
    job = request.app.state.gpo_jobs.get(job_id)
    if job is None:
        raise _bad("not_found", "Задание не найдено (сервер перезапускался?)", 404)
    return GpoApplyJobOut(
        id=job.id,
        gpo_id=job.gpo_id,
        gpo_name=job.gpo_name,
        status=job.status,
        total=job.total,
        done=job.done,
        simulated=job.simulated,
        started_at=job.started_at,
        rows=[r.__dict__ for r in job.rows],
    )


@router.get("/templates", response_model=list[GpoTemplateOut])
async def list_templates(request: Request, session: SessionDep, gpo: str | None = None):
    rows = None
    if gpo:
        rows = [r.model_dump() for r in (await _detail(request, session, gpo)).registry]
    return [describe(t, rows) for t in TEMPLATES.values()]


@router.get("/{gpo_id}", response_model=GpoDetailOut)
async def get_gpo(gpo_id: str, request: Request, session: SessionDep):
    return await _detail(request, session, gpo_id)


@router.get("/{gpo_id}/report", response_class=HTMLResponse)
async def gpo_report(gpo_id: str, request: Request, session: SessionDep):
    login = await runbook_login(session, request.app.state.settings)
    page = await _read(_backend(request).report(gpo_id, login))
    # Shown in a sandboxed frame: no scripts from the report run in the console.
    return HTMLResponse(page, headers={"Content-Security-Policy": "script-src 'none'; object-src 'none'"})


@router.get("/{gpo_id}/computers", response_model=GpoScopeOut)
async def gpo_computers(gpo_id: str, request: Request, session: SessionDep):
    backend = _backend(request)
    login = await runbook_login(session, request.app.state.settings)
    scope = await _read(backend.scope(gpo_id, login, await _inventory(session)))
    return GpoScopeOut(**scope, simulated=backend.simulated)


async def _previous(request, session, gpo_id: str, key: str, name: str) -> dict | None:
    """What a registry setting was before the change, for the audit log (so it can be put back by hand)."""
    try:
        d = await _detail(request, session, gpo_id)
    except HTTPException:
        return None
    k = key.strip().lower().replace("hkey_local_machine", "hklm").replace("hkey_current_user", "hkcu")
    r = next((r for r in d.registry if r.key.lower() == k and r.name.lower() == name.strip().lower()), None)
    return {"type": r.type, "value": r.value} if r else None


async def _name(request, session, gpo_id: str) -> str:
    try:
        return (await _detail(request, session, gpo_id)).gpo.name
    except HTTPException:
        return gpo_id


@router.post("", response_model=GpoDetailOut, status_code=201)
async def create_gpo(body: GpoCreateIn, request: Request, session: SessionDep):
    backend = _backend(request)
    gid = await _write(
        request,
        session,
        body,
        "create",
        {"name": body.name},
        lambda login: backend.create(body.name, body.comment, login),
    )
    return await _detail(request, session, gid)


@router.put("/{gpo_id}/status", response_model=GpoDetailOut)
async def set_status(gpo_id: str, body: GpoStatusIn, request: Request, session: SessionDep):
    _gate(request, body)
    backend = _backend(request)
    name = await _name(request, session, gpo_id)
    await _write(
        request,
        session,
        body,
        "status",
        {"gpo": name, "gpo_id": gpo_id, "status": body.status},
        lambda login: backend.set_status(gpo_id, body.status, login),
    )
    return await _detail(request, session, gpo_id)


@router.post("/{gpo_id}/links", response_model=GpoDetailOut)
async def add_link(gpo_id: str, body: GpoLinkIn, request: Request, session: SessionDep):
    _gate(request, body)
    backend = _backend(request)
    name = await _name(request, session, gpo_id)
    await _write(
        request,
        session,
        body,
        "link_add",
        {"gpo": name, "gpo_id": gpo_id, "target": body.target, "enforced": body.enforced},
        lambda login: backend.link_add(gpo_id, body.target, body.enforced, login),
    )
    return await _detail(request, session, gpo_id)


@router.patch("/{gpo_id}/links", response_model=GpoDetailOut)
async def change_link(gpo_id: str, body: GpoLinkPatchIn, request: Request, session: SessionDep):
    if body.enabled is None and body.enforced is None:
        raise _bad("invalid_request", "Нечего менять: enabled или enforced")
    _gate(request, body)
    backend = _backend(request)
    name = await _name(request, session, gpo_id)
    params = {"gpo": name, "gpo_id": gpo_id, "target": body.target, "enabled": body.enabled, "enforced": body.enforced}
    await _write(
        request,
        session,
        body,
        "link_set",
        params,
        lambda login: backend.link_set(gpo_id, body.target, body.enabled, body.enforced, login),
    )
    return await _detail(request, session, gpo_id)


@router.post("/{gpo_id}/links/remove", response_model=GpoDetailOut)
async def remove_link(gpo_id: str, body: GpoLinkIn, request: Request, session: SessionDep):
    _gate(request, body)
    backend = _backend(request)
    name = await _name(request, session, gpo_id)
    await _write(
        request,
        session,
        body,
        "link_remove",
        {"gpo": name, "gpo_id": gpo_id, "target": body.target},
        lambda login: backend.link_remove(gpo_id, body.target, login),
    )
    return await _detail(request, session, gpo_id)


@router.put("/{gpo_id}/registry", response_model=GpoDetailOut)
async def set_registry(gpo_id: str, body: GpoRegistryIn, request: Request, session: SessionDep):
    _gate(request, body)
    backend = _backend(request)
    name = await _name(request, session, gpo_id)
    params = {"gpo": name, "gpo_id": gpo_id, "key": body.key, "name": body.name, "type": body.type, "value": body.value,
              "before": await _previous(request, session, gpo_id, body.key, body.name)}  # fmt: skip
    await _write(
        request,
        session,
        body,
        "registry_set",
        params,
        lambda login: backend.registry_set(gpo_id, body.key, body.name, body.type, body.value, login),
    )
    return await _detail(request, session, gpo_id)


@router.post("/{gpo_id}/registry/remove", response_model=GpoDetailOut)
async def remove_registry(gpo_id: str, body: GpoRegistryRemoveIn, request: Request, session: SessionDep):
    _gate(request, body)
    backend = _backend(request)
    name = await _name(request, session, gpo_id)
    await _write(
        request,
        session,
        body,
        "registry_remove",
        {"gpo": name, "gpo_id": gpo_id, "key": body.key, "name": body.name,
         "before": await _previous(request, session, gpo_id, body.key, body.name)},  # fmt: skip
        lambda login: backend.registry_remove(gpo_id, body.key, body.name, login),
    )
    return await _detail(request, session, gpo_id)


@router.post("/{gpo_id}/templates/{template_id}", response_model=GpoDetailOut)
async def set_template(gpo_id: str, template_id: str, body: GpoTemplateIn, request: Request, session: SessionDep):
    """All the values of a template in one change; the log keeps what they were, to put back by hand."""
    _gate(request, body)
    backend = _backend(request)
    detail = await _detail(request, session, gpo_id)
    rows = [r.model_dump() for r in detail.registry]
    try:
        t = template(template_id)
        ops = plan(t, body.state, body.values, rows)
    except GpoError as e:
        raise _bad("invalid_request", str(e)) from e
    if not ops:
        return detail
    touched = {(op[1].lower(), op[2].lower()) for op in ops}
    before = [
        {"key": r["key"], "name": r["name"], "type": r["type"], "value": r["value"]}
        for r in rows
        if (r["key"].lower(), r["name"].lower()) in touched
    ]
    params = {"gpo": detail.gpo.name, "gpo_id": gpo_id, "template": t.title, "state": body.state,
              "values": body.values, "changes": [list(op) for op in ops], "before": before}  # fmt: skip
    await _write(request, session, body, "template", params, lambda login: _apply_ops(backend, gpo_id, ops, login))
    return await _detail(request, session, gpo_id)


async def _apply_ops(backend, gpo_id: str, ops: list[tuple], login) -> None:
    """In one write where the backend can (straight over SYSVOL), else value by value."""
    if hasattr(backend, "registry_batch"):
        await backend.registry_batch(gpo_id, ops, login)
        return
    for op in ops:
        if op[0] == "set":
            await backend.registry_set(gpo_id, op[1], op[2], op[3], op[4], login)
        else:
            await backend.registry_remove(gpo_id, op[1], op[2], login)


@router.post("/{gpo_id}/delete", status_code=204)
async def delete_gpo(gpo_id: str, body: GpoWriteIn, request: Request, session: SessionDep):
    _gate(request, body)
    backend = _backend(request)
    name = await _name(request, session, gpo_id)
    await _write(
        request, session, body, "delete", {"gpo": name, "gpo_id": gpo_id}, lambda login: backend.delete(gpo_id, login)
    )


@router.post("/{gpo_id}/apply", response_model=GpoApplyJobOut, status_code=202)
async def apply_gpo(gpo_id: str, body: GpoApplyIn, request: Request, session: SessionDep):
    """gpupdate /force on the computers the GPO reaches (or the ones picked from them), a few at a time."""
    backend = _backend(request)
    login = await runbook_login(session, request.app.state.settings)
    detail = await _detail(request, session, check_gpo(gpo_id))
    scope = await _read(backend.scope(gpo_id, login, await _inventory(session)))
    reach = {c["name"].lower(): c.get("dns") or c["name"] for c in scope["computers"] if c.get("enabled") is not False}
    if body.computers:
        wanted = [c.split(".")[0].lower() for c in body.computers]
        if missing := [c for c in wanted if c not in reach]:
            raise _bad("invalid_request", "Не в области действия политики: " + ", ".join(missing))
        hosts = [reach[c] for c in wanted]
    else:
        hosts = list(reach.values())
    if not hosts:
        raise _bad("invalid_request", "Политика не действует ни на один компьютер")

    async def start(login):
        job = ApplyJob(gpo_id=detail.gpo.id, gpo_name=detail.gpo.name, total=len(hosts), simulated=backend.simulated)
        request.app.state.gpo_jobs.start(job, run_apply(job, hosts, request.app.state.pcs, login))
        return job

    job = await _write(
        request, session, body, "apply", {"gpo": detail.gpo.name, "gpo_id": gpo_id, "computers": len(hosts)}, start
    )
    return GpoApplyJobOut(
        id=job.id,
        gpo_id=job.gpo_id,
        gpo_name=job.gpo_name,
        status=job.status,
        total=job.total,
        done=job.done,
        simulated=job.simulated,
        started_at=job.started_at,
        rows=[],
    )


def check_gpo(gpo_id: str) -> str:
    try:
        return check_id(gpo_id)
    except GpoError as e:
        raise _bad("invalid_request", str(e)) from e
