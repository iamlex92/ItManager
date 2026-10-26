import asyncio
import ipaddress
import time

import pytest
from fastapi.testclient import TestClient

from app.config import MonitorMode
from app.discovery.model import InvalidScan, parse_subnets, short_name
from app.discovery.probe import Prober
from app.discovery.snmp import ARP, BASE_PORT_IF_INDEX, DOT1Q_FDB_PORT, IF_NAME, LLDP_LOC_PORT_ID, LLDP_REM, SnmpScanner
from app.main import create_app

from .conftest import AUTH, audit_rows, find, make_settings, ports_of
from .snmp_util import V2, start_agent

EMULATED = ["10.99.0.0/24", "10.10.10.0/24", "10.10.30.0/24", "10.10.40.0/24"]


def run_scan(client: TestClient, subnets=EMULATED, community="public") -> dict:
    r = client.post("/api/v1/discovery/scans", json={"subnets": subnets, "community": community, "operator": "Pixel"})
    assert r.status_code == 202, r.text
    scan = r.json()
    for _ in range(200):
        scan = client.get(f"/api/v1/discovery/scans/{scan['id']}").json()
        if scan["status"] != "running":
            break
        time.sleep(0.02)
    assert scan["status"] == "done", scan
    return scan


def by_ip(scan: dict) -> dict[str, dict]:
    return {d["ip"]: d for d in scan["devices"]}


def link_between(scan: dict, a: str, b: str) -> dict | None:
    return next((lk for lk in scan["links"] if {lk["a"]["name"], lk["b"]["name"]} == {a, b}), None)


# --- validation --------------------------------------------------------------


def test_subnets_must_be_small_and_private():
    assert [str(n) for n in parse_subnets(["10.10.20.7/24", " ", "10.10.20.0/24"])] == ["10.10.20.0/24"]
    for bad, word in [
        (["8.8.8.0/24"], "внутренние"),
        (["10.0.0.0/16"], "/22"),
        (["вчера"], "подсеть"),
        ([], "хотя бы"),
        (["fd00::/120"], "IPv4"),
        ([f"10.0.{i}.0/24" for i in range(9)], "8 подсетей"),
        ([f"10.0.{i * 4}.0/22" for i in range(3)], "2048"),
    ]:
        with pytest.raises(InvalidScan, match=word):
            parse_subnets(bad)


def test_short_name():
    assert short_name("SW-01.corp.local") == "SW-01"
    assert short_name("SW-01(FOC1234X)") == "SW-01"
    assert short_name("10.99.0.14") == "10.99.0.14"


def test_scan_rejects_public_ranges(client):
    r = client.post("/api/v1/discovery/scans", json={"subnets": ["1.1.1.0/24"]})
    assert r.status_code == 422
    assert r.json()["detail"]["code"] == "invalid_subnets"


def test_scan_needs_token(seeded):
    with TestClient(create_app(make_settings())) as c:
        assert c.post("/api/v1/discovery/scans", json={"subnets": ["10.99.0.0/24"]}).status_code == 401


# --- emulated scan -----------------------------------------------------------


def test_emulated_scan_finds_known_and_new_devices(client):
    scan = run_scan(client)
    assert scan["mode"] == "emulator"
    assert scan["total"] == scan["probed"] == 4 * 254
    devices = by_ip(scan)
    core = devices["10.99.0.1"]
    assert core["name"] == "SW-CORE-01" and core["snmp"] and core["inventory_name"] == "SW-CORE-01"
    new_switch = devices["10.99.0.14"]
    assert new_switch["name"] == "SW-ACC-04" and new_switch["type"] == "SWITCH" and new_switch["inventory_id"] is None
    assert devices["10.10.30.57"]["type"] == "PRINTER"
    pc = devices["10.10.10.231"]
    assert pc["name"] is None and not pc["snmp"] and pc["mac"]
    # ICMP blocked, no SNMP: found by its open ports, and they tell what it is.
    assert pc["seen_by"] == "tcp" and pc["open_ports"] == [135, 445, 3389] and pc["type"] == "WORKSTATION"
    assert pc["vendor"] == "HP" and "Windows" in pc["description"]
    assert scan["warnings"] == []

    # Existing uplinks are recognised, the new switch's uplink is not.
    assert link_between(scan, "SW-CORE-01", "SW-ACC-01")["known"] is True
    uplink = link_between(scan, "SW-CORE-01", "SW-ACC-04")
    assert uplink["source"] == "lldp" and uplink["known"] is False
    assert uplink["a"]["port"] == "Te1/1/4"
    assert link_between(scan, "SW-ACC-04", "PRN-SKLAD")["source"] == "fdb"
    host = next(lk for lk in scan["links"] if lk["b"]["ip"] == "10.10.10.231")
    assert host["source"] == "fdb" and host["b"]["name"] == "10.10.10.231"


