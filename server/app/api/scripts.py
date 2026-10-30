"""Custom scripts written in the web console. The phone lists them next to the built-in runbooks.

Each script says where it runs: «emulator» shows it with the parameters filled in and runs nothing (what every
script did before); «pc» runs it on a domain computer chosen at each run (PowerShell, cmd and Python on Windows over
WinRM, bash and Python on Astra Linux over SSH with sudo); «server» on the NetAdmin server itself (Python, bash).
A real run needs «Разрешить изменения», a confirmation (the fingerprint on the phone) and goes to the audit log;
parameters are filled in as quoted strings, never as code (runbooks/custom.py).
"""

import asyncio
import uuid

from fastapi import APIRouter, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from ..models import CustomScript
from ..pcs.backend import PcError, RealPcs, valid_host
from ..pcs.service import allowed_host, detect_platform
from ..runbooks import custom
from ..runbooks.catalog import Category, Param, Runbook
from ..runbooks.emulator import RunResult
from ..schemas import RunbookOut, RunbookParamOut, ScriptCreate, ScriptOut, ScriptUpdate
from .deps import SessionDep, conflict, not_found

router = APIRouter(prefix="/scripts", tags=["scripts"])

RUNBOOK_PREFIX = "custom-"
CATEGORY = "Свои скрипты"
SHELLS = custom.SHELL_NAMES
_PLACEHOLDER = custom.PLACEHOLDER
# The computer a «pc» script runs on: asked first, before the script's own parameters.
HOST_PARAM = "host"
TARGETS = {"emulator": "только показ", "pc": "на компьютере домена", "server": "на сервере NetAdmin"}


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
        runs_on=script.runs_on,
        created_at=script.created_at,
        updated_at=script.updated_at,
    )


def _params(script: CustomScript) -> list[dict]:
    host = [{"name": HOST_PARAM, "label": "Компьютер", "required": True, "placeholder": "PC-BUH-01 или 10.10.10.21"}]
    return (host if script.runs_on == "pc" else []) + list(script.params)


def _critical(script: CustomScript) -> bool:
    """A script that really runs is confirmed every time, with the fingerprint on the phone."""
    return script.critical or script.runs_on != "emulator"


def as_runbook(script: CustomScript) -> Runbook:
    """The catalog shape, so the runbook API validates its arguments like any other runbook's."""
    params = tuple(
        Param(p["name"], p["label"], required=p.get("required", True), placeholder=p.get("placeholder"))
        for p in _params(script)
    )
    return Runbook(runbook_id(script), Category.INFRA, script.title, script.description, _critical(script), params)


def runbook_out(script: CustomScript) -> RunbookOut:
    return RunbookOut(
        id=runbook_id(script),
        category=CATEGORY,
        title=script.title,
        description=script.description
        or f"Скрипт из веб-консоли: {SHELLS.get(script.shell, script.shell)}, {TARGETS.get(script.runs_on, '')}",
        critical=_critical(script),
        params=[
            RunbookParamOut(
                name=p["name"],
                label=p["label"],
                kind="text",
                required=p.get("required", True),
                placeholder=p.get("placeholder"),
            )
            for p in _params(script)
        ],
    )


async def by_runbook_id(session, rb_id: str) -> CustomScript | None:
    try:
        script_id = uuid.UUID(rb_id.removeprefix(RUNBOOK_PREFIX))
    except ValueError:
        return None
    return await session.get(CustomScript, script_id)


def emulate(script: CustomScript, params: dict[str, str], why: str = "") -> RunResult:
    try:
        rendered = custom.render(script.shell, script.body, params)
    except custom.ScriptError as e:
        return RunResult(exit_code=1, stdout="", stderr=str(e))
    args = ", ".join(f"{k}={v}" for k, v in params.items()) or "нет"
    stdout = (
        f"Эмуляция{why}: скрипт не запускался, серверы и оборудование не затронуты.\n"
        f"Оболочка: {SHELLS.get(script.shell, script.shell)} · где: {TARGETS.get(script.runs_on, script.runs_on)}\n"
        f"Параметры: {args}\n"
        "--- Текст скрипта с подставленными параметрами ---\n"
        f"{rendered}"
    )
    return RunResult(exit_code=0, stdout=stdout)


