"""One device with several addresses: extra IPs, merging two inventory entries into one, and «Обновить» on the map."""

from fastapi.testclient import TestClient

from app.config import MonitorMode
from app.main import create_app

from .conftest import AUTH, find, make_settings, ports_of

API = "/api/v1"


def _device(client, **kw):
    body = {"name": "DUP", "type": "SERVER", **kw}
    r = client.post(f"{API}/devices", json=body)
    assert r.status_code == 201, r.text
    return r.json()


def test_extra_ips_are_kept_tidy_searched_and_counted(client):
    d = _device(client, name="R-EDGE", type="ROUTER", ip="10.10.50.200", extra_ips=["10.10.40.200", "10.10.50.200"])
    assert d["extra_ips"] == ["10.10.40.200"]  # the main address is not repeated
    d = client.patch(f"{API}/devices/{d['id']}", json={"extra_ips": ["10.10.40.200", "10.10.40.201", "10.10.40.200"]})
    assert d.json()["extra_ips"] == ["10.10.40.200", "10.10.40.201"]
    assert [x["name"] for x in client.get(f"{API}/devices", params={"q": "10.10.40.201"}).json()] == ["R-EDGE"]
    # Taken in «IP-адреса»: another device on it is a conflict.
    check = client.get(f"{API}/ipam/check", params={"ip": "10.10.40.201"}).json()
    assert "R-EDGE" in str(check)
    assert client.patch(f"{API}/devices/{d.json()['id']}", json={"extra_ips": ["nope"]}).status_code == 422


def test_merge_moves_ports_cables_history_and_addresses(client):
    keep = find(client, "SRV-1C-01")
    keep_ports = ports_of(client, keep["id"])
    dup = _device(client, name="SRV-1C-01 (второй IP)", ip="10.10.50.99", mac="aa:bb:cc:00:11:22", comment="iLO")
    # The same number and name as a port of the kept server, cabled to a free port of the core switch.
    p = client.post(
        f"{API}/ports",
        json={"device_id": dup["id"], "number": keep_ports[0]["number"], "name": keep_ports[0]["name"]},
    ).json()
    core = find(client, "SW-CORE-01")
    free = next(x for x in ports_of(client, core["id"]) if not x["connected_device_name"] and not x["is_uplink"])
    assert client.post(f"{API}/connections", json={"port_a_id": p["id"], "port_b_id": free["id"]}).status_code == 201
    acc = client.post(f"{API}/peripherals", json={"kind": "MONITOR", "device_id": dup["id"]}).json()

    url = f"{API}/devices/{keep['id']}/merge"
    assert client.post(url, json={"source_id": dup["id"]}).status_code == 400  # not confirmed
    assert client.post(url, json={"source_id": keep["id"], "confirm": True}).status_code == 422
    r = client.post(url, json={"source_id": dup["id"], "confirm": True})
    assert r.status_code == 200, r.text
    merged = r.json()
    assert merged["ip"] == keep["ip"] and merged["extra_ips"] == ["10.10.50.99"]
    assert merged["mac"] == keep["mac"] or keep["mac"] is None and merged["mac"] == "aa:bb:cc:00:11:22"
    assert "iLO" in (merged["comment"] or "")
    assert client.get(f"{API}/devices/{dup['id']}").status_code == 404

    ports = ports_of(client, keep["id"])
    assert len(ports) == len(keep_ports) + 1
    moved = next(x for x in ports if x["id"] == p["id"])
    assert moved["number"] == max(x["number"] for x in keep_ports) + 1
    assert moved["name"] == f"{keep_ports[0]['name']} (SRV-1C-01 (второй IP))"
    assert moved["connected_device_name"] == "SW-CORE-01"  # the cable came along
    assert client.get(f"{API}/peripherals/{acc['id']}").json()["device_id"] == keep["id"]
    # Found by its old address now.
    assert [x["name"] for x in client.get(f"{API}/devices", params={"q": "10.10.50.99"}).json()] == ["SRV-1C-01"]
    audit = client.get(f"{API}/audit").json()
    assert any(a["action"] == "device:merge" for a in audit)


def test_merge_drops_a_cable_between_the_two_halves(client):
    a = _device(client, name="A")
    b = _device(client, name="B", ip="10.10.50.98")
    pa = client.post(f"{API}/ports", json={"device_id": a["id"], "number": 1, "name": "eth0"}).json()
    pb = client.post(f"{API}/ports", json={"device_id": b["id"], "number": 1, "name": "eth0"}).json()
    client.post(f"{API}/connections", json={"port_a_id": pa["id"], "port_b_id": pb["id"]})
    merged = client.post(f"{API}/devices/{a['id']}/merge", json={"source_id": b["id"], "confirm": True}).json()
    assert merged["ip"] == "10.10.50.98"  # A had none: B's becomes the main one
    assert merged["extra_ips"] == []
    assert [(x["number"], x["connected_device_name"]) for x in ports_of(client, a["id"])] == [(1, None), (2, None)]


def test_refresh_status_in_the_emulator(client):
    r = client.post(f"{API}/devices/refresh-status").json()
    assert r["sources"] == ["эмулятор коммутаторов"] and r["errors"] == []
    assert r["checked"] > 10 and 0 < r["online"] <= r["checked"]


def test_refresh_status_pings_every_address(seeded):
    app = create_app(make_settings(monitor_mode=MonitorMode.REAL, heartbeat_interval_seconds=0))
    with TestClient(app, headers=AUTH) as client:
        srv = find(client, "SRV-1C-01")
        client.patch(f"{API}/devices/{srv['id']}", json={"extra_ips": ["10.10.50.99"]})
        answering = {"10.10.50.99"}

        async def pinger(ip):
            return ip in answering

        app.state.pinger = pinger
        r = client.post(f"{API}/devices/refresh-status").json()
        assert "ping" in r["sources"] and r["errors"] == [], r
        assert find(client, "SRV-1C-01")["is_online"] is True  # silent on its main address, answers on the other
        answering.clear()
        r = client.post(f"{API}/devices/refresh-status").json()
        assert find(client, "SRV-1C-01")["is_online"] is False and r["changed"] >= 1