def test_wrong_community_gives_ping_only_results(client):
    scan = run_scan(client, ["10.99.0.0/24"], community="wrong")
    assert scan["devices"] and all(not d["snmp"] and d["name"] is None for d in scan["devices"])
    assert scan["links"] == []
    # Without SNMP the type still comes from open ports and the MAC vendor.
    types = {d["type"] for d in scan["devices"]}
    assert "SWITCH" in types and "UNKNOWN" not in types


def test_offline_devices_do_not_answer(client):
    scan = run_scan(client, ["10.10.10.0/24", "10.10.20.0/24"])
    devices = client.get("/api/v1/devices").json()
    offline = {d["ip"] for d in devices if d["ip"] and not d["is_online"] and d["type"] != "SOCKET"}
    assert offline and not offline & set(by_ip(scan))


def test_one_scan_at_a_time_and_latest(seeded):
    with TestClient(create_app(make_settings(discovery_step_delay_seconds=0.02)), headers=AUTH) as c:
        assert c.get("/api/v1/discovery/scans/latest").status_code == 404
        first = c.post("/api/v1/discovery/scans", json={"subnets": ["10.99.0.0/24"]})
        assert first.status_code == 202 and first.json()["status"] == "running"
        again = c.post("/api/v1/discovery/scans", json={"subnets": ["10.10.10.0/24"]})
        assert again.status_code == 409 and again.json()["detail"]["code"] == "scan_running"
        early = c.post(f"/api/v1/discovery/scans/{first.json()['id']}/import", json={"ips": ["10.99.0.14"]})
        assert early.status_code == 409
        assert c.get("/api/v1/discovery/scans/latest").json()["id"] == first.json()["id"]


# --- import ------------------------------------------------------------------


def test_import_adds_devices_links_and_audit(client):
    scan = run_scan(client)
    ips = ["10.99.0.14", "10.10.30.57", "10.10.40.77", "10.10.10.231", "10.99.0.1"]
    r = client.post(f"/api/v1/discovery/scans/{scan['id']}/import", json={"ips": ips, "operator": "Pixel"})
    assert r.status_code == 200, r.text
    out = r.json()
    assert sorted(d["name"] for d in out["created"]) == ["CAM-VOROTA-2", "HOST-10-10-10-231", "PRN-SKLAD", "SW-ACC-04"]
    assert len(out["links"]) == 4, out

    sw4 = find(client, "SW-ACC-04")
    core = find(client, "SW-CORE-01")
    # Placed below the core switch, inside the plan.
    assert sw4["x"] is not None and abs(sw4["x"] - core["x"]) < 120 and 0 < sw4["y"] < 1200
    assert {p["name"] for p in ports_of(client, sw4["id"])} == {"te1/0/1", "gi1/0/3", "gi1/0/7"}
    conns = client.get("/api/v1/connections", params={"device_id": sw4["id"]}).json()
    assert len(conns) == 3
    pc = find(client, "HOST-10-10-10-231")
    assert pc["type"] == "WORKSTATION" and pc["mac"] and pc["vendor"] == "HP" and pc["x"] is not None

    # The next scan sees them as inventory, with nothing extra invented.
    again = run_scan(client)
    assert by_ip(again)["10.99.0.14"]["inventory_name"] == "SW-ACC-04"
    assert link_between(again, "SW-CORE-01", "SW-ACC-04")["known"] is True
    assert all(lk["known"] for lk in again["links"])
    second = client.post(f"/api/v1/discovery/scans/{again['id']}/import", json={"ips": ips}).json()
    assert second["created"] == [] and second["links"] == []

    actions = [row[0] for row in audit_rows()]
    assert actions.count("network_scan") == 2 and actions.count("discovery_import") == 2
    entries = client.get("/api/v1/audit", params={"kind": "network"}).json()
    assert {e["title"] for e in entries} == {"Сканирование сети", "Добавление найденных устройств"}
    assert all(e["kind"] == "network" for e in entries)
    assert not client.get("/api/v1/audit", params={"kind": "switch"}).json()
    scan_entry = next(e for e in entries if e["action"] == "network_scan")
    assert scan_entry["triggered_by"].startswith("Pixel · ")
    assert "public" not in str(scan_entry["parameters"]) and "community" not in str(scan_entry["parameters"])


