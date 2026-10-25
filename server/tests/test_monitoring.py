import asyncio
import socket
import time
import uuid

from app.config import MonitorMode
from app.hal.printers import SUPPLIES, PrinterTarget, SnmpPrinters, parse_supplies
from app.services import heartbeat
from app.services.traps import handle_trap

from .conftest import TOKEN, find, make_settings, ports_of
from .snmp_util import V2, start_agent, v1_link_trap, v2c_link_trap


def _open_alerts(client, alert_type=None):
    alerts = client.get("/api/v1/alerts", params={"is_resolved": False}).json()
    return [a for a in alerts if alert_type is None or a["alert_type"] == alert_type]


# --- printers -----------------------------------------------------------------


def test_printer_fields(client):
    printers = {p["name"]: p for p in client.get("/api/v1/printers").json()}
    buh = printers["PRN-BUH-01"]
    assert buh["low_supply"] is True and buh["polled_at"] is None
    kinds = {c["name"]: c["kind"] for c in buh["consumables"]}
    assert kinds == {"Black": "TONER", "Waste toner": "WASTE", "Drum unit": "DRUM"}
    assert printers["PRN-CEH-01"]["low_supply"] is False


def test_printer_refresh_raises_toner_low_once(client):
    prn = find(client, "PRN-OK-01")
    with client.websocket_connect(f"/api/v1/ws?token={TOKEN}") as ws:
        ws.send_json({"action": "subscribe", "device_id": prn["id"]})
        assert ws.receive_json()["type"] == "snapshot"
        r = client.post(f"/api/v1/printers/{prn['id']}/refresh")
        assert r.status_code == 200
        body = r.json()
        assert body["polled_at"] and body["total_pages"] >= 129877
        events = [ws.receive_json(), ws.receive_json()]
        assert events[0]["type"] == "printer_status" and events[0]["printer"]["id"] == prn["id"]
        assert events[1]["type"] == "alert" and events[1]["alert"]["alert_type"] == "TONER_LOW"
    low = [a for a in _open_alerts(client, "TONER_LOW") if a["device_id"] == prn["id"]]
    assert len(low) == 1 and "Magenta" in low[0]["message"]
    client.post(f"/api/v1/printers/{prn['id']}/refresh")
    assert len([a for a in _open_alerts(client, "TONER_LOW") if a["device_id"] == prn["id"]]) == 1


def test_printer_404_for_non_printer(client):
    sw = find(client, "SW-CORE-01")
    assert client.get(f"/api/v1/printers/{sw['id']}").status_code == 404


def test_parse_supplies():
    rows = {
        f"{SUPPLIES}.5.1.1": 3,
        f"{SUPPLIES}.6.1.1": "TK-3160 Black Toner",
        f"{SUPPLIES}.8.1.1": 100,
        f"{SUPPLIES}.9.1.1": 37,
        f"{SUPPLIES}.5.1.2": 4,
        f"{SUPPLIES}.6.1.2": "Waste Toner Box",
        f"{SUPPLIES}.8.1.2": 200,
        f"{SUPPLIES}.9.1.2": 30,
        f"{SUPPLIES}.5.1.3": 9,
        f"{SUPPLIES}.6.1.3": "Cyan Drum",
        f"{SUPPLIES}.8.1.3": -2,
        f"{SUPPLIES}.9.1.3": -3,
    }
    s = parse_supplies(rows)
    assert [(x.name, x.kind, x.level_percent, x.color_hex) for x in s] == [
        ("TK-3160 Black Toner", "TONER", 37, "#000000"),
        ("Waste Toner Box", "WASTE", 15, "#7f8c8d"),
    ]


async def test_snmp_printer_driver_against_agent():
    table = {
        f"{SUPPLIES}.5.1.1": V2.Integer(3),
        f"{SUPPLIES}.5.1.2": V2.Integer(3),
        f"{SUPPLIES}.6.1.1": V2.OctetString("Cyan Toner"),
        f"{SUPPLIES}.6.1.2": V2.OctetString("Black Toner"),
        f"{SUPPLIES}.8.1.1": V2.Integer(6000),
        f"{SUPPLIES}.8.1.2": V2.Integer(12000),
        f"{SUPPLIES}.9.1.1": V2.Integer(300),
        f"{SUPPLIES}.9.1.2": V2.Integer(9000),
        "1.3.6.1.2.1.43.10.2.1.4.1.1": V2.Counter32(48213),
        "1.3.6.1.2.1.43.11.2.1.1": V2.Integer(0),  # next table, walk must stop before it
    }
    transport, agent, port = await start_agent(table, community="s3cret")
    try:
        driver = SnmpPrinters(make_settings(monitor_mode=MonitorMode.REAL, snmp_timeout_seconds=1, snmp_retries=0))
        import app.hal.printers as printers_mod

        orig = printers_mod.SnmpPrinters.poll

        # Point the driver at the fake agent's port instead of 161.
        async def poll_on_port(self, target):
            from pysnmp.hlapi.v3arch import asyncio as h

            real_create = h.UdpTransportTarget.create

            async def create(addr, **kw):
                return await real_create((addr[0], port), **kw)

            h.UdpTransportTarget.create = create
            try:
                return await orig(self, target)
            finally:
                h.UdpTransportTarget.create = real_create

        snap = await poll_on_port(driver, PrinterTarget(uuid.uuid4(), "PRN", "127.0.0.1", "s3cret", {}))
    finally:
        transport.close()
    assert snap.total_pages == 48213
    assert [(s.name, s.level_percent, s.color_hex) for s in snap.supplies] == [
        ("Cyan Toner", 5, "#00aeef"),
        ("Black Toner", 75, "#000000"),
    ]
    assert set(agent.requests) <= {"get", "next", "bulk"}  # read-only PDUs only


