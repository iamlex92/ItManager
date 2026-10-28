"""The driver for real Cisco IOS and Eltex MES switches over SSH.

Reads: `show interfaces status` for link and speed, `show running-config` for mode, VLANs and shutdown (the same
parser as the config import), `show vlan brief` for the VLAN list of «allowed all» trunks, `show power inline` for
PoE. Writes send the commands of app/hal/commands.py, the same text the preview shows. It is only ever used wrapped
in WriteGuard, so nothing is changed unless ALLOW_DEVICE_WRITES=true.
"""

import asyncio
import contextlib
import ipaddress
import re
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass

from ..cliimport.parse import PHYSICAL, if_family, normalize_if_name, parse_running_config, parse_vlan_brief
from . import commands
from .base import DeviceTarget, DiagResult, PortConfig, PortRef, PortStatus, SwitchDriver, SwitchError
from .ssh import CliSession, SshOptions, SwitchLogin, cli_error, open_cli

# Credential id of the device (None: the server default) -> its login, or None when there is none.
LoginResolver = Callable[[uuid.UUID | None], Awaitable[SwitchLogin | None]]
Opener = Callable[[str, SwitchLogin, str, SshOptions], contextlib.AbstractAsyncContextManager[CliSession]]

# The pause between shutdown and no shutdown of a port bounce.
BOUNCE_PAUSE_SECONDS = 2.0


@dataclass(frozen=True)
class LinkState:
    up: bool
    speed_mbps: int | None
    # Cisco only: `disabled` (shutdown) in the Status column, and the Vlan column (a number, trunk or routed).
    admin_down: bool = False
    vlan_column: str = ""


def parse_speed(text: str) -> int | None:
    """a-1000, 1000, 100M, 10G, a-10G -> Mbit/s; auto and -- -> None."""
    m = re.match(r"^(?:a-)?(\d+(?:\.\d+)?)\s*([gm])?", text.strip(), re.IGNORECASE)
    if m is None:
        return None
    value = float(m.group(1))
    return int(value * 1000) if (m.group(2) or "").lower() == "g" else int(value)


def _is_port(token: str) -> bool:
    return if_family(normalize_if_name(token)) in PHYSICAL and bool(re.search(r"\d", token))


def parse_cisco_status(text: str) -> dict[str, LinkState]:
    """IOS `show interfaces status`. The Name column may hold spaces, so columns are cut at the header."""
    out: dict[str, LinkState] = {}
    status_at = -1
    for line in text.splitlines():
        if re.match(r"^\s*Port\s+Name\s+Status\s+Vlan", line):
            status_at = line.index("Status")
            continue
        words = line.split()
        if not words or not _is_port(words[0]):
            continue
        rest = line[status_at:].split() if 0 <= status_at < len(line) else words[1:]
        if len(rest) < 4:
            continue
        status, vlan, speed = rest[0].lower(), rest[1].lower(), rest[3]
        out[normalize_if_name(words[0])] = LinkState(
            up=status == "connected",
            speed_mbps=parse_speed(speed),
            admin_down=status == "disabled",
            vlan_column=vlan,
        )
    return out


def parse_eltex_status(text: str) -> dict[str, LinkState]:
    """MES `show interfaces status`: Port Type Duplex Speed Neg Flow-ctrl Link-State …; down ports show --."""
    out: dict[str, LinkState] = {}
    for line in text.splitlines():
        words = line.split()
        if len(words) < 4 or not _is_port(words[0]):
            continue
        up = any(w.lower() == "up" for w in words[4:])
        out[normalize_if_name(words[0])] = LinkState(up=up, speed_mbps=parse_speed(words[3]) if up else None)
    return out


def parse_poe(text: str) -> dict[str, bool]:
    """`show power inline`: the admin column after the port (auto/static/on vs off/never/disable)."""
    out: dict[str, bool] = {}
    for line in text.splitlines():
        words = line.split()
        if len(words) >= 2 and _is_port(words[0]):
            out[normalize_if_name(words[0])] = words[1].lower() not in ("off", "never", "disable", "disabled")
    return out


def build_statuses(
    vendor: str, ports: tuple[PortRef, ...], status_text: str, config_text: str, vlan_text: str, poe_text: str | None
) -> list[PortStatus]:
    """Live state of the ports the inventory knows; ports the switch did not show stay as they are (not returned)."""
    links = parse_cisco_status(status_text) if vendor == commands.CISCO else parse_eltex_status(status_text)
    cfg = parse_running_config(config_text)
    by_cfg = {normalize_if_name(i.name): i for i in cfg.ports}
    every_vlan = tuple(sorted(set(cfg.vlan_names) | {r.vlan_id for r in parse_vlan_brief(vlan_text)}))
    poe = parse_poe(poe_text) if poe_text else {}
    out: list[PortStatus] = []
    for p in ports:
        key = normalize_if_name(p.name)
        link, conf = links.get(key), by_cfg.get(key)
        if link is None and conf is None:
            continue
        column = link.vlan_column if link else ""
        if (conf is not None and conf.routed) or column == "routed":
            mode, access, tagged = p.mode, p.access_vlan, p.tagged_vlans
        elif column == "trunk" or (conf is not None and conf.mode == "TRUNK"):
            mode, access = "TRUNK", None
            if conf is not None and conf.allowed_vlans is not None and not conf.allowed_all:
                tagged = tuple(conf.allowed_vlans)
            elif vendor == commands.CISCO:
                tagged = every_vlan  # IOS trunks carry every VLAN unless told otherwise
            else:
                tagged = ()  # MES trunks carry nothing until `allowed vlan add`
        else:
            mode, tagged = "ACCESS", ()
            access = int(column) if column.isdigit() else (conf.access_vlan if conf and conf.access_vlan else 1)
        admin_up = not conf.shutdown if conf is not None else not (link and link.admin_down)
        up = bool(link and link.up)
        out.append(
            PortStatus(
                number=p.number,
                name=p.name,
                mode=mode,
                access_vlan=access,
                tagged_vlans=tuple(tagged),
                is_admin_up=admin_up,
                is_oper_up=up and admin_up,
                speed_mbps=(link.speed_mbps if link and up and link.speed_mbps else p.speed_mbps),
                poe_enabled=poe.get(key, p.poe_enabled),
                connected_mac=p.connected_mac,
            )
        )
    return out


