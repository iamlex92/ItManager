import logging
import time
from datetime import UTC, datetime

from fastapi import APIRouter, HTTPException, Request

from ..models import ScriptAuditLog
from ..runbooks.catalog import BY_ID, CATALOG, InvalidParams, Runbook, validate
from ..runbooks.emulator import RunbookEnvironment, RunResult
from ..schemas import RunbookChoiceOut, RunbookOut, RunbookParamOut, RunbookRunIn, RunbookRunOut
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


@router.get("", response_model=list[RunbookOut])
async def list_runbooks():
    return [runbook_out(rb) for rb in CATALOG]


@router.post("/{runbook_id}/run", response_model=RunbookRunOut)
async def run_runbook(runbook_id: str, body: RunbookRunIn, request: Request, session: SessionDep):
    """Runs a runbook against the emulated environment and writes the run to the audit log.
    Critical runbooks need "confirm": true, which the app sends after biometric confirmation."""
    rb = BY_ID.get(runbook_id)
    if rb is None:
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

    env: RunbookEnvironment = request.app.state.runbooks
    client = request.client.host if request.client else "unknown"
    triggered_by = f"{body.operator} · {client}" if body.operator else f"api:{client}"
    started_at = datetime.now(UTC)
    t0 = time.monotonic()
    try:
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
            "simulated": True,
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
    )
