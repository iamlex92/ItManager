"""Real link state over SNMP when switch control is emulated (HAL_MODE=emulator, MONITOR_MODE=real)."""

from fastapi.testclient import TestClient

from app.config import MonitorMode
from app.main import create_app
from app.services import linkstate

from .conftest import AUTH, find, make_settings, ports_of


class FakeIfMib:
    """IF-MIB of an Eltex switch: ifIndex 49.. for gi1/0/1.., some ports up."""

    def __init__(self, up: set[int], admin_down: set[int] = frozenset()) -> None:
        self.up, self.admin_down = up, admin_down
        self.walks: list[str] = []

    async def walk(self, ip, auth, prefix):
        self.walks.append(prefix)
        names = {48 + n: f"gi1/0/{n}" for n in range(1, 25)}
        if prefix == linkstate.IF_NAME:
            return {f"{prefix}.{i}": name for i, name in names.items()}
        if prefix == linkstate.IF_OPER_STATUS:
            return {f"{prefix}.{i}": 1 if i - 48 in self.up else 2 for i in names}
        if prefix == linkstate.IF_ADMIN_STATUS:
            return {f"{prefix}.{i}": 2 if i - 48 in self.admin_down else 1 for i in names}
        if prefix == linkstate.IF_HIGH_SPEED:
            return {f"{prefix}.{i}": (100 if i == 49 else 1000) if i - 48 in self.up else 0 for i in names}
        return {}


def test_refresh_reads_links_over_snmp_and_raises_link_down(seeded):
    with TestClient(create_app(make_settings(monitor_mode=MonitorMode.REAL)), headers=AUTH) as c:
        assert c.app.state.links is not None
        sw = find(c, "SW-ACC-01")
        before = {p["name"]: p for p in ports_of(c, sw["id"])}
        was_up = [n for n in range(1, 25) if before[f"gi1/0/{n}"]["is_oper_up"]]
        assert was_up, "the seed has cabled ports on SW-ACC-01"
        dropped = was_up[0]
        fake = FakeIfMib(up={1, 5} | set(was_up[1:]), admin_down={7})
        c.app.state.links._reader = fake

        r = c.post(f"/api/v1/devices/{sw['id']}/refresh")
        assert r.status_code == 200, r.text
        after = {p["name"]: p for p in r.json()}
        assert after["gi1/0/1"]["is_oper_up"] is True and after["gi1/0/1"]["speed_mbps"] == 100
        assert after["gi1/0/7"]["is_admin_up"] is False and after["gi1/0/7"]["is_oper_up"] is False
        if dropped not in (1, 5):
            assert after[f"gi1/0/{dropped}"]["is_oper_up"] is False
            alerts = c.get("/api/v1/alerts", params={"device_id": sw["id"]}).json()
            assert any(a["type"] == "LINK_DOWN" for a in alerts)
        # te ports are not in this IF-MIB: they keep what they had.
        assert after["te1/0/1"]["is_oper_up"] == before["te1/0/1"]["is_oper_up"]

        # ifIndex was learnt from ifName and stored: the next read needs no ifName walk.
        assert after["gi1/0/1"]["if_index"] == 49
        fake.walks.clear()
        c.post(f"/api/v1/devices/{sw['id']}/refresh")
        assert linkstate.IF_NAME not in fake.walks

        class Silent:
            async def walk(self, ip, auth, prefix):
                return {}

        c.app.state.links._reader = Silent()
        bad = c.post(f"/api/v1/devices/{sw['id']}/refresh")
        assert bad.status_code == 502 and bad.json()["detail"]["code"] == "snmp_failed"


def test_emulated_monitoring_keeps_the_emulator(client):
    assert client.app.state.links is None
