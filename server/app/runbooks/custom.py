"""Running a custom script for real: on a domain computer (Windows over WinRM, Astra Linux over SSH with sudo) or on
the NetAdmin server itself.

Parameters never become code: each {{name}} is replaced by the value as a quoted string literal of the script's
language (PowerShell and bash single quotes, a Python string), and cmd, which has no safe quoting, takes only plain
values. So `Get-ADUser {{login}}` works and a value like `x; Remove-Item C:\\ -Recurse` stays a string.

The script travels base64-encoded and is written to a temporary file where it runs (cmd and Python on Windows,
bash and Python on Linux), so its own quotes and line breaks never meet the command line.
"""

import asyncio
import base64
import json
import os
import re
import shutil
import sys
import tempfile

from .emulator import RunResult

PLACEHOLDER = re.compile(r"\{\{\s*([a-z][a-z0-9_]{0,30})\s*\}\}")
# What cmd can take unquoted without its metacharacters (& | < > ^ % " and line breaks).
CMD_SAFE = re.compile(r"^[A-Za-z0-9А-Яа-яЁё _.,:@\\/+=#-]*$")
MAX_OUTPUT = 100_000
# WinRM sends the script on a command line (powershell -EncodedCommand): about this much fits after encoding.
MAX_WINDOWS = {"powershell": 8000, "cmd": 4000, "python": 4000}
SERVER_TIMEOUT = 300
SHELL_NAMES = {"powershell": "PowerShell", "bash": "bash", "cmd": "cmd.exe", "python": "Python"}
# Where each language can run.
WINDOWS = {"powershell", "cmd", "python"}
LINUX = {"bash", "python"}


class ScriptError(ValueError):
    """A run refused before anything is sent: a value cmd cannot take, a language the computer has no shell for."""


def quote(shell: str, value: str) -> str:
    if shell == "powershell":
        return "'" + value.replace("'", "''") + "'"
    if shell == "bash":
        return "'" + value.replace("'", "'\\''") + "'"
    if shell == "python":
        return json.dumps(value, ensure_ascii=False)
    if not CMD_SAFE.match(value):
        raise ScriptError(f"Значение «{value[:40]}» нельзя безопасно подставить в cmd: уберите & | < > ^ % и кавычки")
    return value


def render(shell: str, body: str, params: dict[str, str]) -> str:
    return PLACEHOLDER.sub(lambda m: quote(shell, params.get(m.group(1), "")), body)


def _b64(text: str) -> str:
    return base64.b64encode(text.encode("utf-8")).decode()


def windows_script(shell: str, source: str) -> str:
    """The PowerShell sent over WinRM: the script itself, or a temporary .cmd / .py file run and removed."""
    head = (
        "$ErrorActionPreference = 'Continue'\n[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n"
        f"$src = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('{_b64(source)}'))\n"
    )
    if shell == "powershell":
        # As it is: run_ps encodes it once more, and every byte counts towards the command line's limit.
        return (
            "$ErrorActionPreference = 'Continue'\n[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n" + source
        )
    ext = "cmd" if shell == "cmd" else "py"
    # Windows PowerShell 5.1 (what WinRM runs): no ?? operator.
    run = (
        "cmd.exe /c $f 2>&1"
        if shell == "cmd"
        else "$py = Get-Command py -ErrorAction SilentlyContinue\n"
        "if (-not $py) { $py = Get-Command python -ErrorAction SilentlyContinue }\n"
        "if (-not $py) { 'На компьютере нет Python (py или python в PATH)'; exit 9009 }\n"
        "& $py.Source $f 2>&1"
    )
    # cmd reads its file in the OEM code page, Python in UTF-8 without a BOM.
    encoding = (
        "[System.Text.Encoding]::GetEncoding((Get-Culture).TextInfo.OEMCodePage)"
        if shell == "cmd"
        else "(New-Object System.Text.UTF8Encoding $false)"
    )
    return head + (
        f"$f = Join-Path $env:TEMP ('netadmin-' + [guid]::NewGuid().ToString('N') + '.{ext}')\n"
        f"[System.IO.File]::WriteAllText($f, $src, {encoding})\n"
        "try {\n" + run + "\n$code = $LASTEXITCODE\n} finally { Remove-Item $f -Force -ErrorAction SilentlyContinue }\n"
        "exit $code\n"
    )


def linux_command(shell: str, source: str) -> str:
    """The SSH command line: sudo reads the password from stdin, the script comes base64-encoded."""
    b64 = _b64(source)
    if shell == "python":
        return f"sudo -S -p '' -- python3 -c 'import base64;exec(base64.b64decode(\"{b64}\").decode())'"
    return f"sudo -S -p '' -- bash -c 'eval \"$(printf %s {b64} | base64 -d)\"'"


async def run_on_server(shell: str, source: str) -> RunResult:
    """On the NetAdmin server, as the service's own user: Python with the server's interpreter, bash where it is."""
    if shell == "python":
        exe = [sys.executable]
        suffix = ".py"
    elif shell == "bash" and shutil.which("bash"):
        exe = [shutil.which("bash") or "bash"]
        suffix = ".sh"
    else:
        raise ScriptError(f"На сервере NetAdmin {SHELL_NAMES.get(shell, shell)} не запускается: только Python и bash")
    fd, path = tempfile.mkstemp(prefix="netadmin-script-", suffix=suffix)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(source)
        proc = await asyncio.create_subprocess_exec(
            *exe, path, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
        )
        try:
            out, err = await asyncio.wait_for(proc.communicate(), SERVER_TIMEOUT)
        except TimeoutError:
            proc.kill()
            await proc.wait()
            return RunResult(exit_code=124, stdout="", stderr=f"Скрипт не закончился за {SERVER_TIMEOUT // 60} минут")
        return RunResult(
            exit_code=proc.returncode or 0,
            stdout=out.decode("utf-8", "replace")[:MAX_OUTPUT],
            stderr=err.decode("utf-8", "replace")[:MAX_OUTPUT],
        )
    finally:
        os.unlink(path)
