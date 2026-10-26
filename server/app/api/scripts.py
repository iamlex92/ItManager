"""Custom scripts written in the web console. The phone lists them next to the built-in runbooks.

Runs are always emulated: the server shows the script with the parameters filled in and executes
nothing, so a typo in a script typed into a browser can never reach a server or a switch.
"""

import re
import uuid

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from ..models import CustomScript
from ..runbooks.catalog import Category, Param, Runbook
from ..runbooks.emulator import RunResult
from ..schemas import RunbookOut, RunbookParamOut, ScriptCreate, ScriptOut, ScriptUpdate
from .deps import SessionDep, conflict, not_found

router = APIRouter(prefix="/scripts", tags=["scripts"])

RUNBOOK_PREFIX = "custom-"
CATEGORY = "Свои скрипты"
SHELLS = {"powershell": "PowerShell", "bash": "bash", "cmd": "cmd.exe"}
_PLACEHOLDER = re.compile(r"\{\{\s*([a-z][a-z0-9_]{0,30})\s*\}\}")


def runbook_id(script: CustomScript) -> str:
    return f"{RUNBOOK_PREFIX}{script.id}"


def script_out(script: CustomScript) -> ScriptOut:
    return ScriptOut(
        id=script.id,
        runbook_id=runbook_id(script),
        title=script.title,
        description=script.description,
        shell=script.shell,
        body=script.body,
        params=script.params,
        critical=script.critical,
        created_at=script.created_at,
        updated_at=script.updated_at,
    )


def as_runbook(script: CustomScript) -> Runbook:
    """The catalog shape, so the runbook API validates its arguments like any other runbook's."""
    params = tuple(
        Param(p["name"], p["label"], required=p.get("required", True), placeholder=p.get("placeholder"))
        for p in script.params
    )
    return Runbook(runbook_id(script), Category.INFRA, script.title, script.description, script.critical, params)


def runbook_out(script: CustomScript) -> RunbookOut:
    return RunbookOut(
        id=runbook_id(script),
        category=CATEGORY,
        title=script.title,
        description=script.description or "Скрипт из веб-консоли, запуск в режиме эмуляции",
        critical=script.critical,
        params=[
            RunbookParamOut(
                name=p["name"],
                label=p["label"],
                kind="text",
                required=p.get("required", True),
                placeholder=p.get("placeholder"),
            )
            for p in script.params
        ],
    )


async def by_runbook_id(session, rb_id: str) -> CustomScript | None:
    try:
        script_id = uuid.UUID(rb_id.removeprefix(RUNBOOK_PREFIX))
    except ValueError:
        return None
    return await session.get(CustomScript, script_id)


def emulate(script: CustomScript, params: dict[str, str]) -> RunResult:
    rendered = _PLACEHOLDER.sub(lambda m: params.get(m.group(1), ""), script.body)
    args = ", ".join(f"{k}={v}" for k, v in params.items()) or "нет"
    stdout = (
        "Эмуляция: скрипт не запускался, серверы и оборудование не затронуты.\n"
        f"Оболочка: {SHELLS.get(script.shell, script.shell)}\n"
        f"Параметры: {args}\n"
        "--- Текст скрипта с подставленными параметрами ---\n"
        f"{rendered}"
    )
    return RunResult(exit_code=0, stdout=stdout)


@router.get("", response_model=list[ScriptOut])
async def list_scripts(session: SessionDep):
    return [script_out(s) for s in (await session.scalars(select(CustomScript).order_by(CustomScript.title))).all()]


async def _save(session, script: CustomScript) -> ScriptOut:
    try:
        await session.commit()
    except IntegrityError as e:
        await session.rollback()
        raise conflict("name_taken", "Скрипт с таким названием уже есть") from e
    await session.refresh(script)
    return script_out(script)


def _check_placeholders(body: str, params: list[dict]) -> None:
    names = {p["name"] for p in params}
    unknown = sorted(set(_PLACEHOLDER.findall(body)) - names)
    if unknown:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "unknown_placeholder",
                "message": f"В тексте есть {{{{{unknown[0]}}}}}, но нет такого параметра",
            },
        )


@router.post("", response_model=ScriptOut, status_code=status.HTTP_201_CREATED)
async def create_script(body: ScriptCreate, session: SessionDep):
    data = body.model_dump()
    _check_placeholders(data["body"], data["params"])
    script = CustomScript(**data)
    session.add(script)
    return await _save(session, script)


@router.get("/{script_id}", response_model=ScriptOut)
async def get_script(script_id: uuid.UUID, session: SessionDep):
    script = await session.get(CustomScript, script_id)
    if script is None:
        raise not_found("script")
    return script_out(script)


@router.patch("/{script_id}", response_model=ScriptOut)
async def update_script(script_id: uuid.UUID, body: ScriptUpdate, session: SessionDep):
    script = await session.get(CustomScript, script_id)
    if script is None:
        raise not_found("script")
    for k, v in body.model_dump(exclude_unset=True, exclude_none=True).items():
        setattr(script, k, v)
    _check_placeholders(script.body, script.params)
    return await _save(session, script)


@router.delete("/{script_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_script(script_id: uuid.UUID, session: SessionDep):
    script = await session.get(CustomScript, script_id)
    if script is None:
        raise not_found("script")
    await session.delete(script)
    await session.commit()
