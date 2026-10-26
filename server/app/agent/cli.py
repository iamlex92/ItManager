"""netadmin-agent: the server packaged for Debian (systemd) and Windows (a service), no Docker.

    netadmin-agent run                 # what the service runs: migrations, then the API
    netadmin-agent info                # address and key to enter in the app
    netadmin-agent backup              # one database backup now
    netadmin-agent restore FILE        # replace the database with a backup (service stopped)
    netadmin-agent init-config PATH …  # used by the installers, never overwrites
    netadmin-agent selfcheck           # build check: everything the frozen binary needs is inside

The config file defaults to /etc/netadmin/netadmin.env (Windows: %ProgramData%\\NetAdmin\\netadmin.env)
or NETADMIN_CONFIG; --config overrides both.
"""

import argparse
import asyncio
import contextlib
import logging
import os
import secrets
import socket
import sys
from collections.abc import Iterator
from pathlib import Path

from sqlalchemy.engine import make_url

from .. import __version__
from . import configfile, connectqr
from .pgsql import DEFAULT_PORT, ManagedPostgres

log = logging.getLogger("netadmin.agent")


def resource_dir() -> Path:
    """Where alembic/ lives: inside the PyInstaller bundle, or the server/ source folder."""
    if getattr(sys, "frozen", False):
        return Path(sys._MEIPASS)  # type: ignore[attr-defined]
    return Path(__file__).resolve().parents[2]


def _load(config: Path) -> None:
    if not config.is_file():
        raise SystemExit(f"config not found: {config} (reinstall, or create it with init-config)")
    try:
        configfile.load_into_env(config)
    except PermissionError:
        raise SystemExit(f"no access to {config}: run as administrator (sudo)") from None


def _managed_postgres() -> ManagedPostgres | None:
    data = os.environ.get("PG_MANAGED_DATA", "").strip()
    if not data:
        return None
    url = make_url(os.environ["DATABASE_URL"])
    bin_dir = os.environ.get("PG_BIN_DIR", "").strip()
    if not bin_dir:
        raise SystemExit("PG_MANAGED_DATA is set but PG_BIN_DIR is empty")
    data_dir = Path(data)
    return ManagedPostgres(
        bin_dir=Path(bin_dir),
        data_dir=data_dir,
        port=int(os.environ.get("PG_PORT") or url.port or DEFAULT_PORT),
        user=url.username or "netadmin",
        password=url.password or "",
        log_file=data_dir.parent / "logs" / "postgresql.log",
    )


@contextlib.contextmanager
def _database(owner: bool = False) -> Iterator[None]:
    """Makes sure the bundled database runs for the duration of the block (Windows); no-op with the
    system one. The service is the owner and stops it on exit; a one-off command like backup stops it
    only if it was not running before, so it never pulls the database from under the service."""
    pg = _managed_postgres()
    if pg is None:
        yield
        return
    if not pg.initialized:
        log.warning("creating the database in %s", pg.data_dir)
        pg.init()
    was_running = pg.running()
    pg.start()
    try:
        name = make_url(os.environ["DATABASE_URL"]).database or "netadmin"
        if asyncio.run(pg.ensure_database(name)):
            log.warning("database %s created", name)
        yield
    finally:
        if owner or not was_running:
            pg.stop()


def migrate(database_url: str) -> None:
    from alembic import command
    from alembic.config import Config

    cfg = Config()
    cfg.set_main_option("script_location", str(resource_dir() / "alembic"))
    cfg.attributes["database_url"] = database_url
    command.upgrade(cfg, "head")


def cmd_run(args: argparse.Namespace) -> None:
    _load(args.config)
    import uvicorn

    from ..config import get_settings
    from ..main import create_app

    settings = get_settings()
    with _database(owner=True):
        log.warning("NetAdmin %s: applying database migrations", __version__)
        migrate(settings.database_url)
        if os.environ.get("SEED_DEMO_DATA", "false").strip().lower() == "true":
            from ..seed import main as seed_main

            asyncio.run(seed_main(do_reset=False, if_empty=True))
        host = os.environ.get("API_BIND", "0.0.0.0").strip() or "0.0.0.0"
        port = int(os.environ.get("API_PORT", "8000"))
        log.warning("listening on %s:%d", host, port)
        uvicorn.run(create_app(settings), host=host, port=port, proxy_headers=True, log_level="info")


def cmd_backup(args: argparse.Namespace) -> None:
    _load(args.config)
    from ..config import get_settings
    from ..services import backup

    settings = get_settings()
    folder = Path(args.dir or settings.backup_dir or ".")
    with _database():
        dump = asyncio.run(backup.backup_once(settings, folder))
    print(f"backup ok: {dump} ({dump.stat().st_size // 1024} KB)")


def cmd_restore(args: argparse.Namespace) -> None:
    _load(args.config)
    from ..config import get_settings
    from ..services import backup

    dump = Path(args.file)
    if not dump.is_file():
        raise SystemExit(f"no such file: {dump}")
    if not args.yes:
        answer = input(f"Replace ALL data in the NetAdmin database with {dump.name}? Stop the service first. [y/N] ")
        if answer.strip().lower() not in {"y", "yes", "д", "да"}:
            raise SystemExit("cancelled")
    settings = get_settings()
    with _database():
        asyncio.run(backup.restore(settings, dump))
    print("restored; start the service again")


def _addresses() -> list[str]:
    """This computer's IPv4 addresses, the one of the default route first: it is the likeliest
    to be reachable from the phone."""
    primary = None
    # UDP connect sends nothing; it also covers a host name that resolves to loopback only.
    with contextlib.suppress(OSError), socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("10.255.255.255", 1))
        primary = s.getsockname()[0]
    found: set[str] = set()
    with contextlib.suppress(OSError):
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.add(str(info[4][0]))
    rest = sorted(a for a in found if a != primary)
    return [a for a in [primary, *rest] if a and not a.startswith(("127.", "0."))]


