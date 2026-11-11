"""Keeping the backup server's database a copy of this one.

The main server packs every inventory table (COPY, text format) with a manifest into a zip and posts it to the
backup server's /sync/receive every few minutes. The backup replaces its tables with it in one transaction: a
package that fails halfway leaves the old data. Each server keeps its own «Настройки сервера» (server_settings is
never sent). No pg_dump is needed, so it works the same on Debian, Windows and Docker.

The backup takes a package only from a server at the same schema revision and with the same SECRET_KEY: encrypted
values (SNMP communities, stored logins) travel as they are stored and must stay readable there.
"""

import asyncio
import hashlib
import json
import logging
import tempfile
import uuid
import zipfile
from collections.abc import AsyncIterator
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path

import asyncpg
import httpx
from sqlalchemy.engine import make_url

from .. import crypto
from ..config import Settings
from ..models import Base

log = logging.getLogger(__name__)

FORMAT = 2
MANIFEST = "manifest.json"
# Never copied: each server has its own settings.
LOCAL_TABLES = {"server_settings"}
CHUNK = 1 << 16
# A package bigger than this is refused by the backup server.
MAX_PACKAGE = 2 << 30
PUSH_TIMEOUT = 600


class SyncError(RuntimeError):
    """Said to the operator as it is."""


@dataclass
class SyncStatus:
    # This server as the main one: its last push to the backup.
    pushed_at: str | None = None
    push_error: str | None = None
    push_bytes: int = 0
    push_tables: int = 0
    push_rows: int = 0
    # This server as the backup: the last package taken.
    received_at: str | None = None
    receive_error: str | None = None
    received_rows: int = 0
    received_created_at: str | None = None
    # The server this one takes the database from (set by the first package, kept until reset).
    source_id: str | None = None
    # This server's own id, put in every package it sends.
    server_id: str | None = None

    def as_dict(self) -> dict:
        return asdict(self)


# The server's own id and the source it accepts packages from: rows of server_settings, which is never copied.
KEY_SERVER_ID = "_sync_server_id"
KEY_SOURCE_ID = "_sync_source_id"
KEY_LAST_CREATED = "_sync_last_created"
KEEP_PRESYNC = 3


async def kv_get(conn: asyncpg.Connection, key: str) -> str | None:
    value = await conn.fetchval("SELECT value FROM server_settings WHERE id = $1", key)
    return crypto.box().decrypt(value) if value is not None else None


async def kv_set(conn: asyncpg.Connection, key: str, value: str | None) -> None:
    if value is None:
        await conn.execute("DELETE FROM server_settings WHERE id = $1", key)
        return
    await conn.execute(
        "INSERT INTO server_settings (id, value) VALUES ($1, $2) ON CONFLICT (id) DO UPDATE SET value = EXCLUDED.value",
        key,
        crypto.box().encrypt(value),
    )


async def server_id(settings: Settings) -> str:
    """This server's id: made on first use and kept, so a backup can tell who sends the packages."""
    conn = await asyncpg.connect(dsn(settings.database_url))
    try:
        found = await kv_get(conn, KEY_SERVER_ID)
        if found:
            return found
        found = str(uuid.uuid4())
        await kv_set(conn, KEY_SERVER_ID, found)
        return found
    finally:
        await conn.close()


async def reset_source(settings: Settings) -> None:
    """Forget the server packages were taken from, so another one may be accepted."""
    conn = await asyncpg.connect(dsn(settings.database_url))
    try:
        await kv_set(conn, KEY_SOURCE_ID, None)
        await kv_set(conn, KEY_LAST_CREATED, None)
    finally:
        await conn.close()


def tables() -> list:
    """The tables to copy, parents before the tables that refer to them."""
    return [t for t in Base.metadata.sorted_tables if t.name not in LOCAL_TABLES]


def key_fingerprint(secret_key: str) -> str:
    """Says whether two servers share SECRET_KEY without saying the key; empty when none is set."""
    return hashlib.sha256(secret_key.encode()).hexdigest()[:16] if secret_key else ""


def dsn(database_url: str) -> str:
    return make_url(database_url).set(drivername="postgresql").render_as_string(hide_password=False)


