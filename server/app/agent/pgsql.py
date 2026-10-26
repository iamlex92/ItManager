"""The database bundled with the Windows agent: a private PostgreSQL cluster that the agent
creates on first start, starts before the API and stops after it. Listens on 127.0.0.1 only,
so nothing outside the computer reaches it. On Debian the system PostgreSQL is used instead."""

import os
import subprocess
import sys
import tempfile
from pathlib import Path

import asyncpg

# Not 5432, so it does not clash with a PostgreSQL someone already runs on the same computer.
DEFAULT_PORT = 54329


class PostgresError(RuntimeError):
    pass


class ManagedPostgres:
    def __init__(self, bin_dir: Path, data_dir: Path, port: int, user: str, password: str, log_file: Path):
        self.bin_dir = bin_dir
        self.data_dir = data_dir
        self.port = port
        self.user = user
        self.password = password
        self.log_file = log_file

    def _tool(self, name: str) -> str:
        path = self.bin_dir / (name + (".exe" if sys.platform == "win32" else ""))
        if not path.is_file():
            raise PostgresError(f"{path} not found")
        return str(path)

    def _run(self, *args: str, timeout: float = 120) -> subprocess.CompletedProcess:
        # No shell, no password on the command line.
        return subprocess.run(args, capture_output=True, text=True, timeout=timeout, env=dict(os.environ, LC_ALL="C"))

    @property
    def initialized(self) -> bool:
        return (self.data_dir / "PG_VERSION").is_file()

    def init(self) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        fd, pwfile = tempfile.mkstemp(prefix="netadmin-pw-")
        try:
            with os.fdopen(fd, "w") as f:
                f.write(self.password + "\n")
            r = self._run(
                self._tool("initdb"), "-D", str(self.data_dir), "-U", self.user, f"--pwfile={pwfile}",
                "--auth=scram-sha-256", "--encoding=UTF8", "--locale=C",
            )  # fmt: skip
        finally:
            os.unlink(pwfile)
        if r.returncode != 0:
            raise PostgresError(f"initdb failed: {(r.stderr or r.stdout).strip()}")
        with open(self.data_dir / "postgresql.conf", "a", encoding="utf-8") as conf:
            conf.write(
                "\n# NetAdmin agent: local connections only\n"
                "listen_addresses = '127.0.0.1'\n"
                f"port = {self.port}\n"
                "unix_socket_directories = ''\n"
                "max_connections = 50\n"
            )

    def running(self) -> bool:
        return self._run(self._tool("pg_ctl"), "status", "-D", str(self.data_dir)).returncode == 0

    def start(self) -> None:
        if self.running():
            return
        self.log_file.parent.mkdir(parents=True, exist_ok=True)
        # Output goes to the log file, not to pipes: on Windows the server process inherits pg_ctl's
        # handles, and a pipe it holds open would keep this call waiting for as long as the database runs.
        ctl_log = self.log_file.with_name("pg_ctl.log")
        with open(ctl_log, "a", encoding="utf-8") as log:
            code = subprocess.run(
                [self._tool("pg_ctl"), "start", "-D", str(self.data_dir), "-l", str(self.log_file), "-w", "-t", "90"],
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                timeout=120,
            ).returncode
        if code != 0:
            raise PostgresError(f"database did not start, see {self.log_file} and {ctl_log}")

    def stop(self) -> None:
        if self.running():
            self._run(self._tool("pg_ctl"), "stop", "-D", str(self.data_dir), "-m", "fast", "-w", "-s")

    async def ensure_database(self, name: str) -> bool:
        """Create the application database if missing. True when it was created."""
        conn = await asyncpg.connect(
            host="127.0.0.1", port=self.port, user=self.user, password=self.password, database="postgres"
        )
        try:
            if await conn.fetchval("SELECT 1 FROM pg_database WHERE datname = $1", name):
                return False
            # Identifier comes from our own config, quoted anyway.
            await conn.execute(f'CREATE DATABASE "{name.replace(chr(34), "")}"')
            return True
        finally:
            await conn.close()