def cmd_info(args: argparse.Namespace) -> None:
    try:
        _load(args.config)
        port = os.environ.get("API_PORT", "8000")
        print("NetAdmin: что ввести в приложении (⚙ → Сервер)\n")
        addrs = _addresses()
        for a in addrs or ["<IP этого компьютера>"]:
            print(f"  Адрес сервера: {a}:{port}")
        token = os.environ.get("API_TOKEN", "")
        if args.no_token:
            print("  API-токен:     покажет sudo netadmin-agent info\n")
        else:
            print(f"  API-токен:     {token}\n")
            if addrs and not args.no_qr:
                # The QR carries the token too, so it is shown only where the token is.
                print("Или отсканируйте код в приложении: ⚙ → Сканировать QR-код\n")
                print(connectqr.terminal(connectqr.payload(addrs, port, token)))
                print()
        print(f"Настройки: {args.config}")
        print(
            f"Проверка: http://{(addrs or ['127.0.0.1'])[0]}:{port}/api/v1/health должен открываться с телефона.\n"
            "Если нет, проверьте, что телефон в той же сети (или в VPN) и порт открыт в межсетевом экране."
        )
    finally:
        if args.pause:
            with contextlib.suppress(EOFError):
                input("\nНажмите Enter, чтобы закрыть окно")


def cmd_init_config(args: argparse.Namespace) -> None:
    path = Path(args.path)
    if args.managed_db:
        database_url = configfile.managed_database_url(args.pg_port, secrets.token_urlsafe(24))
    elif args.database_url:
        database_url = args.database_url
    else:
        raise SystemExit("give --database-url or --managed-db")
    text = configfile.render(
        database_url=database_url,
        managed_data=Path(args.managed_db) if args.managed_db else None,
        pg_port=args.pg_port,
        pg_bin=Path(args.pg_bin) if args.pg_bin else None,
        backup_dir=Path(args.backup_dir) if args.backup_dir else None,
        trap_port=args.trap_port,
    )
    if configfile.write_new(path, text):
        print(f"created {path}")
    else:
        print(f"kept existing {path}")


def cmd_selfcheck(args: argparse.Namespace) -> None:
    """Fails loudly when the frozen build misses a module or data file."""
    import ldap3  # noqa: F401  real-mode runbooks import these only when they run
    import winrm  # noqa: F401
    from pysnmp.smi import builder

    from .. import main  # noqa: F401  imports every API module
    from ..discovery import snmp  # noqa: F401

    mibs = builder.MibBuilder()
    mibs.load_modules("SNMPv2-MIB", "SNMP-FRAMEWORK-MIB")
    versions = resource_dir() / "alembic" / "versions"
    migrations = sorted(p.name for p in versions.glob("*.py"))
    if not migrations:
        raise SystemExit(f"no migrations in {versions}")
    static = Path(main.__file__).parent / "static"
    if not any(static.iterdir()):
        raise SystemExit(f"no static files in {static}")
    print(f"netadmin-agent {__version__}: ok, {len(migrations)} migrations, SNMP MIBs load")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="netadmin-agent", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("--version", action="version", version=__version__)
    sub = p.add_subparsers(dest="command", required=True)

    def with_config(sp: argparse.ArgumentParser) -> argparse.ArgumentParser:
        sp.add_argument("--config", type=Path, default=configfile.default_path(), help="settings file")
        return sp

    with_config(sub.add_parser("run", help="run the server (what the service starts)")).set_defaults(func=cmd_run)
    b = with_config(sub.add_parser("backup", help="make one database backup now"))
    b.add_argument("--dir", help="folder for the dump (default BACKUP_DIR)")
    b.set_defaults(func=cmd_backup)
    r = with_config(sub.add_parser("restore", help="replace the database with a backup"))
    r.add_argument("file")
    r.add_argument("--yes", action="store_true", help="do not ask")
    r.set_defaults(func=cmd_restore)
    i = with_config(sub.add_parser("info", help="address and API token for the app"))
    i.add_argument("--pause", action="store_true", help=argparse.SUPPRESS)
    i.add_argument("--no-token", action="store_true", help="do not print the API token (nor the QR code)")
    i.add_argument("--no-qr", action="store_true", help="do not draw the connection QR code")
    i.set_defaults(func=cmd_info)
    c = sub.add_parser("init-config", help="create the settings file with fresh keys (never overwrites)")
    c.add_argument("path")
    c.add_argument("--database-url", help="existing PostgreSQL (Debian: the system cluster)")
    c.add_argument("--managed-db", help="data folder of the bundled database (Windows)")
    c.add_argument("--pg-port", type=int, default=DEFAULT_PORT)
    c.add_argument("--pg-bin", help="folder with initdb, pg_ctl, pg_dump")
    c.add_argument("--backup-dir")
    c.add_argument("--trap-port", type=int, default=0)
    c.set_defaults(func=cmd_init_config)
    sub.add_parser("selfcheck", help=argparse.SUPPRESS).set_defaults(func=cmd_selfcheck)
    return p


def main(argv: list[str] | None = None) -> None:
    # Redirected output on Windows defaults to the ANSI code page, which has no Cyrillic.
    for stream in (sys.stdout, sys.stderr):
        if stream is not None and not stream.isatty() and hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname).5s [%(name)s] %(message)s")
    args = build_parser().parse_args(argv)
    args.func(args)
