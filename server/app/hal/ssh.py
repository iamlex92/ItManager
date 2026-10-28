"""An interactive SSH CLI session with a Cisco IOS or Eltex MES switch.

The switch is driven the way an engineer types: one shell, a prompt after each command, `enable` when the login
lands in user mode, paging turned off, `[confirm]`-style questions answered. Commands are sent one at a time and a
`%`-error stops a configuration block (the session then leaves config mode with `end`).
"""

import asyncio
import contextlib
import logging
import re
from collections.abc import AsyncIterator
from dataclasses import dataclass, field

from .base import SwitchError

log = logging.getLogger(__name__)

# Old IOS and MES firmware only speak SHA-1 key exchange, CBC ciphers and ssh-rsa host keys.
# Modern algorithms stay first, so a current switch still gets them.
_LEGACY_KEX = ("diffie-hellman-group14-sha1", "diffie-hellman-group-exchange-sha1", "diffie-hellman-group1-sha1")
_LEGACY_CIPHERS = ("aes128-cbc", "aes256-cbc", "3des-cbc")
_LEGACY_MACS = ("hmac-sha1",)

# A character followed by a backspace: what MES prints to wipe its «More» marker.
_ERASED = re.compile(r"[^\x08\n]\x08")
_ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]|\x1b[()][AB012]")
_PROMPT = re.compile(r"(?:^|\n)([A-Za-z0-9_.\-@/:]{1,64})(\([^)\n]{0,40}\))?([>#])\s*$")
_LOGIN = re.compile(r"(user ?name|login)\s*:\s*$", re.IGNORECASE)
_PASSWORD = re.compile(r"password\s*:\s*$", re.IGNORECASE)
_MORE = re.compile(r"(--\s*more\s*--|more:\s*<space>.*)\s*$", re.IGNORECASE)
# Questions a command can ask: `write memory` on old IOS, `copy running-config startup-config` on MES.
_CONFIRM_ENTER = re.compile(r"(\[confirm\]|\[startup-config\]\?|destination filename \[[^\]]*\]\?)\s*$", re.IGNORECASE)
_CONFIRM_YES = re.compile(
    r"(\(y/n\)(\s*\[[yn]\])?|\[y/n\]|\[yes/no\]|yes/press any key for no[^\n]*)\s*\??\s*$", re.IGNORECASE
)
_ERROR = re.compile(
    r"^\s*%\s*(invalid|incomplete|ambiguous|unrecognized|unknown|bad|error|wrong|access denied|authorization|"
    r"interface .* does not exist|no such|vlan .* (does not exist|not exist))",
    re.IGNORECASE | re.MULTILINE,
)


@dataclass(frozen=True)
class SwitchLogin:
    username: str
    password: str = field(repr=False)
    # Empty: the login password is tried for `enable` too.
    enable_secret: str = field(default="", repr=False)


@dataclass(frozen=True)
class SshOptions:
    port: int = 22
    connect_timeout: float = 15.0
    command_timeout: float = 30.0
    # A known_hosts file to check switch host keys against; empty accepts any key.
    known_hosts: str = ""


def clean(text: str) -> str:
    """Drop terminal escapes, carriage returns and backspace-erased paging markers."""
    text = _ANSI.sub("", text).replace("\r\n", "\n").replace("\r", "")
    while True:
        erased = _ERASED.sub("", text)
        if erased == text:
            return text.replace("\b", "")
        text = erased


def cli_error(output: str) -> str | None:
    """The first `% ...` error line of a command's output, if the switch refused it."""
    m = _ERROR.search(output)
    if m is None:
        return None
    line_end = output.find("\n", m.start() + 1)
    return output[m.start() : line_end if line_end >= 0 else None].strip()


