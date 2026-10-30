"""Astra Linux computers (SE 1.7 «Смоленск», joined to the domain with winbind), over SSH.

The same page as for Windows: what the computer is, its disks and processes, cleanup, reboot, the console, plus
what only Linux has here: package updates, who may sudo, samba-gpupdate, and remote help on the user's screen
(x11vnc on the logged-in X display, with a popup where the user accepts the helper).

Each action is a small Python 3 program (Astra 1.7 has 3.7) run as root with sudo: the login's password goes to
sudo on stdin, the program travels base64-encoded in the command line, so neither ends up in a file. It prints one
JSON document, read the same way as the PowerShell scripts' output.
"""

import asyncio
import base64
import json
import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from ..config import Settings
from ..runbooks.real import Login
from .backend import PROCESS, Action, PcError, PcResult, as_list, parse_output

# A local or domain user as getent knows it: ivanova, ECOTEHS\ivanova, ivanova@ecotehs.local.
USER = re.compile(
    r"^(?:[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\)?[A-Za-z0-9._][A-Za-z0-9._$-]{0,63}(?:@[A-Za-z0-9.-]{1,190})?$"
)
MAX_COMMAND = 4000
ASSIST_MINUTES = 2

ASTRA_ACTIONS = {
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
    Action.UPDATE,
    Action.ADMIN_ADD,
    Action.ADMIN_REMOVE,
}

_PRELUDE = r'''
import json, os, re, shutil, subprocess, sys
def sh(cmd, timeout=120):
    p = subprocess.run(cmd, shell=isinstance(cmd, str), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       universal_newlines=True, timeout=timeout)
    return p.returncode, p.stdout
def read(path):
    try:
        with open(path, errors="replace") as f:
            return f.read().strip()
    except OSError:
        return ""
def done(obj):
    print(json.dumps(obj, ensure_ascii=False))
    sys.exit(0)
def fail(text):
    done({"error": text})
def free_gb(path="/"):
    st = os.statvfs(path)
    return round(st.f_bavail * st.f_frsize / 2 ** 30, 2)
def session():
    """The user at the graphical seat and their X display, from who."""
    code, text = sh(["who"])
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        disp = parts[1] if parts[1].startswith(":") else ""
        m = re.search(r"\((:\d+(?:\.\d+)?)\)", line)
        if m:
            disp = m.group(1)
        if disp:
            return parts[0], disp
    return None, ":0"
ARG = json.loads(ARG_JSON)
'''

INFO = r"""
osr = dict(re.findall(r'^(\w+)="?([^"\n]*)"?', read("/etc/os-release"), re.M))
name = osr.get("PRETTY_NAME") or "Linux"
astra = read("/etc/astra_version")
if astra and astra not in name:
    name = name + " (" + astra + ")"
cpu = re.search(r"^model name\s*:\s*(.+)$", read("/proc/cpuinfo"), re.M)
mem = re.search(r"^MemTotal:\s*(\d+)", read("/proc/meminfo"), re.M)
user, disp = session()
admins = []
try:
    import grp
    admins = list(grp.getgrnam("astra-admin").gr_mem)
except KeyError:
    pass
for line in read("/etc/sudoers.d/netadmin-admins").splitlines():
    if line and not line.startswith("#"):
        admins.append(line.split(" ALL=")[0].replace("\\\\", "\\").replace("\\ ", " ").strip('"'))
up = read("/proc/uptime").split()
done({
    "platform": "astra",
    "computer": os.uname().nodename.split(".")[0].upper(),
    "os": name,
    "architecture": os.uname().machine,
    "build": os.uname().release,
    "uptime_minutes": int(float(up[0]) // 60) if up else None,
    "cpu": cpu.group(1).strip() if cpu else None,
    "ram_gb": round(int(mem.group(1)) / 2 ** 20, 2) if mem else None,
    "user": user,
    "display": disp if user else None,
    "admins": sorted(set(admins)),
    "x11vnc": bool(shutil.which("x11vnc")),
    "samba_gpupdate": bool(shutil.which("samba-gpupdate")),
})
"""

