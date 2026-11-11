"""The backup server's copy of the database.

GET  /sync          what was last sent (main server) or taken (backup server)
POST /sync/push     send the database to the backup server now (main server)
POST /sync/receive  take the main server's package over this database (backup server, «принимать базу» on)
"""

import asyncio
import logging
import tempfile
from datetime import UTC, datetime
from pathlib import Path

import httpx
from fastapi import APIRouter, HTTPException, Request

from .. import FULL_VERSION
from .. import serversettings as ss
from ..models import ScriptAuditLog
from ..services import dbsync
from ..services.realtime import Hub
from .deps import SessionDep, who

log = logging.getLogger(__name__)

router = APIRouter(prefix="/sync", tags=["backup server sync"])


def _status(request: Request) -> dbsync.SyncStatus:
    return request.app.state.sync_status


def _lock(request: Request) -> asyncio.Lock:
    return request.app.state.sync_lock


def _out(request: Request) -> dict:
    s = request.app.state.settings
    return {
        "role": "backup" if s.sync_accept else "main" if s.sync_to_backup else "",
        "read_only": s.sync_accept and s.sync_read_only,
        "backup_server_url": s.backup_server_url.rstrip("/"),
        "interval_minutes": s.sync_interval_minutes,
        "key": dbsync.key_fingerprint(s.secret_key),
        **_status(request).as_dict(),
    }


async def _audit(request: Request, action: str, exit_code: int, text: str, params: dict) -> None:
    async with request.app.state.sessionmaker() as s:
        s.add(
            ScriptAuditLog(
                script_name=action, triggered_by=who(request), parameters=params, exit_code=exit_code,
                output_log=text,
            )
        )  # fmt: skip
        await s.commit()


@router.get("")
async def sync_status(request: Request):
    st = _status(request)
    try:
        st.server_id = await dbsync.server_id(request.app.state.settings)
    except Exception:
        log.exception("sync: server id not read")
    return _out(request)


@router.post("/check")
async def sync_check(request: Request):
    """Asks the backup server whether a push would be taken: reachable, token accepted, receiving on, same SECRET_KEY.
    Sends nothing and changes nothing; the answer is a list of checks, each ok or not."""
    s = request.app.state.settings
    base = s.backup_server_url.rstrip("/")
    checks: list[dict] = []

    def add(name: str, ok: bool, text: str) -> None:
        checks.append({"name": name, "ok": ok, "text": text})

    if not base:
        add("Адрес", False, "адрес резервного сервера не задан")
        return {"ok": False, "checks": checks}
    transport = getattr(request.app.state, "sync_transport", None)
    try:
        async with httpx.AsyncClient(timeout=10, transport=transport) as client:
            h = await client.get(f"{base}/api/v1/health")
            add("Связь", h.status_code == 200, f"ответил {h.status_code}")
            if h.status_code != 200:
                return {"ok": False, "checks": checks}
            info = h.json()
            add("Версия", True, f"{info.get('version', '?')} (здесь {FULL_VERSION})")
            r = await client.get(f"{base}/api/v1/sync", headers={"Authorization": f"Bearer {s.backup_server_token}"})
    except (httpx.HTTPError, ValueError) as e:
        add("Связь", False, f"сервер недоступен: {e}")
        return {"ok": False, "checks": checks}
    if r.status_code == 401:
        add("Токен", False, "резервный сервер не принял API-токен")
        return {"ok": False, "checks": checks}
    add("Токен", r.status_code == 200, "принят" if r.status_code == 200 else f"ответил {r.status_code}")
    if r.status_code == 200:
        there = r.json()
        add(
            "Приём базы",
            there.get("role") == "backup",
            "включён" if there.get("role") == "backup" else "выключен: на резервном сервере включите «Принимать базу»",
        )
        mine = dbsync.key_fingerprint(s.secret_key)
        same = there.get("key", "") == mine
        add("Ключ шифрования", same, "совпадает" if same else "SECRET_KEY на серверах разный")
    return {"ok": all(c["ok"] for c in checks), "checks": checks}


