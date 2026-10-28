"""The real switch driver against a fake Cisco IOS / Eltex MES speaking SSH on localhost: login, enable, paging,
parsing the port state, configuration blocks, refused commands, the save question and connection failures."""

import contextlib
import uuid

import asyncssh
import pytest

from app.hal.base import DeviceTarget, PortConfig, PortRef, SwitchError
from app.hal.real import SshSwitchDriver, build_statuses, parse_speed
from app.hal.ssh import SshOptions, SwitchLogin, clean, cli_error

INVALID = "% Invalid input detected at '^' marker."


def _row(*cols: str) -> str:
    widths = (10, 19, 13, 11, 8, 7)
    return "".join(c.ljust(w) for c, w in zip(cols, widths, strict=False)) + (cols[6] if len(cols) > 6 else "")


CISCO = {
    "terminal length 0": "",
    "show interfaces status": "\n".join(
        [
            _row("Port", "Name", "Status", "Vlan", "Duplex", "Speed", "Type"),
            _row("Gi1/0/1", "Бухгалтерия ПК 1", "connected", "10", "a-full", "a-1000", "10/100/1000BaseTX"),
            _row("Gi1/0/2", "", "notconnect", "20", "auto", "auto", "10/100/1000BaseTX"),
            _row("Gi1/0/3", "uplink core", "connected", "trunk", "full", "1000", "10/100/1000BaseTX"),
            _row("Gi1/0/4", "", "disabled", "1", "auto", "auto", "10/100/1000BaseTX"),
        ]
    ),
    "show running-config": """Building configuration...

Current configuration : 1234 bytes
!
hostname sw1
!
vlan 10
 name BUH
!
interface GigabitEthernet1/0/1
 switchport access vlan 10
 switchport mode access
!
interface GigabitEthernet1/0/2
 switchport access vlan 20
!
interface GigabitEthernet1/0/3
 switchport mode trunk
!
interface GigabitEthernet1/0/4
 shutdown
!
end""",
    "show vlan brief": """VLAN Name                             Status    Ports
---- -------------------------------- --------- -------------------------------
1    default                          active    Gi1/0/4
10   BUH                              active    Gi1/0/1
20   VLAN0020                         active    Gi1/0/2
30   VLAN0030                         active""",
    "show power inline": """Interface Admin  Oper       Power   Device              Class Max
Gi1/0/1   auto   on         6.5     IP Phone 7942       2     30.0
Gi1/0/2   off    off        0.0     n/a                 n/a   30.0""",
    "ping 10.0.0.1 repeat 3": """Type escape sequence to abort.
Sending 3, 100-byte ICMP Echos to 10.0.0.1, timeout is 2 seconds:
!!!
Success rate is 100 percent (3/3), round-trip min/avg/max = 1/1/2 ms""",
    "write memory": "Building configuration...\n[OK]",
}

ELTEX = {
    "terminal datadump": "",
    "show interfaces status": """                                             Flow Link          Back   Mdix
Port     Type         Duplex  Speed Neg      ctrl State       Pressure Mode
-------- ------------ ------  ----- -------- ---- ----------- -------- -------
gi1/0/1  1G-Copper    Full    100   Enabled  Off  Up          Disabled Off
gi1/0/2  1G-Copper      --      --     --     --  Down           --     --
te1/0/1  10G-Fiber    Full    10G   Disabled Off  Up          Disabled Off""",
    "show running-config": """vlan database
 vlan 10,20
exit
!
interface gigabitethernet1/0/1
 switchport access vlan 20
exit
!
interface gigabitethernet1/0/2
 shutdown
exit
!
interface tengigabitethernet1/0/1
 switchport mode trunk
 switchport trunk allowed vlan add 10,20
exit""",
}