DISKS = r"""
REAL = {"ext2", "ext3", "ext4", "xfs", "btrfs", "vfat", "exfat", "ntfs", "ntfs3", "f2fs", "jfs", "reiserfs"}
rows, seen = [], set()
for line in read("/proc/mounts").splitlines():
    dev, mnt, fs = line.split()[:3]
    if fs not in REAL or dev in seen:
        continue
    seen.add(dev)
    mnt = mnt.replace("\\040", " ")
    st = os.statvfs(mnt)
    total = round(st.f_blocks * st.f_frsize / 2 ** 30, 2)
    if total:
        rows.append({"drive": mnt, "label": dev, "free_gb": free_gb(mnt), "total_gb": total})
done(rows)
"""

FOLDERS = r"""
paths = [p for p in ["/home", "/var/log", "/var/cache", "/var/lib", "/usr", "/opt", "/tmp", "/var/tmp", "/root"]
         if os.path.isdir(p)]
paths += [os.path.join("/home", d) for d in sorted(os.listdir("/home"))] if os.path.isdir("/home") else []
rows = []
for p in paths:
    code, text = sh(["du", "-sxb", p], timeout=60)
    m = re.match(r"(\d+)", text)
    if m and int(m.group(1)) >= 0.1 * 2 ** 30:
        rows.append({"name": p, "size_gb": round(int(m.group(1)) / 2 ** 30, 2)})
done(sorted(rows, key=lambda r: -r["size_gb"]))
"""

PROCESSES = r"""
code, text = sh(["ps", "-eo", "pid=,comm=,rss=", "--sort=-rss"])
rows = []
for line in text.splitlines()[:40]:
    parts = line.split()
    if len(parts) >= 3:
        rows.append({"id": int(parts[0]), "name": " ".join(parts[1:-1]), "ram_mb": round(int(parts[-1]) / 1024, 1)})
done(rows)
"""

KILL = r"""
code, text = sh(["pgrep", "-x", ARG])
n = len(text.split())
if n:
    sh(["pkill", "-x", ARG])
done({"stopped": n, "name": ARG})
"""

CLEANUP = r"""
before = free_gb("/")
sh("apt-get clean")
sh("journalctl --vacuum-time=14d")
sh("find /tmp /var/tmp -xdev -type f -atime +7 -delete")
after = free_gb("/")
done({"freed_mb": round(max(after - before, 0) * 1024, 1), "free_gb": after})
"""

GPUPDATE = r"""
if not shutil.which("samba-gpupdate"):
    fail("На компьютере нет samba-gpupdate (пакет python3-samba). Политики Windows на Astra Linux применяет "
         "только он, и только те, что Samba умеет переводить для Linux.")
code, text = sh(["samba-gpupdate", "--force"], timeout=300)
done({"output": text.strip() or ("Готово" if code == 0 else "код " + str(code))})
"""

REBOOT = r"""
code, text = sh(["shutdown", "-r", "+1", "Компьютер будет перезагружен администратором через минуту"])
done({"output": "Перезагрузка через минуту. Пользователь увидел предупреждение." if code == 0 else text})
"""

COMMAND = r"""
code, text = sh(["bash", "-c", ARG], timeout=120)
done({"output": text + ("" if code == 0 else "\n[код выхода " + str(code) + "]")})
"""

UPDATE = r"""
# In the background: an upgrade outlives the SSH session. astra-update knows Astra's repositories and updates the
# way Astra documents; plain apt-get where it is missing.
if shutil.which("astra-update"):
    job = "astra-update -A -r -T"
else:
    job = ("apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y -o Dpkg::Options::=--force-confdef "
           "-o Dpkg::Options::=--force-confold dist-upgrade")
code, text = sh(["systemd-run", "--unit=netadmin-update-" + str(os.getpid()), "--description=NetAdmin: обновление",
                 "bash", "-c", job])
if code != 0:
    fail(text.strip())
done({"output": "Обновление запущено в фоне (" + job.split()[0] + "). Ход: journalctl -u netadmin-update-"
      + str(os.getpid()) + ". Перезагрузка — отдельно, когда пользователь закончит работу."})
"""

