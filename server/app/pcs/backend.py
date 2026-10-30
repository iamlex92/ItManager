"""Domain computers: what Connect-DomainPC.ps1 did from an admin's PC, done by the server over WinRM.

RUNBOOK_MODE decides, as for runbooks: «real» runs the scripts in scripts.py on the computer with the
login from RUNBOOK_CREDENTIAL; «emulator» answers with made-up data and says so («simulated»).
Changes (cleanup, gpupdate, reboot, stopping a process, the console) also need ALLOW_RUNBOOK_WRITES=true
in real mode; the API asks the app for a biometric confirmation on the risky ones.
"""

import asyncio
import hashlib
import json
import re
from collections.abc import Callable
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

from ..config import Settings
from ..runbooks.real import Login, Shell, winrm_shell
from . import scripts

# A computer name (NetBIOS or FQDN) or an IPv4 address: what may follow -ComputerName.
HOST = re.compile(r"^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*$")
PROCESS = re.compile(r"^[A-Za-z0-9_.\- ]{1,64}$")
MAX_COMMAND = 4000


class Action(StrEnum):
    INFO = "info"
    DISKS = "disks"
    FOLDERS = "folders"
    PROCESSES = "processes"
    CLEANUP = "cleanup"
    GPUPDATE = "gpupdate"
    REBOOT = "reboot"
    KILL = "kill"
    COMMAND = "command"
    # Remote help: msra for Windows, x11vnc with the user's consent for Astra Linux.
    ASSIST = "assist"
    # Astra Linux only.
    UPDATE = "update"
    ADMIN_ADD = "admin_add"
    ADMIN_REMOVE = "admin_remove"


READ_ACTIONS = {Action.INFO, Action.DISKS, Action.FOLDERS, Action.PROCESSES}
# Asked again with a fingerprint on the phone (and «Да» in the web console) before they run.
CONFIRM_ACTIONS = {
    Action.CLEANUP,
    Action.REBOOT,
    Action.KILL,
    Action.COMMAND,
    Action.UPDATE,
    Action.ADMIN_ADD,
    Action.ADMIN_REMOVE,
}
WINDOWS_ACTIONS = {
    Action.INFO,
    Action.DISKS,
    Action.FOLDERS,
    Action.PROCESSES,
    Action.CLEANUP,
    Action.GPUPDATE,
    Action.REBOOT,
    Action.KILL,
    Action.COMMAND,
    Action.ASSIST,
}


class PcError(Exception):
    """Something to show the administrator as it is: no login, no WinRM, a refused host."""


@dataclass
class PcResult:
    ok: bool
    data: Any = None
    error: str = ""
    simulated: bool = False


def valid_host(host: str) -> str:
    h = host.strip().rstrip(".")
    if not HOST.match(h):
        raise PcError(f"«{host}» не похоже на имя компьютера или IP-адрес")
    return h


def script_for(action: Action, arg: str | None) -> str:
    if action not in WINDOWS_ACTIONS or action is Action.ASSIST:
        raise PcError(
            "Это действие есть только для Astra Linux"
            if action not in WINDOWS_ACTIONS
            else "Удалённая помощь Windows запускается с вашего компьютера (msra), не на сервере"
        )
    if action is Action.KILL:
        name = (arg or "").strip()
        name = name[:-4] if name.lower().endswith(".exe") else name
        if not PROCESS.match(name):
            raise PcError("Имя процесса: буквы, цифры, точка, дефис, подчёркивание, без пути")
        return scripts.kill(name)
    if action is Action.COMMAND:
        text = (arg or "").strip()
        if not text:
            raise PcError("Введите команду PowerShell")
        if len(text) > MAX_COMMAND:
            raise PcError(f"Команда длиннее {MAX_COMMAND} символов")
        return scripts.command(text)
    return {
        Action.INFO: scripts.INFO,
        Action.DISKS: scripts.DISKS,
        Action.FOLDERS: scripts.FOLDERS,
        Action.PROCESSES: scripts.PROCESSES,
        Action.CLEANUP: scripts.CLEANUP,
        Action.GPUPDATE: scripts.GPUPDATE,
        Action.REBOOT: scripts.REBOOT,
    }[action]


def parse_output(stdout: str) -> Any:
    """The JSON document a script printed; PowerShell may put a BOM or blank lines around it."""
    text = stdout.strip().lstrip("\ufeff")
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        # A warning line printed before the JSON: take the last line that parses.
        for line in reversed(text.splitlines()):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue
        raise PcError("Компьютер ответил не тем, что ожидалось: " + text[:300]) from None