def ping_succeeded(output: str) -> bool:
    if m := re.search(r"success rate is (\d+) percent", output, re.IGNORECASE):
        return int(m.group(1)) > 0
    if m := re.search(r"(\d+) packets? received", output, re.IGNORECASE):
        return int(m.group(1)) > 0
    if m := re.search(r"(\d+(?:\.\d+)?)% packet loss", output, re.IGNORECASE):
        return float(m.group(1)) < 100
    return "!" in output


class SshSwitchDriver(SwitchDriver):
    """Cisco IOS and Eltex MES over SSH. One session per operation, one at a time per switch."""

    def __init__(self, logins: LoginResolver, options: SshOptions | None = None, opener: Opener = open_cli) -> None:
        self._logins = logins
        self._options = options or SshOptions()
        self._open = opener
        self._locks: dict[uuid.UUID, asyncio.Lock] = {}

    @contextlib.asynccontextmanager
    async def _cli(self, target: DeviceTarget) -> AsyncIterator[CliSession]:
        if not target.ip:
            raise SwitchError("switch_unreachable", f"{target.name}: no IP address in the inventory")
        login = await self._logins(target.credential_id)
        if login is None:
            raise SwitchError(
                "no_switch_login",
                f"{target.name}: no SSH login — pick one in the device card or set SWITCH_CREDENTIAL on the server",
            )
        commands._vendor(target.vendor)  # unsupported vendors fail before connecting
        async with self._locks.setdefault(target.id, asyncio.Lock()):
            async with self._open(target.ip, login, target.vendor, self._options) as cli:
                yield cli

    # --- read-only ---

    async def get_port_statuses(self, target: DeviceTarget) -> list[PortStatus]:
        async with self._cli(target) as cli:
            status = await cli.checked("show interfaces status")
            config = await cli.checked("show running-config", timeout=120)
            vlans = await cli.run("show vlan brief") if target.vendor == commands.CISCO else ""
            poe = await cli.run("show power inline")
        return build_statuses(target.vendor, target.ports, status, config, vlans, None if _refused(poe) else poe)

    async def get_running_config(self, target: DeviceTarget) -> str:
        async with self._cli(target) as cli:
            text = await cli.checked("show running-config", timeout=120)
        # IOS starts with «Building configuration...» and «Current configuration : N bytes».
        lines = text.splitlines()
        while lines and re.match(r"^(building configuration|current configuration|\s*$)", lines[0], re.IGNORECASE):
            lines.pop(0)
        return "\n".join(lines) + "\n"

    async def ping(self, target: DeviceTarget, dest: str, count: int) -> DiagResult:
        cmd = commands.ping_command(target.vendor, str(ipaddress.ip_address(dest)), count)
        async with self._cli(target) as cli:
            out = await cli.run(cmd, timeout=self._options.command_timeout + count * 3)
        return DiagResult(command=cmd, output=out, success=not _refused(out) and ping_succeeded(out))

    async def traceroute(self, target: DeviceTarget, dest: str) -> DiagResult:
        ip = str(ipaddress.ip_address(dest))
        cmd = commands.traceroute_command(target.vendor, ip)
        async with self._cli(target) as cli:
            out = await cli.run(cmd, timeout=180)
        reached = any(ip in line for line in out.splitlines()[1:])
        return DiagResult(command=cmd, output=out, success=not _refused(out) and reached)

    # --- state-changing (WriteGuard decides whether these are reachable) ---

    async def apply_port_config(self, target: DeviceTarget, port: PortRef, change: PortConfig) -> list[str]:
        cmds = commands.port_config_commands(target.vendor, port.name, change)
        if cmds:
            async with self._cli(target) as cli:
                await cli.configure(cmds)
        return cmds

    async def bounce_port(self, target: DeviceTarget, port: PortRef) -> list[str]:
        cmds = commands.bounce_commands(target.vendor, port.name)
        half = len(cmds) // 2
        async with self._cli(target) as cli:
            await cli.configure(cmds[:half])
            await asyncio.sleep(BOUNCE_PAUSE_SECONDS)
            await cli.configure(cmds[half:])
        return cmds

    async def save_config(self, target: DeviceTarget) -> list[str]:
        cmds = commands.save_commands(target.vendor)
        async with self._cli(target) as cli:
            for cmd in cmds:
                await cli.checked(cmd, timeout=120)
        return cmds


def _refused(output: str) -> bool:
    return cli_error(output) is not None
