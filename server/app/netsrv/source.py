"""Where the DHCP and DNS data comes from: the real servers (files over SSH, samba-tool over SSH, a zone transfer) or
an emulator that builds sample data from the inventory and says so. Everything is read-only."""

import asyncio
import re
import shlex
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from ..config import Settings
from ..pcs.astra import SshRun, ssh_runner
from ..pcs.backend import PcError
from ..runbooks.real import Login
from .compare import Dev
from .dnsdata import ARecord, parse_samba_query, zone_transfer

PATH = re.compile(r"^/[A-Za-z0-9._/-]{1,200}$")
ZONE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9.-]{0,200}$")
HOST = re.compile(r"^[A-Za-z0-9][A-Za-z0-9.-]{0,200}$")


class NetSrvError(Exception):
    """Something to show the administrator as it is."""


def zones_of(settings: Settings) -> list[str]:
    out = []
    for z in settings.dns_zones.replace(";", ",").split(","):
        z = z.strip().strip(".").lower()
        if z:
            if not ZONE.match(z):
                raise NetSrvError(f"Зона «{z}»: допустимы буквы, цифры, точки и дефисы")
            out.append(z)
    return out


@dataclass
class RealNetServices:
    settings: Settings
    runner_factory: Callable[[Login], SshRun] | None = None
    axfr: Callable[[str, str], list[ARecord]] | None = None
    simulated: bool = False

    def __post_init__(self) -> None:
        self.runner_factory = self.runner_factory or (lambda login: ssh_runner(self.settings, login))
        self.axfr = self.axfr or zone_transfer

    @property
    def dhcp_host(self) -> str:
        return self.settings.dhcp_host.strip()

    @property
    def dns_host(self) -> str:
        return self.settings.dns_host.strip()

    async def _sudo(self, host: str, argv: list[str], login: Login | None) -> str:
        if login is None:
            raise NetSrvError("Не выбрана учётная запись SSH: «Настройки сервера» → «DHCP и DNS».")
        assert self.runner_factory is not None
        command = "sudo -S -p '' -- " + " ".join(shlex.quote(a) for a in argv)
        try:
            code, out, err = await self.runner_factory(login)(host, command, login.password + "\n")
        except PcError as e:
            raise NetSrvError(str(e)) from e
        if code != 0 and not out.strip():
            raise NetSrvError(f"{host}: {(err.strip() or f'код {code}')[:300]}")
        return out

    async def dhcp_texts(self, login: Login | None, inventory: list[Dev]) -> tuple[str, str]:
        host = self.dhcp_host
        if not host:
            raise NetSrvError("Не задан сервер DHCP: «Настройки сервера» → «DHCP и DNS».")
        if not HOST.match(host):
            raise NetSrvError("Сервер DHCP: имя или адрес без пробелов")
        for p in (self.settings.dhcp_conf_path, self.settings.dhcp_leases_path):
            if not PATH.match(p):
                raise NetSrvError(f"Путь «{p}» не подходит")
        conf = await self._sudo(host, ["cat", self.settings.dhcp_conf_path], login)
        leases = await self._sudo(host, ["cat", self.settings.dhcp_leases_path], login)
        return conf, leases

    async def dns_records(self, zone: str, login: Login | None, inventory: list[Dev]) -> list[ARecord]:
        host = self.dns_host
        if not host:
            raise NetSrvError("Не задан сервер DNS: «Настройки сервера» → «DHCP и DNS».")
        if not HOST.match(host) or not ZONE.match(zone):
            raise NetSrvError("Сервер или зона DNS: допустимы буквы, цифры, точки и дефисы")
        if self.settings.dns_method == "axfr":
            assert self.axfr is not None
            try:
                return await asyncio.to_thread(self.axfr, host, zone)
            except Exception as e:
                raise NetSrvError(f"{host}: перенос зоны {zone} не удался ({type(e).__name__}: {e})") from e
        out = await self._sudo(host, ["samba-tool", "dns", "query", "localhost", zone, "@", "A", "-P"], login)
        return parse_samba_query(out)


@dataclass
class EmulatedNetServices:
    """Sample data from the inventory: a pool, fixed hosts for printers, a few leases, a DNS zone with gaps."""

    simulated: bool = True
    dhcp_host: str = "dhcp.emulator"
    dns_host: str = "dns.emulator"

    def _net(self, inventory: list[Dev]) -> str:
        for d in inventory:
            if d.ips and ":" not in d.ips[0]:
                return ".".join(d.ips[0].split(".")[:3])
        return "10.10.10"

    async def dhcp_texts(self, login: Login | None, inventory: list[Dev]) -> tuple[str, str]:
        net = self._net(inventory)
        hosts = "\n".join(
            f"host {d.name.lower()} {{ hardware ethernet 02:00:00:00:{i:02x}:01; fixed-address {d.ips[0]}; }}"
            for i, d in enumerate(d for d in inventory if d.type == "PRINTER" and d.ips)
        )
        conf = (
            f"subnet {net}.0 netmask 255.255.255.0 {{\n  range {net}.200 {net}.240;\n"
            f"  option routers {net}.1;\n  option domain-name-servers {net}.2;\n}}\n{hosts}\n"
        )
        leases = "".join(
            f"lease {net}.{200 + i} {{\n  starts 4 2026/01/01 00:00:00;\n  ends never;\n  binding state active;\n"
            f'  hardware ethernet 02:00:00:00:aa:{i:02x};\n  client-hostname "tablet-{i}";\n}}\n'
            for i in range(6)
        )
        return conf, leases

    async def dns_records(self, zone: str, login: Login | None, inventory: list[Dev]) -> list[ARecord]:
        out = []
        devices = [d for d in inventory if d.ips and d.type not in ("SOCKET", "PATCH_PANEL", "RACK")]
        for i, d in enumerate(devices):
            if i % 7 == 3:
                continue  # no record
            ip = d.ips[0]
            if i % 11 == 5:
                ip = ip.rsplit(".", 1)[0] + ".250"  # wrong address
            out.append(ARecord(d.name.lower(), ip))
        out.append(ARecord("old-printer", "10.10.10.77"))
        out.append(ARecord("@", "10.10.10.2"))
        return out


def configured(real: Any) -> dict[str, bool]:
    return {"dhcp": bool(real.dhcp_host), "dns": bool(real.dns_host)}