class FakeSwitch:
    """Just enough of a switch CLI: prompts, enable, config modes, canned show output, a yes/no question."""

    def __init__(self, vendor: str, outputs: dict[str, str], *, user_mode=False, login_in_shell=False) -> None:
        self.vendor, self.outputs = vendor, outputs
        self.user_mode, self.login_in_shell = user_mode, login_in_shell
        self.hostname = "sw1" if vendor == "CISCO" else "sw2"
        self.lines: list[str] = []
        self.answers: list[str] = []
        self.reject: set[str] = set()

    async def handle(self, process: asyncssh.SSHServerProcess) -> None:
        w = process.stdout.write

        async def readline(echo: bool = True) -> str | None:
            buf = ""
            while True:
                ch = await process.stdin.read(1)
                if not ch:
                    return None
                if ch in "\r\n":
                    w("\r\n")
                    return buf
                buf += ch
                if echo:
                    w(ch)

        if self.login_in_shell:
            w("\r\nUser Name:")
            user = await readline()
            w("Password:")
            pw = await readline(echo=False)
            if (user, pw) != ("admin", "pw"):
                w("% Authentication failed\r\n")
                process.exit(1)
                return
        mode, ctx = (">" if self.user_mode else "#"), ""
        w("\r\n")
        while True:
            w(f"{self.hostname}{ctx}{mode}")
            line = await readline()
            if line is None:
                break
            cmd = line.strip()
            self.lines.append(cmd)
            if not cmd:
                continue
            if cmd == "exit" and not ctx:
                break
            if cmd == "enable":
                w("Password: ")
                if await readline(echo=False) == "en":
                    mode = "#"
                else:
                    w("% Bad secrets\r\n")
            elif cmd.startswith("configure"):
                ctx = "(config)"
            elif cmd.startswith("interface "):
                ctx = "(config-if)"
            elif cmd == "end":
                ctx = ""
            elif ctx:
                if cmd in self.reject:
                    w(f"{INVALID}\r\n")
            elif cmd == "copy running-config startup-config":
                w("Overwrite file [startup-config].... (Y/N)[N] ?")
                self.answers.append(await process.stdin.read(1))
                w("\r\n%COPY-I-FILECPY: Files Copy - source URL running-config\r\nCopy succeeded\r\n")
            elif cmd in self.outputs:
                out = self.outputs[cmd]
                w(out.replace("\n", "\r\n") + ("\r\n" if out else ""))
            else:
                w(f"{INVALID}\r\n")
        process.exit(0)


class _Server(asyncssh.SSHServer):
    def begin_auth(self, username: str) -> bool:
        return True

    def password_auth_supported(self) -> bool:
        return True

    def validate_password(self, username: str, password: str) -> bool:
        return (username, password) == ("admin", "pw")


@contextlib.asynccontextmanager
async def serve(fake: FakeSwitch):
    key = asyncssh.generate_private_key("ssh-ed25519")
    server = await asyncssh.create_server(
        _Server, "127.0.0.1", 0, server_host_keys=[key], process_factory=fake.handle, line_editor=False
    )
    try:
        yield server.sockets[0].getsockname()[1]
    finally:
        server.close()
        await server.wait_closed()


def driver(port: int, login: SwitchLogin | None = SwitchLogin("admin", "pw", "en")) -> SshSwitchDriver:
    async def logins(_: uuid.UUID | None) -> SwitchLogin | None:
        return login

    return SshSwitchDriver(logins, SshOptions(port=port, connect_timeout=5, command_timeout=5))


def target(vendor: str, names: list[str]) -> DeviceTarget:
    ports = tuple(PortRef(number=i + 1, name=n, speed_mbps=1000) for i, n in enumerate(names))
    return DeviceTarget(id=uuid.uuid4(), name="sw", vendor=vendor, ip="127.0.0.1", ports=ports)


def sent(fake: FakeSwitch) -> list[str]:
    """What the driver typed, without the `exit` it may send while hanging up."""
    return [line for line in fake.lines if line != "exit"]


CISCO_PORTS = ["Gi1/0/1", "Gi1/0/2", "Gi1/0/3", "Gi1/0/4", "Gi1/0/48"]


