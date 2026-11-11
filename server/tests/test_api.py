from app import __version__

from .conftest import find, ports_of


def test_health_is_public(client):
    r = client.get("/api/v1/health", headers={"Authorization": ""})
    assert r.status_code == 200
    body = r.json()
    # Only the keys the apps rely on: a new field must not break this test.
    assert body["status"] == "ok" and body["version"] == __version__
    assert body["min_app_version"]
    assert body["hal_mode"] == "emulator" and body["monitor_mode"] == "emulator"
    assert body["sync_role"] == "" and body["read_only"] is False and body["last_sync_at"] is None
    assert body["secrets_encrypted"] is False and body["backup_server_url"] == ""


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
    # No plan is bundled: each site uploads its own.
    assert f["plan_image_url"] == ""


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


def test_patch_panel_port_path_both_ways(client):
    pc = find(client, "PC-BUH-02")
    nic = ports_of(client, pc["id"])[0]
    hops = client.get(f"/api/v1/ports/{nic['id']}/trace").json()["hops"]
    panel_port = next(h for h in hops if h["device_type"] == "PATCH_PANEL")
    # From the patch panel: the computer on one side, the switch on the other, the panel in between.
    path = client.get(f"/api/v1/ports/{panel_port['port_id']}/path").json()
    assert [h["device_type"] for h in path["hops"]] == ["WORKSTATION", "SOCKET", "PATCH_PANEL", "SWITCH"]
    here = next(h for h in path["hops"] if h["is_here"])
    assert here["port_id"] == panel_port["port_id"]
    assert [e["device_name"] for e in path["ends"]] == ["PC-BUH-02", "SW-ACC-01"]
    assert path["ends"][0]["ip"] == pc["ip"] and path["hops"][0]["cable_type"]
    assert path["hops"][-1]["cable_type"] is None
    # A switch port reads the same line from its own end.
    sw_path = client.get(f"/api/v1/ports/{hops[-1]['port_id']}/path").json()
    assert [h["device_name"] for h in sw_path["hops"]][-1] == "PC-BUH-02"
    assert client.get("/api/v1/ports/00000000-0000-0000-0000-000000000000/path").status_code == 404


def test_a_second_cable_in_a_patch_panel_port_is_warned_about(client):
    import asyncio

    from sqlalchemy import text

    from app.db import make_engine

    from .conftest import TEST_DB

    pc = find(client, "PC-BUH-02")
    nic = ports_of(client, pc["id"])[0]
    hops = client.get(f"/api/v1/ports/{nic['id']}/trace").json()["hops"]
    panel = next(h for h in hops if h["device_type"] == "PATCH_PANEL")
    assert client.get(f"/api/v1/ports/{panel['port_id']}/path").json()["warnings"] == []
    # A record the API would refuse (the port is busy), put in the way a bad import could.
    other = find(client, "PC-BUH-03")
    spare = ports_of(client, other["id"])[0]

    async def cable():
        engine = make_engine(TEST_DB)
        async with engine.begin() as c:
            await c.execute(
                text("INSERT INTO connections (id, port_a_id, port_b_id) VALUES (gen_random_uuid(), :a, :b)"),
                {"a": panel["port_id"], "b": spare["id"]},
            )
        await engine.dispose()

    asyncio.run(cable())
    warned = client.get(f"/api/v1/ports/{panel['port_id']}/path").json()["warnings"]
    assert any("PP-ACC-01" in w and "несколько кабелей" in w for w in warned)


def test_ten_wrong_keys_block_the_address_for_a_while(client):
    from app.api import deps

    wrong = {"Authorization": "Bearer wrong-key-0000000000000000"}
    for _ in range(deps.MAX_FAILURES - 1):
        assert client.get("/api/v1/devices", headers=wrong).status_code == 401
    # The 10th wrong key is still answered 401 and blocks the address.
    assert client.get("/api/v1/devices", headers=wrong).status_code == 401
    blocked = client.get("/api/v1/devices")  # the right key, from the blocked address
    assert blocked.status_code == 429 and blocked.json()["detail"]["code"] == "too_many_attempts"
    assert blocked.headers["retry-after"] == "60"
    # The server's own unblocking: the clock.
    client.app.state.auth_guard._blocked.clear()
    assert client.get("/api/v1/devices").status_code == 200
    entry = next(a for a in client.get("/api/v1/audit").json() if a["action"] == "auth:blocked")
    assert "заблокирован" in (entry["stdout"] or "")


def test_status_lists_background_jobs(client):
    r = client.get("/api/v1/status")
    assert r.status_code == 200
    jobs = {j["name"]: j for j in r.json()["jobs"]}
    assert set(jobs) == {"ping", "traffic", "backup", "sync", "retention"}
    assert jobs["ping"]["enabled"] is False and jobs["backup"]["enabled"] is False
    assert client.get("/api/v1/status", headers={"Authorization": ""}).status_code == 401
