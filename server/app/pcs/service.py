"""Which computers the server talks to, the subnet sweep (menu item 1 of the script) and the key inventory
(menu item 3): Windows, Office and 1C licences collected from many computers at once, with a CSV for Excel."""

import asyncio
import csv
import io
import ipaddress
import socket
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import Settings
from ..discovery.model import InvalidScan, parse_subnets
from ..models import Device
from ..runbooks.real import LdapDirectory, Login, RunbookConfigError
from .backend import EmulatedPcs, PcError, RealPcs

Pinger = Callable[[str], Awaitable[bool]]

# Kinds that can be a Windows computer; cameras, printers and switches never get the domain login.
PC_TYPES = {"WORKSTATION", "SERVER", "VIRTUAL", "UNKNOWN"}
SWEEP_CONCURRENCY = 64
KEYS_CONCURRENCY = 8
MAX_JOBS = 5


def subnet_of(prefix: str) -> ipaddress.IPv4Network:
    """«192.168.82» (the script's way), «192.168.82.0/24» or any private network up to a /22."""
    text = prefix.strip().rstrip(".")
    if text.count(".") == 2 and "/" not in text:
        text += ".0/24"
    try:
        return parse_subnets([text])[0]
    except InvalidScan as e:
        raise PcError(str(e)) from e


async def reverse_dns(ip: str, timeout: float = 2.0) -> str | None:
    try:
        name, _, _ = await asyncio.wait_for(asyncio.to_thread(socket.gethostbyaddr, ip), timeout)
    except (OSError, TimeoutError):
        return None
    return name or None


async def inventory_pcs(session: AsyncSession) -> list[Device]:
    rows = await session.scalars(select(Device).where(Device.device_type.in_(PC_TYPES), Device.is_active.is_(True)))
    return list(rows.all())


def matches(device: Device, host: str) -> bool:
    h = host.lower()
    name = device.name.lower()
    ips = {str(ip) for ip in [device.ip_address, *(device.extra_ips or [])] if ip is not None}
    return name == h or name == h.split(".")[0] or h in ips


PLATFORM_TTL = 600.0


async def port_open(host: str, port: int, timeout: float = 2.5) -> bool:
    try:
        _, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout)
    except (OSError, TimeoutError):
        return False
    writer.close()
    return True


async def detect_platform(
    host: str,
    settings: Settings,
    emulated: bool,
    cache: dict[str, tuple[str, float]],
    probe: Callable[[str, int], Awaitable[bool]] = port_open,
) -> str:
    """«windows» when WinRM answers, «astra» when only SSH does; remembered for ten minutes."""
    import re
    import time

    key = host.lower()
    hit = cache.get(key)
    if hit and time.monotonic() - hit[1] < PLATFORM_TTL:
        return hit[0]
    if emulated:
        platform = "astra" if re.search(r"astra|lnx|linux", host, re.IGNORECASE) else "windows"
    else:
        winrm_port = settings.winrm_port or (5986 if settings.winrm_https else 5985)
        winrm, ssh = await asyncio.gather(probe(host, winrm_port), probe(host, settings.linux_ssh_port))
        if winrm:
            platform = "windows"
        elif ssh:
            platform = "astra"
        else:
            raise PcError(
                f"{host} не отвечает ни по WinRM (порт {winrm_port}), ни по SSH (порт {settings.linux_ssh_port}): "
                "выключен, не в сети или порты закрыты брандмауэром"
            )
    cache[key] = (platform, time.monotonic())
    return platform


async def allowed_host(session: AsyncSession, settings: Settings, login: Login | None, host: str) -> bool:
    """The domain login goes only to a computer the plant knows: one in the inventory, or one in AD."""
    if any(matches(d, host) for d in await inventory_pcs(session)):
        return True
    if settings.ad_ldap_url.strip() and login is not None:
        try:
            return await asyncio.to_thread(lambda: LdapDirectory(settings, login).computer_exists(host))
        except RunbookConfigError:
            return False
    return False


@dataclass
class SweepHit:
    ip: str
    name: str | None
    device_id: uuid.UUID | None = None
    device_name: str | None = None


async def sweep(session: AsyncSession, prefix: str, pinger: Pinger | None, emulated: bool) -> list[SweepHit]:
    """Ping every address of the subnet, then ask DNS for the names of those that answered. Read-only."""
    net = subnet_of(prefix)
    pcs = await inventory_pcs(session)
    if emulated or pinger is None:
        # No network here: the inventory's computers in that subnet answer.
        hits = [
            SweepHit(str(d.ip_address), f"{d.name.lower()}.factory.local", d.id, d.name)
            for d in pcs
            if d.ip_address is not None and ipaddress.ip_address(str(d.ip_address)) in net
        ]
        return sorted(hits, key=lambda h: ipaddress.ip_address(h.ip))
    sem = asyncio.Semaphore(SWEEP_CONCURRENCY)

    async def probe(ip: str) -> SweepHit | None:
        async with sem:
            if not await pinger(ip):
                return None
            name = await reverse_dns(ip)
        known = next((d for d in pcs if d.ip_address is not None and str(d.ip_address) == ip), None)
        return SweepHit(ip, name, known.id if known else None, known.name if known else None)

    found = await asyncio.gather(*(probe(str(h)) for h in net.hosts()))
    return [h for h in found if h is not None]