async def _revision(conn: asyncpg.Connection) -> str:
    return await conn.fetchval("SELECT version_num FROM alembic_version LIMIT 1") or ""


def _q(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


async def pack(settings: Settings, target: Path) -> dict:
    """Writes the package to [target]; returns its manifest. Read in one REPEATABLE READ snapshot, so the tables
    agree with each other."""
    own_id = await server_id(settings)
    conn = await asyncpg.connect(dsn(settings.database_url))
    try:
        manifest = {
            "format": FORMAT,
            "source_id": own_id,
            "revision": await _revision(conn),
            "key": key_fingerprint(settings.secret_key),
            "created_at": datetime.now(UTC).isoformat(timespec="seconds"),
            "tables": {},
        }
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            async with conn.transaction(isolation="repeatable_read", readonly=True):
                for t in tables():
                    rows = 0
                    with zf.open(f"tables/{t.name}.tsv", "w", force_zip64=True) as out:

                        async def write(data: bytes, out=out) -> None:
                            nonlocal rows
                            rows += data.count(b"\n")
                            out.write(data)

                        await conn.copy_from_table(t.name, columns=[c.name for c in t.columns], output=write)
                    manifest["tables"][t.name] = rows
            zf.writestr(MANIFEST, json.dumps(manifest))
        return manifest
    finally:
        await conn.close()


async def _chunks(f) -> AsyncIterator[bytes]:
    while True:
        data = await asyncio.to_thread(f.read, CHUNK)
        if not data:
            return
        yield data


async def unpack(settings: Settings, package: Path, force: bool = False) -> dict:
    """Replaces this server's inventory with [package]. All or nothing. [force]: for putting back a kept copy of the
    data that was replaced, so the source and age checks do not apply."""
    try:
        zf = zipfile.ZipFile(package)
    except zipfile.BadZipFile as e:
        raise SyncError("пакет повреждён: это не zip") from e
    with zf:
        try:
            manifest = json.loads(zf.read(MANIFEST))
        except (KeyError, ValueError) as e:
            raise SyncError("в пакете нет описания (manifest.json)") from e
        if manifest.get("format") != FORMAT:
            raise SyncError(f"формат пакета {manifest.get('format')} не поддерживается: обновите резервный сервер")
        if manifest.get("key", "") != key_fingerprint(settings.secret_key):
            raise SyncError(
                "SECRET_KEY на серверах разный: зашифрованные пароли и community были бы нечитаемы. "
                "Задайте на резервном сервере тот же SECRET_KEY, что на основном"
            )
        mine = [t for t in tables()]
        missing = [t.name for t in mine if f"tables/{t.name}.tsv" not in zf.namelist()]
        if missing:
            raise SyncError(f"в пакете нет таблиц: {', '.join(missing)}")
        source = str(manifest.get("source_id") or "")
        created = str(manifest.get("created_at") or "")
        if not source or not created:
            raise SyncError("в пакете нет сведений об источнике: обновите основной сервер")
        own_id = await server_id(settings)
        if source == own_id and not force:
            raise SyncError(
                "пакет пришёл с этого же сервера: проверьте адрес резервного сервера в настройках основного"
            )
        conn = await asyncpg.connect(dsn(settings.database_url))
        try:
            revision = await _revision(conn)
            if manifest.get("revision") != revision:
                raise SyncError(
                    f"версии базы разные (основной {manifest.get('revision')}, резервный {revision}): "
                    "обновите оба сервера до одной версии"
                )
            known = await kv_get(conn, KEY_SOURCE_ID)
            if known and known != source and not force:
                raise SyncError(
                    f"этот сервер уже принимает базу от другого сервера ({known[:8]}…, а пакет от {source[:8]}…): "
                    "если основной сервер заменён, нажмите «Принимать от другого сервера» в настройках"
                )
            last = await kv_get(conn, KEY_LAST_CREATED)
            if last and created < last and not force:
                raise SyncError(f"пакет старше уже принятого (снят {created}, принят {last}): он не применён")
            if not force:
                await _keep_presync_copy(settings)
            async with conn.transaction():
                # Do not hang behind a long request: try again next time instead.
                await conn.execute("SET LOCAL lock_timeout = '30s'")
                await conn.execute("TRUNCATE " + ", ".join(_q(t.name) for t in mine))
                for t in mine:
                    with zf.open(f"tables/{t.name}.tsv") as src:
                        await conn.copy_to_table(t.name, columns=[c.name for c in t.columns], source=_chunks(src))
                if not force:
                    await kv_set(conn, KEY_SOURCE_ID, source)
                    await kv_set(conn, KEY_LAST_CREATED, created)
        finally:
            await conn.close()
    return manifest


async def _keep_presync_copy(settings: Settings) -> None:
    """The data about to be replaced, kept as a package in the backup folder (the last few), so a wrong or bad copy
    from the main server can be undone with `netadmin-agent restore-sync FILE`. Without BACKUP_DIR there is none."""
    if not settings.backup_dir:
        log.warning("database sync: BACKUP_DIR is not set, the data replaced by the main server's is not kept")
        return
    folder = Path(settings.backup_dir) / "presync"
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    target = folder / f"netadmin-presync-{datetime.now().strftime('%Y%m%d-%H%M%S')}.zip"
    await pack(settings, target)
    for old in sorted(folder.glob("netadmin-presync-*.zip"))[:-KEEP_PRESYNC]:
        old.unlink(missing_ok=True)


def _receive_url(settings: Settings) -> str:
    base = settings.backup_server_url.rstrip("/")
    if not base:
        raise SyncError("адрес резервного сервера не задан")
    return f"{base}/api/v1/sync/receive"


async def push(settings: Settings, status: SyncStatus, transport: httpx.AsyncBaseTransport | None = None) -> None:
    """Packs this database and hands it to the backup server. The outcome lands in [status]; raises SyncError."""
    if not settings.backup_server_token:
        raise SyncError("не задан API-токен резервного сервера")
    with tempfile.TemporaryDirectory(prefix="netadmin-sync-") as tmp:
        package = Path(tmp) / "package.zip"
        try:
            manifest = await pack(settings, package)
            size = package.stat().st_size

            async def body() -> AsyncIterator[bytes]:
                with package.open("rb") as f:
                    async for chunk in _chunks(f):
                        yield chunk

            async with httpx.AsyncClient(timeout=PUSH_TIMEOUT, transport=transport) as client:
                r = await client.post(
                    _receive_url(settings),
                    content=body(),
                    headers={
                        "Authorization": f"Bearer {settings.backup_server_token}",
                        "Content-Type": "application/zip",
                        "Content-Length": str(size),
                    },
                )
            if r.status_code != 200:
                try:
                    detail = r.json().get("detail") or {}
                    message = detail.get("message") if isinstance(detail, dict) else str(detail)
                except ValueError:
                    message = r.text[:200]
                if r.status_code == 401:
                    message = "резервный сервер не принял API-токен"
                raise SyncError(f"резервный сервер ответил {r.status_code}: {message}")
        except httpx.HTTPError as e:
            status.push_error = f"резервный сервер недоступен: {e}"
            raise SyncError(status.push_error) from e
        except SyncError as e:
            status.push_error = str(e)
            raise
        except Exception as e:
            log.exception("sync to the backup server failed")
            status.push_error = f"не удалось: {e}"
            raise SyncError(status.push_error) from e
    status.pushed_at = datetime.now(UTC).isoformat(timespec="seconds")
    status.push_error = None
    status.push_bytes = size
    status.push_tables = len(manifest["tables"])
    status.push_rows = sum(manifest["tables"].values())


async def sync_loop(settings: Settings, status: SyncStatus, lock: asyncio.Lock) -> None:
    # Not in the first minute: let the server settle after a start.
    await asyncio.sleep(60)
    while True:
        try:
            async with lock:
                await push(settings, status)
            log.warning("database sent to the backup server (%d KB)", status.push_bytes // 1024)
        except SyncError as e:
            log.error("database sync to the backup server: %s", e)
        await asyncio.sleep(settings.sync_interval_minutes * 60)
