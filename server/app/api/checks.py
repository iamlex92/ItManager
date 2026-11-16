"""«Проверить» in the server settings: does the server reach what the settings point at, right now.

POST /server-settings/check/{what}   what: switch, ldap, winrm, dhcp, dns

Each check uses the settings the server runs with (saved and restarted), does one small read and says in a sentence
what it got or why it failed. Nothing is changed anywhere. In emulation there is nothing to reach, and the answer
says so.
"""

import asyncio
from typing import Literal

from fastapi import APIRouter, Request
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from ..gpo.backend import gpo_host
from ..hal.base import SwitchError
from ..models import Device
from ..netsrv.dhcpd import parse_conf, parse_leases
from ..netsrv.source import NetSrvError, zones_of
from ..pcs.backend import PcError
from ..runbooks.real import LdapDirectory, RunbookConfigError
from ..services import switches
from .deps import DriverDep, SessionDep
from .netsrv import _inventory, _login_for
from .runbooks import runbook_login

router = APIRouter(prefix="/server-settings/check", tags=["settings"])

TIMEOUT = 40.0
What = Literal["switch", "ldap", "winrm", "dhcp", "dns"]


def _ok(text: str, simulated: bool = False) -> dict:
    return {"ok": True, "text": text, "simulated": simulated}


def _bad(text: str) -> dict:
    return {"ok": False, "text": text, "simulated": False}


async def _switch(request: Request, session, driver) -> dict:
    s = request.app.state.settings
    if s.hal_mode.value != "real":
        return _ok("Эмуляция: на коммутаторы ничего не уходит, проверять нечего.", True)
    rows = (
        await session.scalars(
            select(Device)
            .where(Device.device_type == "SWITCH", Device.is_active.is_(True))
            .options(selectinload(Device.ports))
        )
    ).all()
    sw = next((d for d in rows if switches.is_managed_switch(d) and d.ip_address is not None), None)
    if sw is None:
        return _bad("В учёте нет коммутатора Cisco или Eltex с IP-адресом: проверить не на чем.")
    try:
        await driver.get_poe(switches.device_target(sw))
    except SwitchError as e:
        return _bad(f"{sw.name}: {e}")
    return _ok(f"{sw.name} ({sw.ip_address}): вход по SSH выполнен, команда прошла.")


async def _ldap(request: Request, session) -> dict:
    s = request.app.state.settings
    if not s.pcs_real:
        return _ok("Эмуляция: к домену сервер не обращается, проверять нечего.", True)
    login = await runbook_login(session, s)
    if login is None:
        return _bad("Не выбрана учётная запись домена.")
    try:
        d = await asyncio.to_thread(LdapDirectory, s, login)
    except RunbookConfigError as e:
        return _bad(str(e))
    return _ok(f"Вход в домен выполнен, корень поиска: {d.base_dn}.")


async def _winrm(request: Request, session) -> dict:
    s = request.app.state.settings
    if not s.pcs_real:
        return _ok("Эмуляция: по WinRM сервер не ходит, проверять нечего.", True)
    host = gpo_host(s)
    if not host:
        return _bad("Не задан ни контроллер домена, ни сервер GPMC: не к кому подключаться.")
    try:
        r = await request.app.state.pcs.run_script(
            host, "@{ name = $env:COMPUTERNAME } | ConvertTo-Json -Compress", await runbook_login(session, s)
        )
    except PcError as e:
        return _bad(f"{host}: {e}")
    if not r.ok:
        return _bad(f"{host}: {r.error}")
    name = r.data.get("name") if isinstance(r.data, dict) else None
    return _ok(f"{host}: WinRM отвечает" + (f", компьютер {name}." if name else "."))


async def _dhcp(request: Request, session) -> dict:
    svc = request.app.state.netsrv
    try:
        conf, leases = await svc.dhcp_texts(
            await _login_for(session, request.app.state.settings), await _inventory(session)
        )
    except NetSrvError as e:
        return _bad(str(e))
    scopes, hosts = parse_conf(conf)
    text = f"{svc.dhcp_host}: файлы прочитаны, областей {len(scopes)}, закреплённых адресов {len(hosts)}, "
    return _ok(text + f"активных аренд {len(parse_leases(leases))}.", svc.simulated)


async def _dns(request: Request, session) -> dict:
    svc = request.app.state.netsrv
    s = request.app.state.settings
    try:
        zones = zones_of(s) or (["factory.local"] if svc.simulated else [])
        if not zones:
            return _bad("Не заданы зоны DNS.")
        records = await svc.dns_records(zones[0], await _login_for(session, s), await _inventory(session))
    except NetSrvError as e:
        return _bad(str(e))
    return _ok(f"{svc.dns_host}: зона {zones[0]} прочитана, записей A {len(records)}.", svc.simulated)


@router.post("/{what}")
async def check(what: What, request: Request, session: SessionDep, driver: DriverDep):
    work = {
        "switch": lambda: _switch(request, session, driver),
        "ldap": lambda: _ldap(request, session),
        "winrm": lambda: _winrm(request, session),
        "dhcp": lambda: _dhcp(request, session),
        "dns": lambda: _dns(request, session),
    }[what]
    try:
        return await asyncio.wait_for(work(), TIMEOUT)
    except TimeoutError:
        return _bad(f"Нет ответа за {int(TIMEOUT)} секунд.")
    except Exception as e:  # a check never breaks the page: the reason is the answer
        return _bad(f"{type(e).__name__}: {e}")
