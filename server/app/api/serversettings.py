"""«Настройки сервера»: the settings an administrator changes without editing netadmin.env.

GET  /server-settings          every editable setting by group: its saved value, where it comes from, whether
                               a restart is still needed for it
PUT  /server-settings          save values (and drop the ones to reset to netadmin.env); audited
POST /server-settings/restart  restart the service so the saved values take effect
"""

import asyncio
import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, HTTPException, Request

from .. import serversettings as ss
from ..config import Settings
from ..models import ScriptAuditLog
from ..schemas import ServerSettingsIn, ServerSettingsOut
from .deps import SessionDep

log = logging.getLogger(__name__)

router = APIRouter(prefix="/server-settings", tags=["server settings"])

RESTART_DELAY_SECONDS = 1.0


def _default(key: str) -> Any:
    return ss.plain(Settings.model_fields[key].default)


async def _describe(request: Request, session) -> ServerSettingsOut:
    overrides = await ss.load(session)
    file_values: dict[str, Any] = request.app.state.settings_file
    active: dict[str, Any] = request.app.state.settings_active
    groups = []
    pending = False
    for g in ss.GROUPS:
        fields = []
        for spec in g.fields:
            saved = ss.plain(overrides[spec.key]) if spec.key in overrides else file_values[spec.key]
            if spec.key in overrides:
                source = "ui"
            else:
                source = "file" if file_values[spec.key] != _default(spec.key) else "default"
            restart = saved != active[spec.key]
            pending = pending or restart
            secret = spec.kind == "secret"
            fields.append(
                {
                    "key": spec.key,
                    "label": spec.label,
                    "help": spec.help,
                    "kind": spec.kind,
                    "choices": [{"value": c.value, "label": c.label} for c in spec.choices],
                    "credential_kind": spec.credential_kind or None,
                    "value": None if secret else saved,
                    "is_set": bool(saved) if secret else None,
                    "source": source,
                    "restart_required": restart,
                }
            )
        groups.append({"title": g.title, "note": g.note, "fields": fields})
    return ServerSettingsOut(
        groups=groups,
        restart_pending=pending,
        can_restart=getattr(request.app.state, "restart", None) is not None,
    )


@router.get("", response_model=ServerSettingsOut)
async def get_server_settings(request: Request, session: SessionDep):
    return await _describe(request, session)


@router.put("", response_model=ServerSettingsOut)
async def put_server_settings(body: ServerSettingsIn, request: Request, session: SessionDep):
    # An empty secret field means «keep it»: it is never sent back, so the form cannot show it.
    def kept_secret(k: str, v: Any) -> bool:
        return k in ss.SPECS and ss.SPECS[k].kind == "secret" and v in ("", None)

    values = {k: v for k, v in body.values.items() if not kept_secret(k, v)}
    try:
        changed = await ss.save(session, values, body.reset)
    except ss.SettingError as e:
        raise HTTPException(422, detail={"code": "bad_setting", "message": str(e), "key": e.key}) from e
    if changed:
        client = request.client.host if request.client else "unknown"
        shown = {k: ("***" if ss.SPECS[k].kind == "secret" else ss.plain(values.get(k))) for k in changed}
        session.add(
            ScriptAuditLog(
                script_name="server_settings",
                triggered_by=f"api:{client}",
                parameters={"changed": shown, "operator": body.operator},
                executed_at=datetime.now(UTC),
                exit_code=0,
                output_log="Изменены настройки сервера: " + ", ".join(ss.SPECS[k].label for k in changed),
            )
        )
    await session.commit()
    return await _describe(request, session)


@router.post("/restart", status_code=202)
async def restart_server(request: Request, session: SessionDep):
    restart = getattr(request.app.state, "restart", None)
    if restart is None:
        raise HTTPException(
            409,
            detail={
                "code": "cannot_restart",
                "message": "Сервер запущен не как служба: перезапустите его вручную, чтобы настройки вступили в силу",
            },
        )
    client = request.client.host if request.client else "unknown"
    session.add(
        ScriptAuditLog(
            script_name="server_restart",
            triggered_by=f"api:{client}",
            parameters={},
            executed_at=datetime.now(UTC),
            exit_code=0,
            output_log="Перезапуск сервера для применения настроек",
        )
    )
    await session.commit()
    log.warning("restart requested from %s to apply server settings", client)
    # After the answer has gone out.
    asyncio.get_running_loop().call_later(RESTART_DELAY_SECONDS, restart)
    return {"restarting": True}