ADMINS = r"""
import pwd, tempfile
FILE = "/etc/sudoers.d/netadmin-admins"
try:
    name = pwd.getpwnam(ARG["user"]).pw_name
except KeyError:
    fail("Пользователя " + ARG["user"] + " нет на компьютере (getent passwd): проверьте имя и winbind.")
esc = name.replace("\\", "\\\\").replace(" ", "\\ ")
lines = [l for l in read(FILE).splitlines() if l.strip() and not l.startswith(esc + " ALL=")]
if ARG["add"]:
    lines.append(esc + " ALL=(ALL:ALL) ALL")
head = "# NetAdmin: users given sudo from the console. Edited by the server, one per line.\n"
fd, tmp = tempfile.mkstemp(dir="/etc/sudoers.d", prefix=".netadmin-")
with os.fdopen(fd, "w") as f:
    f.write(head + "".join(l + "\n" for l in lines if not l.startswith("#")))
os.chmod(tmp, 0o440)
code, text = sh(["visudo", "-cf", tmp])
if code != 0:
    os.unlink(tmp)
    fail("visudo отверг файл: " + text.strip())
os.rename(tmp, FILE)
done({"output": ("Выдан sudo: " if ARG["add"] else "Снят sudo: ") + name, "user": name})
"""

ASSIST = r"""
import random, socket, string, tempfile
if not shutil.which("x11vnc"):
    fail("На компьютере нет x11vnc: установите его из репозитория Astra (sudo apt install x11vnc) и повторите.")
user, disp = session()
if not user:
    fail("На компьютере никто не вошёл в графический сеанс — помогать некому.")
port = None
for p in range(5900, 5911):
    s = socket.socket()
    try:
        s.bind(("0.0.0.0", p))
        port = p
        break
    except OSError:
        continue
    finally:
        s.close()
if port is None:
    fail("Порты 5900–5910 заняты: уже идёт сеанс помощи?")
pw = "".join(random.SystemRandom().choice(string.ascii_letters + string.digits) for _ in range(8))
fd, pwfile = tempfile.mkstemp(prefix=".netadmin-vnc-")
with os.fdopen(fd, "w") as f:
    f.write(pw + "\n")
cmd = ["x11vnc", "-display", disp, "-auth", "guess", "-rfbport", str(port), "-passwdfile", "rm:" + pwfile,
       "-once", "-timeout", str(TIMEOUT), "-accept", "popup", "-noxdamage", "-quiet", "-bg",
       "-o", "/var/log/netadmin-x11vnc.log"]
if ARG.get("view_only"):
    cmd.append("-viewonly")
code, text = sh(cmd, timeout=30)
if code != 0:
    if os.path.exists(pwfile):
        os.unlink(pwfile)
    fail("x11vnc не запустился: " + (text.strip() or read("/var/log/netadmin-x11vnc.log")[-500:]))
done({"method": "vnc", "port": port, "password": pw, "display": disp, "user": user,
      "view_only": bool(ARG.get("view_only"))})
"""


def check_user(arg: str | None) -> str:
    u = (arg or "").strip()
    if not USER.match(u):
        raise PcError("Пользователь: ivanova, ECOTEHS\\ivanova или ivanova@ecotehs.local")
    return u


def program(action: Action, arg: str | None) -> str:
    """The Python program for an action, with its argument; PcError for a bad argument or a Windows-only action."""
    body: str
    value: Any = None
    if action is Action.KILL:
        name = (arg or "").strip()
        if not PROCESS.match(name):
            raise PcError("Имя процесса: буквы, цифры, точка, дефис, подчёркивание, без пути")
        body, value = KILL, name
    elif action is Action.COMMAND:
        text = (arg or "").strip()
        if not text:
            raise PcError("Введите команду bash")
        if len(text) > MAX_COMMAND:
            raise PcError(f"Команда длиннее {MAX_COMMAND} символов")
        body, value = COMMAND, text
    elif action in (Action.ADMIN_ADD, Action.ADMIN_REMOVE):
        body, value = ADMINS, {"user": check_user(arg), "add": action is Action.ADMIN_ADD}
    elif action is Action.ASSIST:
        body = ASSIST.replace("TIMEOUT", str(ASSIST_MINUTES * 60))
        value = {"view_only": (arg or "").strip() == "view"}
    else:
        table = {
            Action.INFO: INFO,
            Action.DISKS: DISKS,
            Action.FOLDERS: FOLDERS,
            Action.PROCESSES: PROCESSES,
            Action.CLEANUP: CLEANUP,
            Action.GPUPDATE: GPUPDATE,
            Action.REBOOT: REBOOT,
            Action.UPDATE: UPDATE,
        }
        if action not in table:
            raise PcError("Для Astra Linux этого действия нет")
        body = table[action]
    return f"ARG_JSON = {json.dumps(json.dumps(value))}\n{_PRELUDE}\n{body}"