def test_import_without_links_places_devices_in_a_row(client):
    scan = run_scan(client)
    out = client.post(
        f"/api/v1/discovery/scans/{scan['id']}/import", json={"ips": ["10.10.30.57", "10.10.40.77"], "links": False}
    ).json()
    assert out["links"] == []
    a, b = find(client, "PRN-SKLAD"), find(client, "CAM-VOROTA-2")
    assert a["y"] == b["y"] == 1110 and a["x"] != b["x"]


def test_import_reports_unknown_ip(client):
    scan = run_scan(client, ["10.99.0.0/24"])
    out = client.post(f"/api/v1/discovery/scans/{scan['id']}/import", json={"ips": ["10.99.0.200"]}).json()
    assert out["created"] == [] and "10.99.0.200" in out["skipped"][0]


# --- real scanner against fake SNMP agents ------------------------------------

HOST_MAC = bytes.fromhex("001122334455")
SWB_MAC = bytes.fromhex("a8f94b000002")


def _mac_index(mac: bytes) -> str:
    return ".".join(str(b) for b in mac)


SW_A = {
    "1.3.6.1.2.1.1.1.0": V2.OctetString("Cisco IOS Software, C2960X Software"),
    "1.3.6.1.2.1.1.2.0": V2.ObjectIdentifier("1.3.6.1.4.1.9.1.1208"),
    "1.3.6.1.2.1.1.5.0": V2.OctetString("SW-A.corp.local"),
    f"{IF_NAME}.1": V2.OctetString("Gi1/0/1"),
    f"{IF_NAME}.2": V2.OctetString("Gi1/0/2"),
    f"{IF_NAME}.28": V2.OctetString("Gi1/0/28"),
    f"{LLDP_LOC_PORT_ID}.28": V2.OctetString("Gi1/0/28"),
    f"{LLDP_REM}.7.0.28.1": V2.OctetString("te1/0/1"),
    f"{LLDP_REM}.9.0.28.1": V2.OctetString("SW-B"),
    f"{ARP}.1.10.0.0.5": V2.OctetString(HOST_MAC),
    f"{BASE_PORT_IF_INDEX}.1": V2.Integer(1),
    f"{BASE_PORT_IF_INDEX}.2": V2.Integer(2),
    f"{BASE_PORT_IF_INDEX}.28": V2.Integer(28),
    f"{DOT1Q_FDB_PORT}.10.{_mac_index(HOST_MAC)}": V2.Integer(2),
    f"{DOT1Q_FDB_PORT}.99.{_mac_index(SWB_MAC)}": V2.Integer(28),
}
SW_B = {
    "1.3.6.1.2.1.1.1.0": V2.OctetString("MES2324P AC 28-port 1G/10G Managed Switch"),
    "1.3.6.1.2.1.1.2.0": V2.ObjectIdentifier("1.3.6.1.4.1.35265.1.81"),
    "1.3.6.1.2.1.1.5.0": V2.OctetString("SW-B"),
    f"{IF_NAME}.49": V2.OctetString("te1/0/1"),
    f"{LLDP_LOC_PORT_ID}.49": V2.OctetString("te1/0/1"),
    f"{LLDP_REM}.7.0.49.3": V2.OctetString("Gi1/0/28"),
    f"{LLDP_REM}.9.0.49.3": V2.OctetString("SW-A"),
}