def normalize_licences(d: dict[str, Any]) -> dict[str, Any]:
    """Empty keys as None, single items as lists (ConvertTo-Json flattens one-item arrays)."""
    out = dict(d)
    for k in ("windows_bios_key", "windows_registry_key", "user"):
        v = out.get(k)
        out[k] = v.strip() if isinstance(v, str) and v.strip() else None
    for k in ("office_keys", "one_c"):
        v = out.get(k)
        out[k] = [] if v is None else v if isinstance(v, list) else [v]
    out["office_installed"] = bool(out.get("office_installed"))
    return out


def as_list(v: Any) -> list:
    return [] if v is None else v if isinstance(v, list) else [v]


@dataclass
class RealPcs:
    """Runs the scripts over WinRM. pywinrm blocks, so every call goes to a worker thread."""

    settings: Settings
    shell_factory: Callable[[Login], Shell] | None = None
    simulated: bool = False

    def __post_init__(self) -> None:
        self.shell_factory = self.shell_factory or (lambda login: winrm_shell(self.settings, login))

    @property
    def writes_enabled(self) -> bool:
        return self.settings.allow_runbook_writes

    async def run(self, host: str, action: Action, arg: str | None, login: Login | None) -> PcResult:
        if login is None:
            raise PcError(
                "На сервере не задана учётная запись администратора домена: добавьте её в «Учётные данные» "
                "и укажите её имя в RUNBOOK_CREDENTIAL."
            )
        script = script_for(action, arg)
        assert self.shell_factory is not None
        shell = self.shell_factory(login)
        code, out, err = await asyncio.to_thread(shell, host, script)
        if code != 0 and not out.strip():
            return PcResult(False, error=(err.strip() or f"код {code}")[:2000])
        data = parse_output(out)
        if action is Action.INFO and isinstance(data, dict):
            data = normalize_licences(data)
        if action in (Action.DISKS, Action.FOLDERS, Action.PROCESSES):
            data = as_list(data)
        return PcResult(True, data=data)

    async def run_script(self, host: str, script: str, login: Login | None) -> PcResult:
        """A read-only script of another module (gpresult for Group Policy) on one computer."""
        if login is None:
            raise PcError("Не выбрана учётная запись домена: «Настройки сервера» → «Учётная запись домена».")
        assert self.shell_factory is not None
        code, out, err = await asyncio.to_thread(self.shell_factory(login), host, script)
        if code != 0 and not out.strip():
            return PcResult(False, error=(err.strip() or f"код {code}")[:2000])
        return PcResult(True, data=parse_output(out))

    async def keys(self, host: str, login: Login | None) -> PcResult:
        if login is None:
            raise PcError("Не задана учётная запись RUNBOOK_CREDENTIAL")
        assert self.shell_factory is not None
        shell = self.shell_factory(login)
        code, out, err = await asyncio.to_thread(shell, host, scripts.KEYS)
        if code != 0 and not out.strip():
            return PcResult(False, error=(err.strip() or f"код {code}")[:500])
        data = parse_output(out)
        return PcResult(True, data=normalize_licences(data) if isinstance(data, dict) else data)


# --- emulator ------------------------------------------------------------------------------------

# Public generic setup keys, not anyone's licence: obviously not real in the emulator.
_GENERIC_KEYS = ["W269N-WFGWX-YVC9B-4J6C9-T83GX", "NPPR9-FWDCX-D2C8J-H872K-2YT43", "VK7JG-NPHTM-C97JM-9MPGT-3V66T"]
_LIC = "C:\\ProgramData\\1C\\licenses\\20240117.lic"
_USERS = ["FACTORY\\ivanova", "FACTORY\\petrov", "FACTORY\\sidorov", None]