def sudo_command(source: str) -> str:
    """The command line: sudo reads the password from stdin, the program comes base64-encoded."""
    b64 = base64.b64encode(source.encode()).decode()
    return f"sudo -S -p '' -- python3 -c 'import base64;exec(base64.b64decode(\"{b64}\").decode())'"


# (host, command, stdin) -> (exit code, stdout, stderr)
SshRun = Callable[[str, str, str], Awaitable[tuple[int, str, str]]]


def ssh_runner(settings: Settings, login: Login) -> SshRun:
    async def run(host: str, command: str, stdin: str) -> tuple[int, str, str]:
        import asyncssh

        try:
            async with asyncssh.connect(
                host,
                port=settings.linux_ssh_port,
                username=login.username,
                password=login.password,
                known_hosts=None,
                connect_timeout=15,
                client_keys=None,
            ) as conn:
                r = await conn.run(command, input=stdin, timeout=330)
                return r.exit_status or 0, str(r.stdout or ""), str(r.stderr or "")
        except asyncssh.PermissionDenied as e:
            raise PcError(
                f"{host}: SSH не пустил {login.username} — проверьте пароль и что вход по SSH разрешён"
            ) from e
        except asyncssh.TimeoutError as e:
            raise PcError(f"{host}: команда не закончилась за 5 минут") from e
        except (OSError, asyncssh.Error) as e:
            raise PcError(f"{host}: нет связи по SSH (порт {settings.linux_ssh_port}): {e}") from e

    return run


def _sudo_error(err: str) -> str:
    low = err.lower()
    if "not in the sudoers" in low or "is not allowed to" in low or "нет в файле sudoers" in low:
        return "Учётной записи не разрешён sudo на этом компьютере: добавьте её в astra-admin или в sudoers."
    if "incorrect password" in low or "неверный пароль" in low or "sorry, try again" in low:
        return "sudo не принял пароль учётной записи."
    return err.strip()[:2000]


@dataclass
class AstraPcs:
    """Runs the programs over SSH with sudo."""

    settings: Settings
    runner_factory: Callable[[Login], SshRun] | None = None
    simulated: bool = False

    def __post_init__(self) -> None:
        self.runner_factory = self.runner_factory or (lambda login: ssh_runner(self.settings, login))

    @property
    def writes_enabled(self) -> bool:
        return self.settings.allow_runbook_writes

    async def run(self, host: str, action: Action, arg: str | None, login: Login | None) -> PcResult:
        if login is None:
            raise PcError(
                "Не задана учётная запись для Astra Linux: «Настройки сервера» → «Учётная запись для Astra Linux» "
                "(или «Учётная запись домена»)."
            )
        source = program(action, arg)
        assert self.runner_factory is not None
        code, out, err = await self.runner_factory(login)(host, sudo_command(source), login.password + "\n")
        if not out.strip():
            return PcResult(False, error=_sudo_error(err) or f"код {code}")
        data = parse_output(out)
        if isinstance(data, dict) and data.get("error"):
            return PcResult(False, error=str(data["error"]))
        if action in (Action.DISKS, Action.FOLDERS, Action.PROCESSES):
            data = as_list(data)
        if action is Action.ASSIST and isinstance(data, dict):
            data = assist_out(host, data)
        return PcResult(True, data=data)


def assist_out(host: str, data: dict) -> dict:
    port = int(data.get("port") or 5900)
    return {
        **data,
        "host": host,
        "address": f"{host}:{port - 5900}" if 5900 <= port < 6000 else f"{host}::{port}",
        "url": f"vnc://{host}:{port}",
        "expires_minutes": ASSIST_MINUTES,
    }