# --- heartbeat ---------------------------------------------------------------


def test_heartbeat_alerts_for_servers_not_pcs(client):
    srv = find(client, "SRV-1C-01")
    pc = find(client, "PC-BUH-01")
    down = {srv["ip"], pc["ip"]}

    async def pinger(ip):
        return ip not in down

    app = client.app
    client.portal.call(heartbeat.heartbeat_once, app.state.sessionmaker, app.state.hub, pinger)
    assert find(client, "SRV-1C-01")["is_online"] is False
    assert find(client, "PC-BUH-01")["is_online"] is False
    # Offline in the seed, answers ping now
    assert find(client, "PC-BUH-05")["is_online"] is True
    host_down = _open_alerts(client, "HOST_DOWN")
    assert [a["device_id"] for a in host_down] == [srv["id"]]

    down.clear()
    client.portal.call(heartbeat.heartbeat_once, app.state.sessionmaker, app.state.hub, pinger)
    assert find(client, "SRV-1C-01")["is_online"] is True
    assert _open_alerts(client, "HOST_DOWN") == []


async def test_icmp_ping_never_uses_a_shell(monkeypatch):
    seen = {}

    class P:
        async def wait(self):
            return 0

    async def fake_exec(*args, **kw):
        seen["args"] = args
        return P()

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_exec)
    assert await heartbeat.icmp_ping("10.0.0.1") is True
    assert seen["args"] == ("ping", "-n", "-c", "1", "-W", "1", "10.0.0.1")


# --- traps -------------------------------------------------------------------


def _trap(client, data, source_ip, community="public"):
    app = client.app
    return client.portal.call(handle_trap, app.state.sessionmaker, app.state.hub, community, source_ip, data)


def test_v2c_link_trap_by_name(client):
    sw = find(client, "SW-ACC-01")
    with client.websocket_connect(f"/api/v1/ws?token={TOKEN}") as ws:
        ws.send_json({"action": "subscribe", "device_id": "*"})
        assert _trap(client, v2c_link_trap(False, 999, "gi1/0/2"), sw["ip"]) is True
        types = {ws.receive_json()["type"] for _ in range(3)}
        assert {"port_status", "alert", "device_status"} <= types
    assert ports_of(client, sw["id"])[1]["is_oper_up"] is False
    assert find(client, "PC-BUH-02")["is_online"] is False
    [a] = [a for a in _open_alerts(client, "LINK_DOWN") if a["device_id"] == sw["id"] and "gi1/0/2" in a["message"]]

    assert _trap(client, v2c_link_trap(True, 999, "gi1/0/2"), sw["ip"]) is True
    assert not [x for x in _open_alerts(client, "LINK_DOWN") if x["id"] == a["id"]]
    assert find(client, "PC-BUH-02")["is_online"] is True


def test_cisco_ifdescr_and_v1_by_ifindex(client):
    sw = find(client, "SW-ACC-03")
    assert _trap(client, v2c_link_trap(False, 10102, "GigabitEthernet1/0/2"), sw["ip"]) is True
    assert ports_of(client, sw["id"])[1]["is_oper_up"] is False
    # v1 trap relayed from another address: agent-addr identifies the switch
    assert _trap(client, v1_link_trap(True, sw["ip"], 10102), "192.0.2.50") is True
    assert ports_of(client, sw["id"])[1]["is_oper_up"] is True


def test_trap_rejections(client):
    sw = find(client, "SW-ACC-01")
    assert _trap(client, v2c_link_trap(False, 50, "gi1/0/2", community="wrong"), sw["ip"]) is False
    assert _trap(client, v2c_link_trap(False, 50, "gi1/0/2"), "192.0.2.99") is False  # unknown sender
    assert _trap(client, v2c_link_trap(False, 7, "gi9/9/9"), sw["ip"]) is False  # unknown port
    assert _trap(client, b"garbage", sw["ip"]) is False
    assert ports_of(client, sw["id"])[1]["is_oper_up"] is True


def test_trap_listener_over_udp(seeded):
    from fastapi.testclient import TestClient

    from app.main import create_app

    from .conftest import AUTH

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    with TestClient(create_app(make_settings(trap_listen_port=port)), headers=AUTH) as c:
        sw = find(c, "SW-ACC-01")
        # The listener sees 127.0.0.1 as the source, so use a v1 trap with agent-addr.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as tx:
            tx.sendto(v1_link_trap(False, sw["ip"], 51), ("127.0.0.1", port))
        deadline = time.time() + 5
        while time.time() < deadline and ports_of(c, sw["id"])[2]["is_oper_up"]:
            time.sleep(0.05)
        assert ports_of(c, sw["id"])[2]["is_oper_up"] is False