@dataclass
class EmulatedPcs:
    """Answers from a seed made of the host name: the same computer always looks the same."""

    processes_stopped: dict[str, set[str]] = field(default_factory=dict)
    simulated: bool = True
    writes_enabled: bool = True

    @staticmethod
    def _rng(host: str) -> int:
        return int(hashlib.sha256(host.lower().encode()).hexdigest()[:8], 16)

    def _licences(self, host: str) -> dict[str, Any]:
        n = self._rng(host)
        has_office = n % 3 != 0
        return {
            "computer": host.split(".")[0].upper(),
            "user": _USERS[n % len(_USERS)],
            "os": "Майкрософт Windows 11 Pro" if n % 2 else "Майкрософт Windows 10 Pro",
            "windows_bios_key": _GENERIC_KEYS[n % 3] if n % 4 else None,
            "windows_registry_key": _GENERIC_KEYS[(n // 3) % 3],
            "office_installed": has_office,
            "office_keys": [f"{(n % 90000) + 10000:05X}"[:5]] if has_office and n % 5 else [],
            "one_c": [{"path": _LIC, "name": "20240117.lic", "date": "2024-01-17"}] if n % 4 == 1 else [],
        }

    async def run(self, host: str, action: Action, arg: str | None, login: Login | None) -> PcResult:
        script_for(action, arg)  # the same checks as for real
        await asyncio.sleep(0.2)
        n = self._rng(host)
        if action is Action.INFO:
            info = self._licences(host) | {
                "architecture": "64-bit",
                "build": "10.0.22631" if n % 2 else "10.0.19045",
                "uptime_minutes": n % 20000,
                "cpu": ["Intel(R) Core(TM) i5-10400 CPU @ 2.90GHz", "AMD Ryzen 5 5600G"][n % 2],
                "ram_gb": [7.85, 15.85, 31.85][n % 3],
            }
            return PcResult(True, data=info, simulated=True)
        if action is Action.DISKS:
            total = [237.87, 475.8, 931.5][n % 3]
            return PcResult(
                True,
                data=[{"drive": "C:", "label": "", "free_gb": round(total * (n % 60 + 5) / 100, 2), "total_gb": total}],
                simulated=True,
            )
        if action is Action.FOLDERS:
            rows = [("Windows", 28.4), ("Users", 41.2 + n % 30), ("Program Files", 12.1), ("Program Files (x86)", 6.3),
                    ("ProgramData", 3.2), ("$Recycle.Bin", round((n % 70) / 10, 1))]  # fmt: skip
            data = [{"name": a, "size_gb": b} for a, b in sorted(rows, key=lambda r: -r[1]) if b > 0.1]
            return PcResult(True, data=data, simulated=True)
        if action is Action.PROCESSES:
            gone = self.processes_stopped.get(host.lower(), set())
            names = ["chrome", "1cv8c", "Teams", "OUTLOOK", "explorer", "MsMpEng", "svchost", "WINWORD", "EXCEL",
                     "AcroRd32", "dwm", "SearchHost", "OneDrive", "csrss", "RuntimeBroker", "spoolsv", "lsass",
                     "sihost", "taskhostw", "ctfmon"]  # fmt: skip
            data = [{"id": 1000 + i * 37 + n % 31, "name": p, "ram_mb": round(900 / (i + 1) + n % 50, 1)}
                    for i, p in enumerate(names) if p.lower() not in gone]  # fmt: skip
            return PcResult(True, data=data, simulated=True)
        if action is Action.KILL:
            name = (arg or "").strip().removesuffix(".exe")
            known = name.lower() in {"chrome", "1cv8c", "teams", "outlook", "winword", "excel", "acrord32", "onedrive"}
            if known:
                self.processes_stopped.setdefault(host.lower(), set()).add(name.lower())
            return PcResult(True, data={"stopped": 1 if known else 0, "name": name}, simulated=True)
        if action is Action.CLEANUP:
            return PcResult(True, data={"freed_mb": float(300 + n % 4000), "free_gb": 61.4}, simulated=True)
        if action is Action.GPUPDATE:
            return PcResult(
                True,
                data={"output": "Выполняется обновление политики...\n\nОбновление политики для компьютера "
                      "успешно завершено.\nОбновление политики пользователя успешно завершено."},
                simulated=True,
            )  # fmt: skip
        if action is Action.REBOOT:
            return PcResult(True, data={"output": "Компьютер отправлен в перезагрузку (эмуляция)"}, simulated=True)
        # The console: no PowerShell to run here, so it says what would have been run.
        return PcResult(
            True,
            data={"output": f"Эмуляция: на {host} выполнилась бы команда\n{(arg or '').strip()}"},
            simulated=True,
        )

    async def keys(self, host: str, login: Login | None) -> PcResult:
        await asyncio.sleep(0.05)
        return PcResult(True, data=self._licences(host), simulated=True)
