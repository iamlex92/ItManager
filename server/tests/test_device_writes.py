"""Everything that changes switch state must be confirmed, audited, and
blocked on real hardware unless explicitly enabled."""

from .conftest import audit_rows, find, ports_of


def _port(client, switch, number):
    sw = find(client, switch)
    return next(p for p in ports_of(client, sw["id"]) if p["number"] == number)


def test_preview_does_not_touch_device(client):
    p = _port(client, "SW-ACC-01", 2)
    r = client.post(f"/api/v1/ports/{p['id']}/config/preview", json={"access_vlan": 20})
    assert r.json()["commands"] == [
        "configure terminal",
        "interface gigabitethernet 1/0/2",
        "switchport access vlan 20",
        "end",
    ]
    assert _port(client, "SW-ACC-01", 2)["access_vlan"] == 10
    assert audit_rows() == []


def test_write_requires_confirmation(client):
    p = _port(client, "SW-ACC-01", 2)
    r = client.post(f"/api/v1/ports/{p['id']}/config", json={"access_vlan": 20})
    assert r.status_code == 400 and r.json()["detail"]["code"] == "confirmation_required"
    r = client.post(f"/api/v1/ports/{p['id']}/config", json={"access_vlan": 20, "confirm": False})
    assert r.status_code == 400
    assert _port(client, "SW-ACC-01", 2)["access_vlan"] == 10
    assert audit_rows() == []


def test_confirmed_write_applies_and_is_audited(client):
    p = _port(client, "SW-ACC-03", 3)
    r = client.post(
        f"/api/v1/ports/{p['id']}/config", json={"mode": "TRUNK", "tagged_vlans": [20, 10], "confirm": True}
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["commands"] == [
        "configure terminal",
        "interface GigabitEthernet1/0/3",
        "switchport mode trunk",
        "switchport trunk allowed vlan 10,20",
        "end",
    ]
    assert body["port"]["mode"] == "TRUNK" and body["port"]["tagged_vlans"] == [10, 20]
    assert _port(client, "SW-ACC-03", 3)["mode"] == "TRUNK"
    [(name, code, log)] = audit_rows()
    assert name == "port_config" and code == 0 and "switchport mode trunk" in log


def test_shutdown_takes_link_down(client):
    p = _port(client, "SW-ACC-01", 1)
    r = client.post(f"/api/v1/ports/{p['id']}/config", json={"is_admin_up": False, "confirm": True})
    assert r.json()["port"]["is_admin_up"] is False and r.json()["port"]["is_oper_up"] is False
    # Admin shutdown is not a fault: no LINK_DOWN alert
    alerts = client.get("/api/v1/alerts", params={"is_resolved": False}).json()
    assert not any(a["port_id"] == p["id"] for a in alerts)
    assert find(client, "PC-BUH-01")["is_online"] is False


def test_uplink_needs_second_flag(client):
    p = _port(client, "SW-ACC-01", 25)
    r = client.post(f"/api/v1/ports/{p['id']}/config", json={"is_admin_up": False, "confirm": True})
    assert r.status_code == 409 and r.json()["detail"]["code"] == "uplink_port_protected"
    r = client.post(f"/api/v1/ports/{p['id']}/bounce", json={"confirm": True})
    assert r.status_code == 409
    r = client.post(
        f"/api/v1/ports/{p['id']}/config", json={"is_admin_up": False, "confirm": True, "confirm_uplink": True}
    )
    assert r.status_code == 200


def test_unknown_vlan_rejected(client):
    p = _port(client, "SW-ACC-01", 2)
    r = client.post(f"/api/v1/ports/{p['id']}/config", json={"access_vlan": 777, "confirm": True})
    assert r.status_code == 422 and r.json()["detail"]["code"] == "unknown_vlan"


def test_empty_change_rejected(client):
    p = _port(client, "SW-ACC-01", 2)
    assert client.post(f"/api/v1/ports/{p['id']}/config", json={"confirm": True}).status_code == 422


def test_not_a_switch(client):
    pc = find(client, "PC-BUH-01")
    nic = ports_of(client, pc["id"])[0]
    r = client.post(f"/api/v1/ports/{nic['id']}/config", json={"is_admin_up": False, "confirm": True})
    assert r.status_code == 422 and r.json()["detail"]["code"] == "not_a_managed_switch"


def test_bounce_and_write_memory(client):
    p = _port(client, "SW-ACC-02", 21)
    r = client.post(f"/api/v1/ports/{p['id']}/bounce", json={"confirm": True})
    assert r.status_code == 200 and r.json()["commands"].count("shutdown") == 1
    assert _port(client, "SW-ACC-02", 21)["is_oper_up"] is True
    sw = find(client, "SW-CORE-01")
    assert client.post(f"/api/v1/devices/{sw['id']}/write-memory", json={}).status_code == 400
    r = client.post(f"/api/v1/devices/{sw['id']}/write-memory", json={"confirm": True})
    assert r.json() == {"commands": ["write memory"]}
    eltex = find(client, "SW-ACC-01")
    r = client.post(f"/api/v1/devices/{eltex['id']}/write-memory", json={"confirm": True})
    assert r.json() == {"commands": ["copy running-config startup-config"]}
    assert [row[0] for row in audit_rows()] == ["port_bounce", "write_memory", "write_memory"]


def test_real_mode_blocks_writes_by_default(real_client):
    c = real_client
    assert c.get("/api/v1/health").json()["device_writes_enabled"] is False
    p = _port(c, "SW-ACC-01", 2)
    r = c.post(f"/api/v1/ports/{p['id']}/config", json={"access_vlan": 20, "confirm": True})
    assert r.status_code == 403 and r.json()["detail"]["code"] == "device_writes_disabled"
    assert c.post(f"/api/v1/ports/{p['id']}/bounce", json={"confirm": True}).status_code == 403
    sw = find(c, "SW-ACC-01")
    assert c.post(f"/api/v1/devices/{sw['id']}/write-memory", json={"confirm": True}).status_code == 403
    assert _port(c, "SW-ACC-01", 2)["access_vlan"] == 10
    rows = audit_rows()
    assert len(rows) == 3 and all(code == 403 and log.startswith("BLOCKED") for _, code, log in rows)
    # Preview stays available: it never contacts the device
    assert c.post(f"/api/v1/ports/{p['id']}/config/preview", json={"access_vlan": 20}).status_code == 200


def test_real_mode_reads_not_implemented(real_client):
    sw = find(real_client, "SW-ACC-01")
    r = real_client.post(f"/api/v1/devices/{sw['id']}/refresh")
    assert r.status_code == 501
