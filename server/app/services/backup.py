"""Built-in database backups for the Debian and Windows agents: pg_dump every few hours,
old dumps deleted. The Docker setup runs scripts/backup.sh in its own container instead.
Dumps are custom-format, restorable with pg_restore or `netadmin-agent restore`."""

import asyncio
import logging
import os
import shutil
import sys
import time
from datetime import datetime
from pathlib import Path

from sqlalchemy.engine import make_url

from ..config import Settings

log = logging.getLogger(__name__)

PREFIX = "netadmin-"
SUFFIX = ".dump"


class BackupError(RuntimeError):
    pass


def pg_tool(settings: Settings, name: str) -> str:
    """Full path of a PostgreSQL client tool: from PG_BIN_DIR when set, else from PATH."""
    exe = name + (".exe" if sys.platform == "win32" else "")
    if settings.pg_bin_dir:
        path = Path(settings.pg_bin_dir) / exe
        if path.is_file():
            return str(path)
        raise BackupError(f"{exe} not found in PG_BIN_DIR={settings.pg_bin_dir}")
    found = shutil.which(name)
    if not found:
        raise BackupError(f"{name} not found: install the PostgreSQL client or set PG_BIN_DIR")
    return found


def pg_env(database_url: str) -> dict[str, str]:
    """libpq variables for pg_dump/pg_restore from DATABASE_URL. The password goes through the
    environment of the child process only, never its command line."""
    url = make_url(database_url)
    env = dict(os.environ)
    host = url.query.get("host") or url.host
    if isinstance(host, tuple):
        host = host[0]
    for key, value in {
        "PGHOST": host,
        "PGPORT": str(url.port) if url.port else None,
        "PGUSER": url.username,
        "PGPASSWORD": url.password,
        "PGDATABASE": url.database,
    }.items():
        if value:
            env[key] = value
    return env


def list_dumps(folder: Path) -> list[Path]:
    return sorted(folder.glob(f"{PREFIX}*{SUFFIX}"))


def prune(folder: Path, keep_days: int, now: float | None = None) -> list[Path]:
    cutoff = (now or time.time()) - keep_days * 86400
    removed = []
    for dump in list_dumps(folder):
        if dump.stat().st_mtime < cutoff:
            dump.unlink(missing_ok=True)
            removed.append(dump)
    return removed


async def backup_once(settings: Settings, folder: Path | None = None) -> Path:
    """One dump into BACKUP_DIR (or folder). Written under a temporary name first,
    so a half-written file never looks like a finished backup."""
    folder = folder or Path(settings.backup_dir)
    # Dumps hold the whole inventory: owner-only.
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    target = folder / f"{PREFIX}{datetime.now().strftime('%Y%m%d-%H%M%S')}{SUFFIX}"
    partial = target.with_name(target.name + ".partial")
    proc = await asyncio.create_subprocess_exec(
        pg_tool(settings, "pg_dump"), "--format=custom", "--no-owner", f"--file={partial}",
        env=pg_env(settings.database_url),
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.PIPE,
    )  # fmt: skip
    _, err = await proc.communicate()
    if proc.returncode != 0:
        partial.unlink(missing_ok=True)
        raise BackupError(f"pg_dump failed: {err.decode(errors='replace').strip()}")
    partial.replace(target)
    os.chmod(target, 0o600)
    prune(folder, settings.backup_keep_days)
    return target


async def backup_loop(settings: Settings) -> None:
    # First dump shortly after start, so a fresh install has one before anyone needs it.
    await asyncio.sleep(60)
    while True:
        try:
            dump = await backup_once(settings)
            log.warning("backup ok: %s (%d KB)", dump, dump.stat().st_size // 1024)
        except Exception as e:  # keep the loop alive; the next round may succeed
            log.error("backup failed: %s", e)
        await asyncio.sleep(settings.backup_interval_hours * 3600)


async def restore(settings: Settings, dump: Path) -> None:
    """Replace the database contents with a dump. The API must not be running."""
    proc = await asyncio.create_subprocess_exec(
        pg_tool(settings, "pg_restore"), "--clean", "--if-exists", "--no-owner", "--single-transaction",
        f"--dbname={pg_env(settings.database_url)['PGDATABASE']}", str(dump),
        env=pg_env(settings.database_url),
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.PIPE,
    )  # fmt: skip
    _, err = await proc.communicate()
    if proc.returncode != 0:
        raise BackupError(f"pg_restore failed: {err.decode(errors='replace').strip()}")