# --- emulator ------------------------------------------------------------------------------------


async def emulated(host: str, action: Action, arg: str | None, n: int) -> PcResult:
    """Made-up answers for an Astra computer of the emulated network ([n]: the host's seed)."""
    program(action, arg)  # the same checks as for real
    await asyncio.sleep(0.2)
    user = ["ivanova", "petrov", "sidorov", None][n % 4]
    if action is Action.INFO:
        return PcResult(True, data={
            "platform": "astra", "computer": host.split(".")[0].upper(),
            "os": "Astra Linux Special Edition 1.7 «Смоленск» (SE 1.7.6)", "architecture": "x86_64",
            "build": "6.1.90-1-generic", "uptime_minutes": n % 20000,
            "cpu": ["Intel(R) Core(TM) i5-10400 CPU @ 2.90GHz", "Байкал-М"][n % 2], "ram_gb": [7.65, 15.6][n % 2],
            "user": user, "display": ":0" if user else None, "admins": ["ECOTEHS\\kharca01"],
            "x11vnc": True, "samba_gpupdate": True,
        }, simulated=True)  # fmt: skip
    if action is Action.DISKS:
        return PcResult(True, data=[{"drive": "/", "label": "/dev/sda1", "free_gb": 61.2, "total_gb": 117.5}],
                        simulated=True)  # fmt: skip
    if action is Action.FOLDERS:
        rows = [("/usr", 9.8), ("/home", 22.4), (f"/home/{user or 'user'}", 21.9), ("/var/cache", 1.2)]
        return PcResult(True, data=[{"name": a, "size_gb": b} for a, b in sorted(rows, key=lambda r: -r[1])],
                        simulated=True)  # fmt: skip
    if action is Action.PROCESSES:
        names = ["firefox", "fly-wm", "Xorg", "soffice.bin", "1cv8c", "thunderbird", "fly-fm", "systemd", "winbindd"]
        return PcResult(True, data=[{"id": 900 + i * 41, "name": p, "ram_mb": round(800 / (i + 1), 1)}
                                    for i, p in enumerate(names)], simulated=True)  # fmt: skip
    if action is Action.KILL:
        return PcResult(True, data={"stopped": 1, "name": (arg or "").strip()}, simulated=True)
    if action is Action.CLEANUP:
        return PcResult(True, data={"freed_mb": float(200 + n % 900), "free_gb": 61.4}, simulated=True)
    if action is Action.ASSIST:
        if not user:
            return PcResult(False, error="На компьютере никто не вошёл в графический сеанс — помогать некому.",
                            simulated=True)  # fmt: skip
        data = {"method": "vnc", "port": 5900, "password": "Emu1ated", "display": ":0", "user": user,
                "view_only": (arg or "") == "view"}  # fmt: skip
        return PcResult(True, data=assist_out(host, data), simulated=True)
    if action in (Action.ADMIN_ADD, Action.ADMIN_REMOVE):
        u = check_user(arg)
        return PcResult(True, data={"output": ("Выдан sudo: " if action is Action.ADMIN_ADD else "Снят sudo: ") + u,
                                    "user": u}, simulated=True)  # fmt: skip
    outputs = {
        Action.GPUPDATE: "samba-gpupdate --force: применено (эмуляция)",
        Action.REBOOT: "Перезагрузка через минуту (эмуляция)",
        Action.UPDATE: "Обновление запущено в фоне (astra-update) — эмуляция",
    }
    return PcResult(True, data={"output": outputs.get(action, f"Эмуляция: на {host} выполнилась бы команда\n"
                                                              f"{(arg or '').strip()}")}, simulated=True)  # fmt: skip


@dataclass
class EmulatedAstra:
    """Astra computers of the emulated network: a name with «astra», «lnx» or «linux» in it."""

    simulated: bool = True
    writes_enabled: bool = True

    async def run(self, host: str, action: Action, arg: str | None, login: Login | None) -> PcResult:
        import hashlib

        return await emulated(host, action, arg, int(hashlib.sha256(host.lower().encode()).hexdigest()[:8], 16))
