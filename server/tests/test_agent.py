import asyncio
import os
import shutil
import time
from pathlib import Path

import pytest
from sqlalchemy.engine import make_url

from app.agent import cli, configfile, connectqr
from app.config import Settings
from app.crypto import valid_key
from app.services import backup

from .conftest import TEST_DB, make_settings


def test_init_config_creates_private_file_with_safe_defaults(tmp_path: Path):
    path = tmp_path / "netadmin.env"
    cli.main(["init-config", str(path), "--managed-db", str(tmp_path / "pgdata"), "--backup-dir", str(tmp_path / "b")])
    values = configfile.read(path)
    assert values["HAL_MODE"] == "emulator" and values["ALLOW_DEVICE_WRITES"] == "false"
    assert values["MONITOR_MODE"] == "emulator" and "SEED_DEMO_DATA" not in values
    assert values["RUNBOOK_MODE"] == "emulator" and values["ALLOW_RUNBOOK_WRITES"] == "false"
    assert len(values["API_TOKEN"]) >= 32 and valid_key(values["SECRET_KEY"])
    url = make_url(values["DATABASE_URL"])
    assert (url.host, url.port, url.username) == ("127.0.0.1", 54329, "netadmin") and len(url.password) >= 24
    assert values["PG_MANAGED_DATA"] == str(tmp_path / "pgdata")
    if os.name == "posix":
        assert path.stat().st_mode & 0o777 == 0o600
    # The generated file is a valid configuration.
    Settings(**{k.lower(): v for k, v in values.items() if k.lower() in Settings.model_fields})

    # Never overwritten: reinstalling keeps the keys the phones already use.
    cli.main(["init-config", str(path), "--database-url", "postgresql+asyncpg://x@/y"])
    assert configfile.read(path)["API_TOKEN"] == values["API_TOKEN"]


def test_two_configs_get_different_keys():
    first = configfile.render(database_url="postgresql+asyncpg://a@/b")
    second = configfile.render(database_url="postgresql+asyncpg://a@/b")
    assert first != second


def test_pg_env_takes_password_from_url_not_argv():
    env = backup.pg_env("postgresql+asyncpg://netadmin:p%40ss@127.0.0.1:54329/netadmin")
    assert (env["PGHOST"], env["PGPORT"], env["PGUSER"], env["PGPASSWORD"], env["PGDATABASE"]) == (
        "127.0.0.1",
        "54329",
        "netadmin",
        "p@ss",
        "netadmin",
    )
    sock = backup.pg_env("postgresql+asyncpg://netadmin@/netadmin?host=/var/run/postgresql")
    assert (sock["PGHOST"], sock["PGUSER"], sock["PGDATABASE"]) == ("/var/run/postgresql", "netadmin", "netadmin")


def test_prune_keeps_recent_dumps(tmp_path: Path):
    old = tmp_path / "netadmin-20200101-000000.dump"
    new = tmp_path / "netadmin-20990101-000000.dump"
    other = tmp_path / "notes.txt"
    for f in (old, new, other):
        f.write_bytes(b"x")
    os.utime(old, (time.time() - 20 * 86400,) * 2)
    assert backup.prune(tmp_path, keep_days=14) == [old]
    assert new.exists() and other.exists()


def test_missing_pg_tool_is_reported(tmp_path: Path):
    settings = make_settings(pg_bin_dir=str(tmp_path))
    with pytest.raises(backup.BackupError, match="pg_dump"):
        backup.pg_tool(settings, "pg_dump")


@pytest.mark.skipif(shutil.which("pg_dump") is None, reason="pg_dump not installed")
def test_backup_once_writes_a_restorable_dump(tmp_path: Path):
    settings = make_settings(backup_dir=str(tmp_path), backup_keep_days=3)
    dump = asyncio.run(backup.backup_once(settings))
    assert dump.parent == tmp_path and dump.name.startswith("netadmin-") and dump.suffix == ".dump"
    assert dump.read_bytes()[:5] == b"PGDMP"  # custom format
    assert not list(tmp_path.glob("*.partial"))


def test_backup_failure_leaves_no_partial_file(tmp_path: Path):
    if shutil.which("pg_dump") is None:
        pytest.skip("pg_dump not installed")
    url = make_url(TEST_DB).set(database="no_such_database_here")
    settings = make_settings(database_url=url.render_as_string(hide_password=False))
    with pytest.raises(backup.BackupError, match="pg_dump failed"):
        asyncio.run(backup.backup_once(settings, tmp_path))
    assert list(tmp_path.iterdir()) == []


def test_selfcheck_from_source(capsys):
    cli.main(["selfcheck"])
    assert "ok" in capsys.readouterr().out


def test_connect_qr_payload_and_drawing():
    data = connectqr.payload(["10.0.0.5", "192.168.1.2"], 8000, "a+b/c=")
    assert data == "netadmin://connect?u=http%3A%2F%2F10.0.0.5%3A8000&u=http%3A%2F%2F192.168.1.2%3A8000&t=a%2Bb%2Fc%3D"
    art = connectqr.terminal(data).splitlines()
    assert len({len(line) for line in art}) == 1  # a rectangle
    assert set("".join(art)) <= set("█▀▄ ")
    assert art[0] == "█" * len(art[0])  # quiet zone drawn light


def test_info_shows_qr_with_token_only(tmp_path: Path, capsys, monkeypatch):
    path = tmp_path / "netadmin.env"
    cli.main(["init-config", str(path), "--database-url", "postgresql+asyncpg://x@/y"])
    values = configfile.read(path)
    # info loads the file into the environment; set it through monkeypatch so other tests get theirs back.
    for key, value in values.items():
        monkeypatch.setenv(key, value)
    monkeypatch.setattr(cli, "_addresses", lambda: ["10.0.0.5"])
    capsys.readouterr()
    cli.main(["info", "--config", str(path)])
    out = capsys.readouterr().out
    assert values["API_TOKEN"] in out and "▀" in out
    cli.main(["info", "--config", str(path), "--no-token"])
    out = capsys.readouterr().out
    assert values["API_TOKEN"] not in out and "▀" not in out
