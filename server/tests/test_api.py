from .conftest import find, ports_of


def test_health_is_public(client):
    r = client.get("/api/v1/health", headers={"Authorization": ""})
    assert r.status_code == 200
    assert r.json() == {
        "status": "ok",
        "version": "0.1.0",
        "hal_mode": "emulator",
        "device_writes_enabled": True,
        "monitor_mode": "emulator",
        "runbook_mode": "emulator",
        "secrets_encrypted": False,
        "telegram_alerts": False,
    }


def test_token_required(client):
    assert client.get("/api/v1/devices", headers={"Authorization": ""}).status_code == 401
    assert client.get("/api/v1/devices", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert client.get("/api/v1/devices").status_code == 200


def test_openapi_lists_routes(client):
    paths = client.get("/openapi.json").json()["paths"]
    for p in [
        "/api/v1/floors",
        "/api/v1/devices",
        "/api/v1/ports/{port_id}/config",
        "/api/v1/vlans",
        "/api/v1/connections",
        "/api/v1/alerts",
        "/api/v1/printers",
    ]:
        assert p in paths


def test_floors_and_plan(client):
    floors = client.get("/api/v1/floors").json()
    assert len(floors) == 1
    f = floors[0]
    assert f["width_px"] == 2400 and f["height_px"] == 1200
    r = client.get(f["plan_image_url"], headers={"Authorization": ""})
    assert r.status_code == 200 and r.text.startswith("<svg")


def test_seed_shape(client):
    devices = client.get("/api/v1/devices").json()
    switches = [d for d in devices if d["type"] == "SWITCH"]
    assert {s["vendor"] for s in switches} == {"CISCO", "ELTEX"}
    sizes = sorted(len([p for p in ports_of(client, s["id"]) if not p["is_uplink"]]) for s in switches)
    assert sizes == [24, 24, 48, 48]
    types = {d["type"] for d in devices}
    assert {"WORKSTATION", "PRINTER", "SERVER", "CAMERA", "SOCKET", "PATCH_PANEL"} <= types


def test_device_fields_for_map(client):
    pc = find(client, "PC-BUH-02")
    assert pc["type"] == "WORKSTATION"
    assert pc["ip"] == "10.10.10.22"
    assert pc["vlan_id"] == 10
    assert pc["is_online"] is True
    assert isinstance(pc["x"], float) and isinstance(pc["y"], float)
    assert find(client, "PC-BUH-05")["is_online"] is False


def test_device_crud_and_nfc(client):
    floor_id = client.get("/api/v1/floors").json()[0]["id"]
    r = client.post(
        "/api/v1/devices",
        json={
            "name": "PC-TEST",
            "type": "WORKSTATION",
            "floor_id": floor_id,
            "x": 10,
            "y": 20,
            "ip": "10.10.10.99",
            "mac": "AA-BB-CC-DD-EE-FF",
            "nfc_tag_uid": "04:11:22:33:44:55:66",
        },
    )
    assert r.status_code == 201, r.text
    dev = r.json()
    assert dev["mac"] == "aa:bb:cc:dd:ee:ff"
    assert client.get("/api/v1/devices/by-nfc/04:11:22:33:44:55:66").json()["id"] == dev["id"]
    r = client.patch(f"/api/v1/devices/{dev['id']}", json={"name": "PC-TEST-2", "metadata": {"owner": "Иванов"}})
    assert r.json()["name"] == "PC-TEST-2" and r.json()["metadata"] == {"owner": "Иванов"}
    # NFC tags are unique
    sw = find(client, "SW-CORE-01")
    r = client.patch(f"/api/v1/devices/{dev['id']}", json={"nfc_tag_uid": sw["nfc_tag_uid"]})
    assert r.status_code == 409
    assert client.delete(f"/api/v1/devices/{dev['id']}").status_code == 204
    assert client.get(f"/api/v1/devices/{dev['id']}").status_code == 404


def test_search(client):
    pc = find(client, "PC-OK-01")
    names = [d["name"] for d in client.get("/api/v1/search", params={"q": pc["mac"].upper()}).json()]
    assert names == ["PC-OK-01"]
    names = [d["name"] for d in client.get("/api/v1/search", params={"q": "10.99.0.1"}).json()]
    assert "SW-CORE-01" in names


def test_ports_show_connected_device(client):
    sw = find(client, "SW-ACC-01")
    ports = {p["number"]: p for p in ports_of(client, sw["id"])}
    assert ports[2]["connected_device_name"] == "PC-BUH-02"
    assert ports[25]["is_uplink"] and ports[25]["connected_device_name"] == "SW-CORE-01"
    assert ports[25]["mode"] == "TRUNK" and 10 in ports[25]["tagged_vlans"]
    assert ports[22]["is_admin_up"] is False


def test_trace_pc_to_switch(client):
    pc = find(client, "PC-BUH-02")
    nic = ports_of(client, pc["id"])[0]
    hops = client.get(f"/api/v1/ports/{nic['id']}/trace").json()["hops"]
    assert [h["device_type"] for h in hops] == ["WORKSTATION", "SOCKET", "PATCH_PANEL", "SWITCH"]
    assert hops[-1]["device_name"] == "SW-ACC-01" and hops[-1]["port_name"] == "gi1/0/2"


def test_connections(client):
    conns = client.get("/api/v1/connections").json()
    assert conns and {"device_a_id", "device_b_id", "cable_type", "label"} <= conns[0].keys()
    sw = find(client, "SW-ACC-01")
    busy = ports_of(client, sw["id"])[0]
    free = ports_of(client, sw["id"])[22]
    r = client.post("/api/v1/connections", json={"port_a_id": busy["id"], "port_b_id": free["id"]})
    assert r.status_code == 409 and r.json()["detail"]["code"] == "port_busy"


def test_vlans(client):
    vlans = client.get("/api/v1/vlans").json()
    assert {v["vlan_id"] for v in vlans} >= {1, 10, 20, 30, 40, 50, 99}
    assert (
        client.post("/api/v1/vlans", json={"vlan_id": 60, "name": "Гости", "subnet_cidr": "10.10.60.0/24"}).status_code
        == 201
    )
    assert (
        client.post("/api/v1/vlans", json={"vlan_id": 60, "name": "x", "subnet_cidr": "10.10.61.0/24"}).status_code
        == 409
    )
    assert client.delete("/api/v1/vlans/10").status_code == 409  # in use
    assert client.delete("/api/v1/vlans/60").status_code == 204


def test_printers(client):
    printers = {p["name"]: p for p in client.get("/api/v1/printers").json()}
    color = printers["PRN-OK-01"]
    assert {c["name"] for c in color["consumables"]} >= {"Black", "Cyan", "Magenta", "Yellow"}
    assert any(c["level_percent"] < 10 for c in printers["PRN-BUH-01"]["consumables"])


def test_alerts_filter_and_resolve(client):
    open_alerts = client.get("/api/v1/alerts", params={"is_resolved": False}).json()
    assert len(open_alerts) == 2
    r = client.post(f"/api/v1/alerts/{open_alerts[0]['id']}/resolve")
    assert r.json()["is_resolved"] is True and r.json()["resolved_at"]
    assert len(client.get("/api/v1/alerts", params={"is_resolved": False}).json()) == 1