class CliSession:
    """One logged-in, privileged shell. Use through [open_cli]."""

    def __init__(self, process, login: SwitchLogin, vendor: str, timeout: float) -> None:
        self._p = process
        self._login = login
        self._vendor = vendor
        self._timeout = timeout
        self._base = ""  # the hostname part of the prompt, learnt at login
        self.transcript: list[str] = []

    def _is_prompt(self, buf: str) -> bool:
        m = _PROMPT.search(buf[-200:])
        if m is None:
            return False
        return not self._base or m.group(1) == self._base

    async def _read(self, timeout: float, answer_questions: bool = True, login_phase: bool = False) -> str:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        buf = ""
        while True:
            left = deadline - loop.time()
            if left <= 0:
                tail = clean(buf)[-200:]
                raise SwitchError("switch_timeout", f"the switch did not answer in {timeout:.0f} s: …{tail}")
            try:
                chunk = await asyncio.wait_for(self._p.stdout.read(65536), left)
            except TimeoutError:
                continue
            if not chunk:
                raise SwitchError("switch_closed", f"the switch closed the session: …{clean(buf)[-200:]}")
            buf += chunk
            tail = clean(buf)[-300:]
            if _MORE.search(tail):
                buf = re.sub(r"(--\s*more\s*--|more:\s*<space>[^\n]*)\s*$", "", buf, flags=re.IGNORECASE)
                self._p.stdin.write(" ")
                continue
            if login_phase and _LOGIN.search(tail):
                return clean(buf)
            if login_phase and _PASSWORD.search(tail):
                return clean(buf)
            if answer_questions and _CONFIRM_YES.search(tail):
                self._p.stdin.write("y")
                continue
            if answer_questions and _CONFIRM_ENTER.search(tail):
                self._p.stdin.write("\n")
                continue
            if self._is_prompt(clean(buf)):
                return clean(buf)

    async def start(self) -> None:
        """Log in where the switch asks again (MES), reach privileged mode, turn paging off."""
        out = await self._read(self._timeout, login_phase=True)
        for _ in range(3):
            if _LOGIN.search(out[-100:]):
                self._p.stdin.write(self._login.username + "\n")
                out = await self._read(self._timeout, login_phase=True)
            elif _PASSWORD.search(out[-100:]):
                self._p.stdin.write(self._login.password + "\n")
                out = await self._read(self._timeout, login_phase=True)
            else:
                break
        m = _PROMPT.search(out[-200:])
        if m is None:
            raise SwitchError("switch_login_failed", "no command prompt after login")
        self._base = m.group(1)
        if m.group(3) == ">":
            self._p.stdin.write("enable\n")
            out = await self._read(self._timeout, login_phase=True)
            if _PASSWORD.search(out[-100:]):
                self._p.stdin.write((self._login.enable_secret or self._login.password) + "\n")
                out = await self._read(self._timeout, login_phase=True)
            m = _PROMPT.search(out[-200:])
            if m is None or m.group(3) != "#":
                raise SwitchError(
                    "switch_login_failed",
                    "enable was refused: set SWITCH_ENABLE_SECRET or give the login privilege 15",
                )
        paging = "terminal datadump" if self._vendor == "ELTEX" else "terminal length 0"
        await self.run(paging)

    async def run(self, command: str, timeout: float | None = None) -> str:
        """Send one line and return what it printed, without the echo and the next prompt."""
        self._p.stdin.write(command + "\n")
        out = await self._read(timeout or self._timeout)
        self.transcript.append(command)
        lines = out.split("\n")
        if lines and lines[0].strip().endswith(command.strip()):
            lines = lines[1:]
        if lines and _PROMPT.search(lines[-1]):
            lines = lines[:-1]
        return "\n".join(lines).strip("\n")

    async def checked(self, command: str, timeout: float | None = None) -> str:
        out = await self.run(command, timeout)
        if err := cli_error(out):
            raise SwitchError("switch_rejected", f"{command}: {err}")
        return out

    async def configure(self, commands: list[str]) -> None:
        """Run a `configure terminal … end` block; the first refused line stops it and leaves config mode."""
        for cmd in commands:
            out = await self.run(cmd)
            if err := cli_error(out):
                with contextlib.suppress(SwitchError):
                    await self.run("end")
                raise SwitchError("switch_rejected", f"{cmd}: {err}")


@contextlib.asynccontextmanager
async def open_cli(host: str, login: SwitchLogin, vendor: str, options: SshOptions) -> AsyncIterator[CliSession]:
    import asyncssh  # only real mode needs it

    def algs(defaults: list[bytes], legacy: tuple[str, ...]) -> list[str]:
        names = [a.decode() for a in defaults]
        return names + [a for a in legacy if a not in names]

    from asyncssh.encryption import get_default_encryption_algs
    from asyncssh.kex import get_default_kex_algs
    from asyncssh.mac import get_default_mac_algs
    from asyncssh.public_key import get_default_public_key_algs

    try:
        conn = await asyncio.wait_for(
            asyncssh.connect(
                host,
                port=options.port,
                username=login.username,
                password=login.password,
                known_hosts=options.known_hosts or None,
                client_keys=None,
                agent_path=None,
                preferred_auth=("keyboard-interactive", "password"),
                kex_algs=algs(get_default_kex_algs(), _LEGACY_KEX),
                encryption_algs=algs(get_default_encryption_algs(), _LEGACY_CIPHERS),
                mac_algs=algs(get_default_mac_algs(), _LEGACY_MACS),
                server_host_key_algs=algs(get_default_public_key_algs(), ("ssh-rsa",)),
            ),
            options.connect_timeout,
        )
    except asyncssh.PermissionDenied as e:
        raise SwitchError("switch_login_failed", f"{host}: login refused ({login.username})") from e
    except (OSError, TimeoutError, asyncssh.Error) as e:
        raise SwitchError("switch_unreachable", f"{host}: {e or type(e).__name__}") from e
    try:
        process = await conn.create_process(term_type="vt100", term_size=(250, 50), encoding="utf-8", errors="replace")
        cli = CliSession(process, login, vendor, options.command_timeout)
        await cli.start()
        yield cli
        with contextlib.suppress(Exception):
            process.stdin.write("exit\n")
    finally:
        conn.close()
        with contextlib.suppress(Exception):
            await asyncio.wait_for(conn.wait_closed(), 5)
