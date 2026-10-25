from .conftest import audit_rows, find


def test_ping_reachable_host_from_cisco(client):
    sw = find(client, "SW-CORE-01")
    r = client.post(f"/api/v1/devices/{sw['id']}/ping", json={"target": "10.10.10.22", "count": 3})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["command"] == "ping 10.10.10.22 repeat 3"
    assert body["success"] is True and "!!!" in body["output"] and "(3/3)" in body["output"]
    [(name, code, log)] = audit_rows()
    assert name == "switch_ping" and code == 0 and log.startswith("ping 10.10.10.22")


def test_ping_offline_host_from_eltex(client):
    sw = find(client, "SW-ACC-01")
    r = client.post(f"/api/v1/devices/{sw['id']}/ping", json={"target": "10.10.10.25"})
    body = r.json()
    assert body["command"] == "ping 10.10.10.25 count 5"
    assert body["success"] is False and "100% packet loss" in body["output"]
    assert audit_rows()[0][1] == 1


def test_ping_unknown_ip_fails(client):
    sw = find(client, "SW-CORE-01")
    assert client.post(f"/api/v1/devices/{sw['id']}/ping", json={"target": "192.0.2.1"}).json()["success"] is False


def test_traceroute(client):
    sw = find(client, "SW-ACC-01")
    body = client.post(f"/api/v1/devices/{sw['id']}/traceroute", json={"target": "10.10.20.11"}).json()
    assert body["command"] == "traceroute 10.10.20.11"
    assert body["success"] is True
    assert "10.10.20.1 " in body["output"] and body["output"].strip().endswith("1 ms")


def test_target_must_be_ip(client):
    sw = find(client, "SW-CORE-01")
    for bad in ["example.com", "10.0.0.1; reload", ""]:
        assert client.post(f"/api/v1/devices/{sw['id']}/ping", json={"target": bad}).status_code == 422
    assert client.post(f"/api/v1/devices/{sw['id']}/ping", json={"target": "10.0.0.1", "count": 100}).status_code == 422


def test_ping_needs_a_switch(client):
    pc = find(client, "PC-BUH-01")
    r = client.post(f"/api/v1/devices/{pc['id']}/ping", json={"target": "10.10.10.22"})
    assert r.status_code == 422


def test_real_mode_ping_not_implemented(real_client):
    sw = find(real_client, "SW-CORE-01")
    assert real_client.post(f"/api/v1/devices/{sw['id']}/ping", json={"target": "10.10.10.22"}).status_code == 501
