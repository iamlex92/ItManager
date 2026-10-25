from app.api.history import describe

from .conftest import find, ports_of


def _port(client, switch, number):
    sw = find(client, switch)
    return sw, next(p for p in ports_of(client, sw["id"]) if p["number"] == number)


def test_port_changes_and_config_copies_make_the_history(client):
    sw, p = _port(client, "SW-ACC-01", 2)
    assert client.post(f"/api/v1/ports/{p['id']}/config", json={"access_vlan": 20, "confirm": True}).status_code == 200
    assert (
        client.post(f"/api/v1/ports/{p['id']}/config", json={"is_admin_up": False, "confirm": True}).status_code == 200
    )
    assert client.post(f"/api/v1/devices/{sw['id']}/configs/backup").status_code == 201
    assert client.post(f"/api/v1/devices/{sw['id']}/configs/backup").status_code == 201
    # Diagnostics change nothing and stay out of the history.
    client.post(f"/api/v1/devices/{sw['id']}/ping", json={"target": "10.10.10.22", "count": 1})
    # Another switch's change is not this one's history.
    _, other = _port(client, "SW-ACC-03", 3)
    client.post(f"/api/v1/ports/{other['id']}/config", json={"access_vlan": 20, "confirm": True})

    history = client.get(f"/api/v1/devices/{sw['id']}/history").json()
    assert [h["kind"] for h in history] == ["config_backup", "config_backup", "change", "change"]
    assert history[0]["summary"].startswith("без изменений с прошлой копии")
    assert history[1]["summary"].startswith("первая сохранённая копия")
    assert history[2]["summary"] == "gi1/0/2: выключен (shutdown)"
    assert history[3]["summary"] == "gi1/0/2: VLAN 10 → 20"
    assert history[3]["title"] == "Настройка порта" and "switchport access vlan 20" in history[3]["commands"]


def test_blocked_write_is_in_the_history(real_client):
    sw = find(real_client, "SW-ACC-01")
    p = ports_of(real_client, sw["id"])[1]
    r = real_client.post(f"/api/v1/ports/{p['id']}/config", json={"access_vlan": 20, "confirm": True})
    assert r.status_code == 403
    [entry] = real_client.get(f"/api/v1/devices/{sw['id']}/history").json()
    assert entry["kind"] == "blocked"


def test_describe_old_rows_without_before():
    params = {"port": "gi1/0/5", "change": {"mode": "TRUNK", "tagged_vlans": [10, 20]}}
    assert describe("port_config", params) == "gi1/0/5: режим trunk, trunk VLAN 10,20"
    assert describe("port_bounce", {"port": "gi1/0/5"}) == "gi1/0/5: shutdown и no shutdown"


def test_vlan_dictionary_changes_are_audited(client):
    assert (
        client.post("/api/v1/vlans", json={"vlan_id": 60, "name": "Гости", "subnet_cidr": "10.10.60.0/24"}).status_code
        == 201
    )
    assert client.patch("/api/v1/vlans/60", json={"name": "Гостевой Wi-Fi"}).status_code == 200
    assert client.patch("/api/v1/vlans/60", json={"name": "Гостевой Wi-Fi"}).status_code == 200  # no change, no row
    assert client.delete("/api/v1/vlans/10").status_code == 409  # in use: nothing deleted, nothing logged
    assert client.delete("/api/v1/vlans/60").status_code == 204
    rows = client.get("/api/v1/audit", params={"kind": "switch"}).json()
    assert [r["action"] for r in rows] == ["vlan_delete", "vlan_update", "vlan_create"]
    assert rows[1]["title"] == "Изменение VLAN"
    assert rows[1]["parameters"]["changed"] == {"name": ["Гости", "Гостевой Wi-Fi"]}
