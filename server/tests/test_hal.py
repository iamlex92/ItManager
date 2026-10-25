import uuid

import pytest

from app.config import HalMode
from app.hal.base import DeviceTarget, DeviceWritesDisabled, PortConfig, PortRef, SwitchDriver
from app.hal.commands import bounce_commands, port_config_commands, save_commands
from app.hal.emulator import EmulatorDriver
from app.hal.factory import build_driver
from app.hal.guard import WriteGuard

from .conftest import make_settings


def _target(vendor="CISCO", n=4):
    return DeviceTarget(
        id=uuid.uuid4(),
        name="SW-T",
        vendor=vendor,
        ip="10.0.0.1",
        ports=tuple(
            PortRef(
                number=i,
                name=f"Gi1/0/{i}" if vendor == "CISCO" else f"gi1/0/{i}",
                access_vlan=10,
                is_oper_up=True,
                connected_mac=f"00:00:00:00:00:0{i}",
            )
            for i in range(1, n + 1)
        ),
    )


def test_cisco_commands():
    cmds = port_config_commands("cisco", "Gi1/0/5", PortConfig(mode="ACCESS", access_vlan=20, is_admin_up=True))
    assert cmds == [
        "configure terminal",
        "interface GigabitEthernet1/0/5",
        "switchport mode access",
        "switchport access vlan 20",
        "no shutdown",
        "end",
    ]
    assert save_commands("CISCO") == ["write memory"]


def test_eltex_commands():
    cmds = port_config_commands("ELTEX", "gi1/0/7", PortConfig(mode="TRUNK", tagged_vlans=(10, 20)))
    assert cmds == [
        "configure terminal",
        "interface gigabitethernet 1/0/7",
        "switchport mode trunk",
        "switchport trunk allowed vlan remove all",
        "switchport trunk allowed vlan add 10,20",
        "end",
    ]
    assert save_commands("ELTEX") == ["copy running-config startup-config"]
    assert bounce_commands("ELTEX", "gi1/0/7")[2] == "shutdown"


def test_unknown_vendor():
    from app.hal.base import UnsupportedVendor

    with pytest.raises(UnsupportedVendor):
        port_config_commands("KYOCERA", "eth0", PortConfig(is_admin_up=False))


class _Spy(SwitchDriver):
    def __init__(self):
        self.calls = []

    async def get_port_statuses(self, target):
        self.calls.append("read")
        return []

    async def get_running_config(self, target):
        self.calls.append("config")
        return ""

    async def ping(self, target, dest, count):
        self.calls.append("ping")

    async def traceroute(self, target, dest):
        self.calls.append("traceroute")

    async def apply_port_config(self, target, port, change):
        self.calls.append("apply")
        return []

    async def bounce_port(self, target, port):
        self.calls.append("bounce")
        return []

    async def save_config(self, target):
        self.calls.append("save")
        return []


async def test_write_guard_blocks_writes_but_not_reads():
    spy = _Spy()
    guard = WriteGuard(spy, allow_writes=False)
    t = _target()
    await guard.get_port_statuses(t)
    await guard.get_running_config(t)
    await guard.ping(t, "10.0.0.2", 1)
    await guard.traceroute(t, "10.0.0.2")
    for call in (
        guard.apply_port_config(t, t.ports[0], PortConfig(is_admin_up=False)),
        guard.bounce_port(t, t.ports[0]),
        guard.save_config(t),
    ):
        with pytest.raises(DeviceWritesDisabled):
            await call
    assert spy.calls == ["read", "config", "ping", "traceroute"]


async def test_write_guard_allows_when_enabled():
    spy = _Spy()
    guard = WriteGuard(spy, allow_writes=True)
    t = _target()
    await guard.apply_port_config(t, t.ports[0], PortConfig(is_admin_up=False))
    await guard.save_config(t)
    assert spy.calls == ["apply", "save"]


def test_factory_wraps_real_driver():
    assert isinstance(build_driver(make_settings()), EmulatorDriver)
    real = build_driver(make_settings(hal_mode=HalMode.REAL))
    assert isinstance(real, WriteGuard) and real.allow_writes is False
    assert build_driver(make_settings(hal_mode=HalMode.REAL, allow_device_writes=True)).allow_writes is True


def test_placeholder_token_rejected():
    with pytest.raises(ValueError):
        make_settings(api_token="change-me")


async def test_emulator_flaps_and_recovers():
    emu = EmulatorDriver(flap_probability=1.0, seed=1)
    t = _target()
    first = await emu.get_port_statuses(t)
    assert sum(not s.is_oper_up for s in first) == 1
    emu._flap_probability = 0
    for _ in range(5):
        statuses = await emu.get_port_statuses(t)
    assert all(s.is_oper_up for s in statuses)


async def test_emulator_running_config_reflects_changes():
    emu = EmulatorDriver(bounce_delay=0)
    t = _target("ELTEX")
    await emu.apply_port_config(t, t.ports[1], PortConfig(access_vlan=30, is_admin_up=False))
    cfg = await emu.get_running_config(t)
    block = cfg.split("interface gigabitethernet 1/0/2\n")[1].split("exit")[0]
    assert "switchport access vlan 30" in block and "shutdown" in block
    statuses = {s.number: s for s in await emu.get_port_statuses(t)}
    assert statuses[2].is_oper_up is False