@router.post("/reset-source")
async def sync_reset_source(request: Request):
    """The backup forgets which server it takes packages from, so another one (a replaced main server) may send."""
    await dbsync.reset_source(request.app.state.settings)
    _status(request).source_id = None
    await _audit(request, "sync:reset-source", 0, "source forgotten", {})
    return _out(request)


@router.post("/promote")
async def sync_promote(request: Request, session: SessionDep):
    """This backup server becomes a main one: it stops taking packages and accepts changes at once. Polling and
    ping start with the next restart (the saved setting «принимать базу» is off from then). Use when the main server
    is lost."""
    settings = request.app.state.settings
    if not settings.sync_accept:
        raise HTTPException(
            422, detail={"code": "not_backup", "message": "Этот сервер и так не резервный: он не принимает базу"}
        )
    changed = await ss.save(session, {"sync_accept": False}, [])
    await session.commit()
    settings.sync_accept = False
    await _audit(request, "sync:promote", 0, "backup server promoted to main", {"changed": changed})
    out = _out(request)
    out["restart_required"] = True
    return out


@router.post("/push")
async def sync_push(request: Request):
    """Sends this database to the backup server now, the same as the scheduled sync. Changes only the backup."""
    if not request.app.state.settings.backup_server_url:
        raise HTTPException(
            422, detail={"code": "no_backup_server", "message": "Адрес резервного сервера не задан в настройках"}
        )
    async with _lock(request):
        try:
            await dbsync.push(request.app.state.settings, _status(request))
        except dbsync.SyncError as e:
            await _audit(request, "sync:push", 1, str(e), {})
            raise HTTPException(502, detail={"code": "sync_failed", "message": str(e)}) from e
    st = _status(request)
    await _audit(request, "sync:push", 0, f"{st.push_rows} rows, {st.push_bytes} bytes", {})
    return _out(request)


@router.post("/receive")
async def sync_receive(request: Request):
    """Takes the main server's database over this one, in one transaction. Only with «принимать базу» on."""
    settings = request.app.state.settings
    if not settings.sync_accept:
        raise HTTPException(
            403,
            detail={
                "code": "sync_not_accepted",
                "message": "этот сервер не принимает базу: включите «Этот сервер — резервный» в его настройках",
            },
        )
    status = _status(request)
    async with _lock(request):
        with tempfile.TemporaryDirectory(prefix="netadmin-sync-") as tmp:
            package = Path(tmp) / "package.zip"
            size = 0
            with package.open("wb") as f:
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > dbsync.MAX_PACKAGE:
                        raise HTTPException(413, detail={"code": "too_big", "message": "пакет больше 2 ГБ"})
                    await asyncio.to_thread(f.write, chunk)
            try:
                manifest = await dbsync.unpack(settings, package)
            except dbsync.SyncError as e:
                status.receive_error = str(e)
                await _audit(request, "sync:receive", 1, str(e), {})
                raise HTTPException(409, detail={"code": "sync_refused", "message": str(e)}) from e
            except Exception as e:
                log.exception("taking the main server's database failed")
                status.receive_error = f"не удалось: {e}"
                await _audit(request, "sync:receive", 1, status.receive_error, {})
                raise HTTPException(500, detail={"code": "sync_failed", "message": status.receive_error}) from e
    status.received_at = datetime.now(UTC).isoformat(timespec="seconds")
    status.receive_error = None
    status.received_rows = sum(manifest["tables"].values())
    status.received_created_at = manifest.get("created_at")
    status.source_id = manifest.get("source_id")
    # Screens open on this server reload what changed under them.
    hub: Hub = request.app.state.hub
    hub.publish(None, {"type": "inventory_changed"})
    await _audit(request, "sync:receive", 0, f"{status.received_rows} rows", {"created_at": status.received_created_at})
    return _out(request)