# --- key inventory ---------------------------------------------------------------------------------


@dataclass
class KeyRow:
    host: str
    ok: bool
    error: str = ""
    data: dict[str, Any] = field(default_factory=dict)


@dataclass
class KeyJob:
    id: uuid.UUID
    source: str
    started_at: datetime
    status: str = "running"  # running, done, failed
    stage: str = "Список компьютеров"
    total: int = 0
    online: int = 0
    done: int = 0
    rows: list[KeyRow] = field(default_factory=list)
    error: str = ""
    simulated: bool = False
    finished_at: datetime | None = None


class KeyInventory:
    """Jobs in memory: the last few, newest first. A server restart forgets them (the CSV is the record)."""

    def __init__(self) -> None:
        self.jobs: dict[uuid.UUID, KeyJob] = {}
        self._tasks: set[asyncio.Task] = set()

    def get(self, job_id: uuid.UUID) -> KeyJob | None:
        return self.jobs.get(job_id)

    def start(self, job: KeyJob, work: Awaitable[None]) -> None:
        self.jobs[job.id] = job
        for old in sorted(self.jobs.values(), key=lambda j: j.started_at)[:-MAX_JOBS]:
            self.jobs.pop(old.id, None)
        task = asyncio.create_task(work)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)


async def run_key_job(
    job: KeyJob,
    hosts: list[str],
    backend: RealPcs | EmulatedPcs,
    login: Login | None,
    pinger: Pinger | None,
    allowed: Callable[[str], Awaitable[bool]],
) -> None:
    try:
        job.total = len(hosts)
        job.stage = "Проверка, какие компьютеры включены"
        if pinger is not None and not job.simulated:
            sem = asyncio.Semaphore(SWEEP_CONCURRENCY)

            async def up(h: str) -> bool:
                async with sem:
                    return await pinger(h)

            alive = await asyncio.gather(*(up(h) for h in hosts))
            hosts = [h for h, a in zip(hosts, alive, strict=True) if a]
        job.online = len(hosts)
        job.stage = "Сбор лицензий"
        sem = asyncio.Semaphore(KEYS_CONCURRENCY)

        async def collect(h: str) -> None:
            async with sem:
                if not await allowed(h):
                    row = KeyRow(h, False, "пропущен: компьютера нет ни в учёте, ни в AD")
                else:
                    try:
                        r = await backend.keys(h, login)
                        row = KeyRow(h, r.ok, r.error, r.data if isinstance(r.data, dict) else {})
                    except PcError as e:
                        row = KeyRow(h, False, str(e))
                    except Exception as e:  # one computer must not stop the round
                        row = KeyRow(h, False, f"{type(e).__name__}: {e}")
            job.rows.append(row)
            job.done += 1

        await asyncio.gather(*(collect(h) for h in hosts))
        job.rows.sort(key=lambda r: (not r.ok, r.host.lower()))
        job.status = "done"
        job.stage = "Готово"
    except Exception as e:
        job.status = "failed"
        job.error = str(e) or type(e).__name__
    finally:
        job.finished_at = datetime.now(UTC)


def office_text(d: dict[str, Any]) -> str:
    if not d.get("office_installed"):
        return "Нет"
    keys = d.get("office_keys") or []
    return ", ".join(f"*****-{k}" for k in keys) if keys else "Office 365 / Без ключа"


def one_c_text(d: dict[str, Any]) -> str:
    files = d.get("one_c") or []
    return ", ".join(f.get("name", "") for f in files if isinstance(f, dict)) or "Нет"


CSV_HEADER = [
    "Имя ПК", "Пользователь", "ОС", "Windows (BIOS)", "Windows (Реестр)", "MS Office", "Лицензии 1С", "Ошибка",
]  # fmt: skip


def to_csv(job: KeyJob) -> bytes:
    """As the script exported it: «;» between columns, UTF-8 with a BOM so Excel opens it right."""
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=";", lineterminator="\r\n")
    w.writerow(CSV_HEADER)
    for r in job.rows:
        d = r.data
        w.writerow([
            d.get("computer") or r.host,
            d.get("user") or ("Нет активного сеанса" if r.ok else ""),
            d.get("os") or "",
            d.get("windows_bios_key") or ("Нет" if r.ok else ""),
            d.get("windows_registry_key") or ("Нет" if r.ok else ""),
            office_text(d) if r.ok else "",
            one_c_text(d) if r.ok else "",
            r.error,
        ])  # fmt: skip
    return ("﻿" + buf.getvalue()).encode("utf-8")