async def test_cisco_reads_ports_through_enable():
    fake = FakeSwitch("CISCO", CISCO, user_mode=True)
    async with serve(fake) as port:
        statuses = await driver(port).get_port_statuses(target("CISCO", CISCO_PORTS))
    assert fake.lines[:2] == ["enable", "terminal length 0"]
    by = {s.name: s for s in statuses}
    assert set(by) == {"Gi1/0/1", "Gi1/0/2", "Gi1/0/3", "Gi1/0/4"}  # Gi1/0/48 is not on this switch
    one, two, uplink, off = by["Gi1/0/1"], by["Gi1/0/2"], by["Gi1/0/3"], by["Gi1/0/4"]
    assert (one.mode, one.access_vlan, one.is_oper_up, one.speed_mbps) == ("ACCESS", 10, True, 1000)
    assert one.poe_enabled is True
    assert (two.access_vlan, two.is_oper_up, two.is_admin_up, two.poe_enabled) == (20, False, True, False)
    assert uplink.mode == "TRUNK" and uplink.tagged_vlans == (1, 10, 20, 30)  # an IOS trunk carries every VLAN
    assert (off.is_admin_up, off.is_oper_up, off.access_vlan) == (False, False, 1)


async def test_cisco_config_diagnostics_and_writes():
    fake = FakeSwitch("CISCO", CISCO)
    async with serve(fake) as port:
        d, t = driver(port), target("CISCO", CISCO_PORTS)
        config = await d.get_running_config(t)
        assert config.startswith("!\nhostname sw1") and "Building" not in config
        ping = await d.ping(t, "10.0.0.1", 3)
        assert ping.success and ping.command == "ping 10.0.0.1 repeat 3" and "!!!" in ping.output

        cmds = await d.apply_port_config(t, t.ports[1], PortConfig(access_vlan=30))
        assert cmds == ["configure terminal", "interface GigabitEthernet1/0/2", "switchport access vlan 30", "end"]
        assert sent(fake)[-len(cmds) :] == cmds

        fake.reject.add("switchport access vlan 999")
        with pytest.raises(SwitchError) as e:
            await d.apply_port_config(t, t.ports[1], PortConfig(access_vlan=999))
        assert e.value.code == "switch_rejected" and "Invalid input" in str(e.value)
        assert sent(fake)[-2:] == ["switchport access vlan 999", "end"]  # left config mode before hanging up

        assert await d.save_config(t) == ["write memory"]


async def test_eltex_login_in_shell_ports_and_save():
    fake = FakeSwitch("ELTEX", ELTEX, login_in_shell=True)
    async with serve(fake) as port:
        d, t = driver(port), target("ELTEX", ["gi1/0/1", "gi1/0/2", "te1/0/1"])
        statuses = {s.name: s for s in await d.get_port_statuses(t)}
        assert fake.lines[0] == "terminal datadump"
        one, off, uplink = statuses["gi1/0/1"], statuses["gi1/0/2"], statuses["te1/0/1"]
        assert (one.mode, one.access_vlan, one.is_oper_up, one.speed_mbps) == ("ACCESS", 20, True, 100)
        assert (off.is_admin_up, off.is_oper_up, off.speed_mbps) == (False, False, 1000)
        assert (uplink.mode, uplink.tagged_vlans, uplink.speed_mbps) == ("TRUNK", (10, 20), 10000)
        assert one.poe_enabled is False  # `show power inline` refused: PoE stays as stored

        cmds = await d.apply_port_config(t, t.ports[2], PortConfig(tagged_vlans=(10, 30)))
        assert cmds[1:4] == [
            "interface tengigabitethernet 1/0/1",
            "switchport trunk allowed vlan remove all",
            "switchport trunk allowed vlan add 10,30",
        ]
        assert await d.save_config(t) == ["copy running-config startup-config"]
        assert fake.answers == ["y"]


