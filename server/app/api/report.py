"""The report on the infrastructure: everything the program holds, as a readable page (HTML, prints to PDF) or a
workbook (Excel). Made from the inventory at the moment of the request.

POST /report/preview   builds the page and returns a short-lived address to open it at (it prints from there)
POST /report/download  the page or the workbook as a file
GET  /report/view/{id} the built page; the address is the secret, it works for ten minutes and says nothing else
GET  /report/options   sections, presets
"""

import secrets
import time
from datetime import UTC, datetime
from typing import Literal

from fastapi import APIRouter, HTTPException, Request, Response
from pydantic import BaseModel, Field

from ..models import ScriptAuditLog
from ..reports import labels
from ..reports.data import Options, collect
from ..reports.render import render
from ..reports.xlsx import build
from .deps import SessionDep, who
from .lifecycle import ACTIONS

router = APIRouter(prefix="/report", tags=["report"])
# The built pages, by address: a handful, ten minutes. A page opened by its address needs no login, so it is never
# stored longer than it takes to look at it.
public_router = APIRouter(prefix="/report", tags=["report"])
TTL = 600
PAGE_CSP = (
    "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'; "
    "frame-ancestors 'none'"
)
KEEP = 6


class ReportIn(BaseModel):
    preset: Literal["full", "brief", "external"] | None = None
    sections: list[str] | None = None
    period_days: int = Field(default=30, ge=1, le=365)
    with_keys: bool = False
    # The full keys go into the file only when the user confirmed it.
    confirm_keys: bool = False
    mask: bool | None = None
    docs_full: bool = False
    format: Literal["html", "xlsx"] = "html"
    operator: str | None = Field(default=None, max_length=60)


def _options(body: ReportIn, request: Request) -> Options:
    preset = labels.PRESETS.get(body.preset or "full", labels.PRESETS["full"])
    wanted = body.sections if body.sections is not None else preset["sections"]
    unknown = [s for s in wanted if s not in labels.SECTION_IDS]
    if unknown:
        raise HTTPException(422, detail={"code": "invalid_request", "message": f"Нет такого раздела: {unknown[0]}"})
    if body.with_keys and not body.confirm_keys:
        raise HTTPException(
            400,
            detail={"code": "confirmation_required", "message": "Полные ключи лицензий: подтвердите и повторите"},
        )
    mask = preset["mask"] if body.mask is None else body.mask
    return Options(
        sections=[s for s in labels.SECTION_IDS if s in wanted] or ["summary"],
        period_days=body.period_days,
        with_keys=body.with_keys and not mask,
        mask=mask,
        brief=bool(preset["brief"]),
        docs_full=body.docs_full,
        operator=who(request, body.operator),
    )


async def _make(request: Request, session, body: ReportIn):
    opts = _options(body, request)
    data = await collect(session, request.app.state.settings, opts)
    session.add(
        ScriptAuditLog(
            script_name="report_keys" if opts.with_keys else "report_create",
            triggered_by=opts.operator,
            parameters={"sections": opts.sections, "mask": opts.mask, "format": body.format},
            executed_at=datetime.now(UTC),
            exit_code=0,
            output_log=f"{ACTIONS['report_keys' if opts.with_keys else 'report_create']}: {', '.join(opts.sections)}",
        )
    )
    await session.commit()
    return data


@router.get("/options")
async def options():
    return {
        "sections": [{"id": i, "title": t, "about": a} for i, t, a in labels.SECTIONS],
        "presets": [{"id": k, **v} for k, v in labels.PRESETS.items()],
    }


@router.post("/preview")
async def preview(body: ReportIn, request: Request, session: SessionDep):
    data = await _make(request, session, body)
    cache: dict[str, tuple[float, str]] = request.app.state.__dict__.setdefault("report_cache", {})
    now = time.monotonic()
    for k in [k for k, (t, _) in cache.items() if now - t > TTL]:
        cache.pop(k, None)
    for k in sorted(cache, key=lambda k: cache[k][0])[: max(0, len(cache) - KEEP + 1)]:
        cache.pop(k, None)
    rid = secrets.token_urlsafe(24)
    cache[rid] = (now, render(data))
    return {"id": rid, "url": f"/report/view/{rid}"}


@public_router.get("/view/{rid}")
async def view(rid: str, request: Request):
    cache = getattr(request.app.state, "report_cache", {})
    hit = cache.get(rid)
    if hit is None or time.monotonic() - hit[0] > TTL:
        raise HTTPException(404, detail="Отчёт не найден или устарел: сформируйте его заново")
    return Response(
        hit[1],
        media_type="text/html; charset=utf-8",
        headers={
            # Its own styles only; no scripts, nothing from outside, never framed.
            "Content-Security-Policy": PAGE_CSP,
            "X-Content-Type-Options": "nosniff",
            "Referrer-Policy": "no-referrer",
            "Cache-Control": "no-store",
        },
    )


@router.post("/download")
async def download(body: ReportIn, request: Request, session: SessionDep):
    data = await _make(request, session, body)
    stamp = data["meta"]["generated_at"].astimezone().strftime("%Y-%m-%d_%H-%M")
    if body.format == "xlsx":
        content, media, ext = (
            build(data),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xlsx",
        )
    else:
        content, media, ext = render(data).encode("utf-8"), "text/html; charset=utf-8", "html"
    return Response(
        content,
        media_type=media,
        headers={"Content-Disposition": f'attachment; filename="Infrastructure_Report_{stamp}.{ext}"'},
    )
