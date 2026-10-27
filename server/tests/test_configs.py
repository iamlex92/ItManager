from .conftest import find, ports_of


def test_backup_and_diff(client):
    sw = find(client, "SW-CORE-01")
    r = client.post(f"/api/v1/devices/{sw['id']}/configs/backup")
    assert r.status_code == 201
    cfg = r.json()
    assert "hostname SW-CORE-01" in cfg["content"] and len(cfg["config_hash"]) == 64
    assert client.get(f"/api/v1/devices/{sw['id']}/configs/diff").json()["changed"] is False

    port = ports_of(client, sw["id"])[4]
    client.post(f"/api/v1/ports/{port['id']}/config", json={"access_vlan": 50, "confirm": True})
    d = client.get(f"/api/v1/devices/{sw['id']}/configs/diff", params={"base_id": cfg["id"]}).json()
    assert d["changed"] is True and "+ switchport access vlan 50" in d["diff"]
    assert len(client.get(f"/api/v1/devices/{sw['id']}/configs").json()) == 1


def test_diff_without_backup(client):
    sw = find(client, "SW-CORE-01")
    assert client.get(f"/api/v1/devices/{sw['id']}/configs/diff").status_code == 404


def test_read_stored_config_and_diff_two_stored(client):
    sw = find(client, "SW-CORE-01")
    first = client.post(f"/api/v1/devices/{sw['id']}/configs/backup").json()
    got = client.get(f"/api/v1/devices/{sw['id']}/configs/{first['id']}")
    assert got.status_code == 200 and got.json()["content"] == first["content"]

    port = ports_of(client, sw["id"])[4]
    client.post(f"/api/v1/ports/{port['id']}/config", json={"access_vlan": 50, "confirm": True})
    second = client.post(f"/api/v1/devices/{sw['id']}/configs/backup").json()
    d = client.get(
        f"/api/v1/devices/{sw['id']}/configs/diff", params={"base_id": first["id"], "other_id": second["id"]}
    ).json()
    assert d["changed"] is True and "+ switchport access vlan 50" in d["diff"] and "stored" in d["diff"].splitlines()[1]

    other = find(client, "SW-ACC-01")
    assert client.get(f"/api/v1/devices/{other['id']}/configs/{first['id']}").status_code == 404