def _refuse(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})


async def execute(
    script: CustomScript, params: dict[str, str], request: Request, session, dry_run: bool
) -> tuple[RunResult, bool]:
    """The run itself; (result, simulated). Refusals (writes off, an unknown computer, a value cmd cannot take)
    are raised before anything is sent anywhere."""
    from .runbooks import linux_login, runbook_login

    values = {k: v for k, v in params.items() if not (script.runs_on == "pc" and k == HOST_PARAM)}
    if dry_run or script.runs_on == "emulator":
        return emulate(script, params), True
    settings = request.app.state.settings
    if not settings.allow_runbook_writes:
        raise _refuse(403, "writes_disabled", "Запуск скриптов выключен: «Настройки сервера» → «Разрешить изменения».")
    try:
        source = custom.render(script.shell, script.body, values)
    except custom.ScriptError as e:
        raise _refuse(422, "invalid_params", str(e)) from e

    if script.runs_on == "server":
        try:
            return await custom.run_on_server(script.shell, source), False
        except custom.ScriptError as e:
            raise _refuse(422, "invalid_request", str(e)) from e

    windows = request.app.state.pcs
    if not isinstance(windows, RealPcs):
        return emulate(script, params, " (компьютеры домена в режиме эмуляции)"), True
    try:
        host = valid_host(params.get(HOST_PARAM, ""))
    except PcError as e:
        raise _refuse(422, "invalid_params", str(e)) from e
    login = await runbook_login(session, settings)
    if not await allowed_host(session, settings, login, host):
        raise _refuse(
            403, "unknown_host", f"{host} нет ни в учёте, ни в AD: скрипт отправляется только известным компьютерам."
        )
    try:
        platform = await detect_platform(
            host, settings, False, request.app.state.pc_platforms, request.app.state.pc_probe
        )
        if platform == "windows":
            if script.shell not in custom.WINDOWS:
                raise custom.ScriptError(f"{host} — Windows: {SHELLS[script.shell]} там не запускается")
            if len(source) > custom.MAX_WINDOWS[script.shell]:
                limit = custom.MAX_WINDOWS[script.shell]
                raise custom.ScriptError(f"Для Windows по WinRM скрипт {SHELLS[script.shell]} — до {limit} символов")
            if login is None:
                raise PcError("Не задана «Учётная запись домена» в «Настройках сервера»")
            assert windows.shell_factory is not None
            code, out, err = await asyncio.to_thread(
                windows.shell_factory(login), host, custom.windows_script(script.shell, source)
            )
        else:
            if script.shell not in custom.LINUX:
                raise custom.ScriptError(f"{host} — Astra Linux: {SHELLS[script.shell]} там не запускается")
            linux = await linux_login(session, settings)
            if linux is None:
                raise PcError("Не задана учётная запись для Astra Linux в «Настройках сервера»")
            runner = request.app.state.astra.runner_factory(linux)
            code, out, err = await runner(host, custom.linux_command(script.shell, source), linux.password + "\n")
    except custom.ScriptError as e:
        raise _refuse(422, "invalid_request", str(e)) from e
    except PcError as e:
        raise _refuse(409, "pc_failed", str(e)) from e
    header = f"{host} ({'Windows' if platform == 'windows' else 'Astra Linux'})\n"
    return RunResult(exit_code=code, stdout=header + out[: custom.MAX_OUTPUT], stderr=err[: custom.MAX_OUTPUT]), False


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


def _check_placeholders(body: str, params: list[dict], runs_on: str = "emulator") -> None:
    names = {p["name"] for p in params}
    if runs_on == "pc" and HOST_PARAM in names:
        raise HTTPException(
            status_code=422,
            detail={"code": "reserved_param", "message": "Параметр host занят: так спрашивается компьютер для запуска"},
        )
    if runs_on == "pc":
        names.add(HOST_PARAM)
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
    _check_placeholders(data["body"], data["params"], data["runs_on"])
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
    _check_placeholders(script.body, script.params, script.runs_on)
    return await _save(session, script)


@router.delete("/{script_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_script(script_id: uuid.UUID, session: SessionDep):
    script = await session.get(CustomScript, script_id)
    if script is None:
        raise not_found("script")
    await session.delete(script)
    await session.commit()
