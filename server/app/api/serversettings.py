"""«Настройки сервера»: the settings an administrator changes without editing netadmin.env.

GET  /server-settings          every editable setting by group: its saved value, where it comes from, whether
                               a restart is still needed for it
PUT  /server-settings          save values (and drop the ones to reset to netadmin.env); audited
POST /server-settings/restart  restart the service so the saved values take effect
POST /server-settings/rotate-token  a new API token: written to the settings file and in force at once
"""

import asyncio
import logging
import os
import re
import secrets
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, HTTPException, Request

from .. import serversettings as ss
from ..agent import configfile, connectqr
from ..config import Settings
from ..models import ScriptAuditLog
from ..schemas import RotateTokenIn, RotateTokenOut, ServerSettingsIn, ServerSettingsOut
from .deps import SessionDep, who

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
        shown = {k: ("***" if ss.SPECS[k].kind == "secret" else ss.plain(values.get(k))) for k in changed}
        session.add(
            ScriptAuditLog(
                script_name="server_settings",
                triggered_by=who(request),
                parameters={"changed": shown, "operator": body.operator},
                executed_at=datetime.now(UTC),
                exit_code=0,
                output_log="Изменены настройки сервера: " + ", ".join(ss.SPECS[k].label for k in changed),
            )
        )
    await session.commit()
    return await _describe(request, session)


@router.post("/controller-certificate")
async def controller_certificate(body: dict, request: Request, session: SessionDep):
    """The certificate the domain controller presents on LDAPS, read without trusting it, for the administrator to
    check by its fingerprint and save as «Сертификат контроллера». Nothing is saved here."""
    from ..runbooks.real import RunbookConfigError, fetch_controller_certificate

    url = str(body.get("url") or "").strip()
    if not url:
        overrides = await ss.load(session)
        url = str(overrides.get("ad_ldap_url") or request.app.state.settings_file.get("ad_ldap_url") or "")
    try:
        return await asyncio.to_thread(fetch_controller_certificate, url)
    except RunbookConfigError as e:
        raise HTTPException(409, detail={"code": "certificate_failed", "message": str(e)}) from e


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
            triggered_by=who(request),
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


def _replace_token(text: str, new: str) -> str:
    if re.search(r"^API_TOKEN=.*$", text, flags=re.M):
        return re.sub(r"^API_TOKEN=.*$", lambda _: f"API_TOKEN={new}", text, count=1, flags=re.M)
    return text.rstrip("\n") + f"\nAPI_TOKEN={new}\n"


@router.post("/rotate-token", response_model=RotateTokenOut)
async def rotate_token(body: RotateTokenIn, request: Request, session: SessionDep):
    """Replaces the API token: the new one goes into the settings file (so it survives a restart) and works at once; the
    old one stops working. Every phone and console must then enter the new key: the answer carries it with the QR code
    that the app scans, once and nowhere else (the journal says only that it happened)."""
    if body.confirm is not True:
        raise HTTPException(
            400,
            detail={
                "code": "confirmation_required",
                "message": 'Смена ключа отключает все телефоны и консоли: повторите с "confirm": true',
            },
        )
    path = configfile.default_path()
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        raise HTTPException(
            409,
            detail={
                "code": "config_missing",
                "message": f"Файл настроек {path} не найден: сервер запущен не из пакета. Смените API_TOKEN там, "
                "где он задан (например, в .env у Docker), и перезапустите сервер",
            },
        ) from None
    new = secrets.token_urlsafe(32)
    try:
        # In place: the owner and the mode of the file stay as the installer made them.
        path.write_text(_replace_token(text, new), encoding="utf-8")
    except OSError:
        raise HTTPException(
            409,
            detail={
                "code": "config_not_writable",
                "message": f"Служба не может записать {path}. Смените ключ командой «sudo netadmin-agent rotate-token» "
                "и перезапустите службу, либо дайте службе право записи в файл (на Debian: sudo chmod g+w "
                "/etc/netadmin/netadmin.env)",
            },
        ) from None
    request.app.state.settings.api_token = new
    os.environ["API_TOKEN"] = new
    session.add(
        ScriptAuditLog(
            script_name="token_rotate",
            triggered_by=who(request, body.operator),
            parameters={},
            executed_at=datetime.now(UTC),
            exit_code=0,
            output_log="Смена API-токена: прежний ключ больше не принимается",
        )
    )
    await session.commit()
    log.warning("API token replaced from %s", request.client.host if request.client else "unknown")
    from ..agent.cli import _addresses

    port = request.url.port or int(os.environ.get("API_PORT", "8000"))
    seen = [request.url.hostname] if request.url.hostname else []
    addresses = list(dict.fromkeys([*seen, *_addresses()]))[: connectqr.MAX_ADDRESSES]
    link = connectqr.payload(addresses, port, new)
    return RotateTokenOut(token=new, link=link, qr_svg=connectqr.svg(link), addresses=addresses, config=str(path))
