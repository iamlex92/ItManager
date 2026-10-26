import logging
import time
from datetime import UTC, datetime

from fastapi import APIRouter, HTTPException, Request
from sqlalchemy import select

from ..config import Settings
from ..models import Credential, CustomScript, ScriptAuditLog
from ..runbooks.catalog import BY_ID, CATALOG, InvalidParams, Runbook, validate
from ..runbooks.emulator import RunbookEnvironment, RunResult
from ..runbooks.real import REAL_CATEGORIES, Login, RealRunbooks
from ..schemas import RunbookChoiceOut, RunbookOut, RunbookParamOut, RunbookRunIn, RunbookRunOut
from . import scripts
from .deps import SessionDep, not_found

log = logging.getLogger(__name__)
router = APIRouter(prefix="/runbooks", tags=["runbooks"])

AUDIT_PREFIX = "runbook:"


def runbook_out(rb: Runbook) -> RunbookOut:
    return RunbookOut(
        id=rb.id,
        category=rb.category.value,
        title=rb.title,
        description=rb.description,
        critical=rb.critical,
        params=[
            RunbookParamOut(
                name=p.name,
                label=p.label,
                kind=p.kind.value,
                required=p.required,
                choices=[RunbookChoiceOut(value=c.value, label=c.label) for c in p.choices],
                placeholder=p.placeholder,
            )
            for p in rb.params
        ],
    )


def offered(rb: Runbook, env: object) -> bool:
    """Real mode offers only what has a real backend: AD and folder rights."""
    return not isinstance(env, RealRunbooks) or rb.category in REAL_CATEGORIES


async def runbook_login(session, settings: Settings) -> Login | None:
    name = settings.runbook_credential.strip()
    if not name:
        return None
    cred = await session.scalar(select(Credential).where(Credential.name == name))
    if cred is None or not cred.username or not cred.secret:
        return None
    return Login(cred.username, cred.secret)


@router.get("", response_model=list[RunbookOut])
async def list_runbooks(request: Request, session: SessionDep):
    env = request.app.state.runbooks
    custom = (await session.scalars(select(CustomScript).order_by(CustomScript.title))).all()
    return [runbook_out(rb) for rb in CATALOG if offered(rb, env)] + [scripts.runbook_out(s) for s in custom]


@router.post("/{runbook_id}/run", response_model=RunbookRunOut)
async def run_runbook(runbook_id: str, body: RunbookRunIn, request: Request, session: SessionDep):
    """Runs a runbook (emulated, or against AD in RUNBOOK_MODE=real) and writes the run to the audit log.
    Critical runbooks need "confirm": true, which the app sends after biometric confirmation."""
    env: RunbookEnvironment | RealRunbooks = request.app.state.runbooks
    custom = await scripts.by_runbook_id(session, runbook_id) if runbook_id.startswith(scripts.RUNBOOK_PREFIX) else None
    rb = scripts.as_runbook(custom) if custom else BY_ID.get(runbook_id)
    if rb is None or (custom is None and not offered(rb, env)):
        raise not_found("runbook")
    try:
        params = validate(rb, body.params)
    except InvalidParams as e:
        raise HTTPException(status_code=422, detail={"code": "invalid_params", "message": str(e)}) from e
    if rb.critical and body.confirm is not True:
        raise HTTPException(
            status_code=400,
            detail={
                "code": "confirmation_required",
                "message": 'critical runbook: confirm on the phone and repeat with "confirm": true',
            },
        )

    # Scripts from the web console are never executed, whatever RUNBOOK_MODE says.
    real = isinstance(env, RealRunbooks) and custom is None
    client = request.client.host if request.client else "unknown"
    triggered_by = f"{body.operator} · {client}" if body.operator else f"api:{client}"
    started_at = datetime.now(UTC)
    t0 = time.monotonic()
    try:
        if custom is not None:
            result = scripts.emulate(custom, params)
        elif isinstance(env, RealRunbooks):
            result = await env.run(rb.id, params, await runbook_login(session, request.app.state.settings))
        else:
            result = await env.run(rb.id, params)
    except Exception as e:
        log.exception("runbook %s failed", rb.id)
        result = RunResult(exit_code=1, stdout="", stderr=f"FAILED: {e!r}")
    duration_ms = int((time.monotonic() - t0) * 1000)

    entry = ScriptAuditLog(
        script_name=f"{AUDIT_PREFIX}{rb.id}",
        triggered_by=triggered_by[:100],
        parameters={
            "params": params,
            "confirmed": body.confirm,
            "auth_method": body.auth_method,
            "simulated": not real,
        },
        executed_at=started_at,
        exit_code=result.exit_code,
        output_log=result.stdout,
        stderr_log=result.stderr,
        duration_ms=duration_ms,
    )
    session.add(entry)
    await session.commit()
    return RunbookRunOut(
        audit_id=entry.id,
        runbook_id=rb.id,
        exit_code=result.exit_code,
        stdout=result.stdout,
        stderr=result.stderr,
        secret=result.secret,
        started_at=started_at,
        duration_ms=duration_ms,
        simulated=not real,
    )