async def no_arp() -> dict[str, str]:
    return {}


async def test_snmp_scanner_against_agents():
    t_a, agent_a, port_a = await start_agent(SW_A, community="ro-c0mm")
    t_b, agent_b, port_b = await start_agent(SW_B, community="ro-c0mm")
    # Nothing listens here: the host answers ping only.
    silent = socket_port()
    ports = {"10.0.0.1": port_a, "10.0.0.2": port_b, "10.0.0.5": silent}

    class Scanner(SnmpScanner):
        async def _target(self, ip):
            from pysnmp.hlapi.v3arch import asyncio as h

            return await h.UdpTransportTarget.create(("127.0.0.1", ports[ip]), timeout=0.5, retries=0)

    async def pinger(ip: str) -> bool:
        return ip in ports

    seen = []
    try:
        settings = make_settings(monitor_mode=MonitorMode.REAL)
        # No TCP probe or name lookups: the 10.0.0.x addresses here exist only for the fake agents.
        prober = Prober(pinger, fast_ports=(), extra_ports=(), names=False)
        result = await Scanner(settings, prober=prober, arp=no_arp).scan(
            [ipaddress.ip_network("10.0.0.0/29")], "ro-c0mm", seen.append
        )
    finally:
        t_a.close()
        t_b.close()

    assert seen[-1] == 6
    devices = {d.ip: d for d in result.devices}
    assert set(devices) == {"10.0.0.1", "10.0.0.2", "10.0.0.5"}
    a = devices["10.0.0.1"]
    assert (a.name, a.vendor, a.device_type, a.snmp) == ("SW-A", "CISCO", "SWITCH", True)
    assert devices["10.0.0.2"].vendor == "ELTEX" and devices["10.0.0.2"].device_type == "SWITCH"
    host = devices["10.0.0.5"]
    assert not host.snmp and host.mac == "00:11:22:33:44:55"

    links = {(lk.source, lk.a.name, lk.a.port, lk.b.name, lk.b.port, lk.b.ip) for lk in result.links}
    assert links == {
        ("lldp", "SW-A", "Gi1/0/28", "SW-B", "te1/0/1", "10.0.0.2"),
        ("fdb", "SW-A", "Gi1/0/2", "", "", "10.0.0.5"),
    }
    # GET and GETNEXT/GETBULK only: nothing that could change a device.
    assert set(agent_a.requests) | set(agent_b.requests) <= {"get", "next", "bulk"}


def socket_port() -> int:
    import socket

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def test_real_mode_reports_scan_mode(seeded, monkeypatch):
    from app.discovery import manager

    async def fake_scan(self, nets, community, progress):
        progress(len(list(nets[0].hosts())))
        await asyncio.sleep(0)
        from app.discovery.model import FoundDevice, ScanResult

        return ScanResult(devices=[FoundDevice(ip="10.99.0.1", name="SW-CORE-01", snmp=True)])

    monkeypatch.setattr(manager.SnmpScanner, "scan", fake_scan)
    monkeypatch.setattr(manager.heartbeat, "ping_available", lambda: True)
    with TestClient(create_app(make_settings(discovery_mode="real")), headers=AUTH) as c:
        scan = run_scan(c, ["10.99.0.0/28"])
    assert scan["mode"] == "real" and scan["probed"] == 14
    assert by_ip(scan)["10.99.0.1"]["inventory_name"] == "SW-CORE-01"