async def test_connection_failures_are_named():
    fake = FakeSwitch("CISCO", CISCO, user_mode=True)
    t = target("CISCO", CISCO_PORTS)
    async with serve(fake) as port:
        with pytest.raises(SwitchError) as e:
            await driver(port, SwitchLogin("admin", "wrong")).get_running_config(t)
        assert e.value.code == "switch_login_failed"
        with pytest.raises(SwitchError) as e:
            await driver(port, SwitchLogin("admin", "pw", "bad-enable")).get_running_config(t)
        assert e.value.code == "switch_login_failed" and "SWITCH_ENABLE_SECRET" in str(e.value)
        with pytest.raises(SwitchError) as e:
            await driver(port, None).get_running_config(t)
        assert e.value.code == "no_switch_login"
    with pytest.raises(SwitchError) as e:
        await driver(port).get_running_config(t)  # nothing listens there any more
    assert e.value.code == "switch_unreachable"


def test_parsing_helpers():
    speeds = [parse_speed(s) for s in ("a-1000", "100", "10G", "a-10G", "auto", "--")]
    assert speeds == [1000, 100, 10000, 10000, None, None]
    assert clean("abc\x1b[2K--More--\b\b\b\b\b\b\b\b        \b\b\b\b\b\b\b\bdef\r\n") == "abcdef\n"
    assert cli_error("ok\n% Invalid input detected at '^' marker.\n") == "% Invalid input detected at '^' marker."
    assert cli_error("Building configuration...\n[OK]") is None
    # A port missing from both outputs is not reported, so its stored state stays.
    assert build_statuses("CISCO", (PortRef(1, "Gi1/0/9"),), "", "", "", None) == []


def test_api_refresh_uses_the_device_login_and_names_failures(seeded):
    """Over HTTP: the device's own SSH credential is used, the live ports are stored, a wrong login is a 502."""
    import asyncio
    import threading

    from fastapi.testclient import TestClient

    from app.config import HalMode
    from app.main import create_app

    from .conftest import AUTH, find, make_settings, ports_of

    fake = FakeSwitch("ELTEX", ELTEX, login_in_shell=True)
    loop = asyncio.new_event_loop()
    started, stop = threading.Event(), None
    port_box: list[int] = []

    async def run() -> None:
        nonlocal stop
        stop = asyncio.Event()
        async with serve(fake) as p:
            port_box.append(p)
            started.set()
            await stop.wait()

    thread = threading.Thread(target=loop.run_until_complete, args=(run(),), daemon=True)
    thread.start()
    assert started.wait(10)
    try:
        settings = make_settings(hal_mode=HalMode.REAL, switch_ssh_port=port_box[0], switch_command_timeout_seconds=5)
        with TestClient(create_app(settings), headers=AUTH) as c:
            sw = find(c, "SW-ACC-01")
            cred = c.post(
                "/api/v1/credentials", json={"name": "sw-admin", "kind": "ssh", "username": "admin", "secret": "pw"}
            ).json()
            c.patch(f"/api/v1/devices/{sw['id']}", json={"ip": "127.0.0.1", "ssh_credential_id": cred["id"]})
            r = c.post(f"/api/v1/devices/{sw['id']}/refresh")
            assert r.status_code == 200, r.text
            by = {p["name"]: p for p in ports_of(c, sw["id"])}
            assert by["gi1/0/1"]["access_vlan"] == 20 and by["gi1/0/1"]["is_oper_up"] is True
            assert by["gi1/0/2"]["is_admin_up"] is False
            assert by["te1/0/1"]["mode"] == "TRUNK" and by["te1/0/1"]["tagged_vlans"] == [10, 20]

            c.patch(f"/api/v1/credentials/{cred['id']}", json={"secret": "wrong"})
            bad = c.post(f"/api/v1/devices/{sw['id']}/refresh")
            assert bad.status_code == 502 and bad.json()["detail"]["code"] == "switch_login_failed"
    finally:
        loop.call_soon_threadsafe(stop.set)
        thread.join(10)
