from app.cliimport.parse import normalize_if_name, parse_running_config, parse_vlan_brief, parse_vlan_ids

from .conftest import audit_rows, find, ports_of

CONFIG = """\
hostname SW-ACC-01
!
vlan 10,20
vlan 30
 name Cameras
!
interface GigabitEthernet1/0/1
 description PC office
 switchport mode access
 switchport access vlan 10
!
interface GigabitEthernet1/0/2
 switchport mode trunk
 switchport trunk allowed vlan 10,20
 switchport trunk allowed vlan add 30
 speed 1000
!
interface GigabitEthernet1/0/3
 switchport mode access
 switchport access vlan 10
 shutdown
!
interface GigabitEthernet9/9/9
 switchport mode access
!
"""


def test_parser_pieces():
    assert normalize_if_name("GigabitEthernet1/0/1") == "gi1/0/1"
    assert normalize_if_name("gigabitethernet 1/0/1") == "gi1/0/1"
    assert normalize_if_name("Gi1/0/1") == "gi1/0/1"
    assert normalize_if_name("Te1/0/2") == "te1/0/2"
    assert parse_vlan_ids("10,20,30-32,x,5000") == [10, 20, 30, 31, 32]
    cfg = parse_running_config(CONFIG)
    assert cfg.hostname == "SW-ACC-01" and cfg.vlan_names[30] == "Cameras"
    trunk = cfg.interfaces[1]
    assert trunk.mode == "TRUNK" and trunk.allowed_vlans == [10, 20, 30] and trunk.speed_mbps == 1000
    assert cfg.interfaces[2].shutdown


def test_vlan_brief():
    text = (
        "VLAN Name                             Status    Ports\n"
        "---- -------------------------------- --------- ------------------\n"
        "1    default                          active    Gi1/0/9\n"
        "10   Office                           active    Gi1/0/1, Gi1/0/2\n"
        "                                                Gi1/0/3\n"
        "1002 fddi-default                     act/unsup\n"
    )
    rows = parse_vlan_brief(text)
    assert [r.vlan_id for r in rows] == [1, 10]
    assert rows[1].ports == ["Gi1/0/1", "Gi1/0/2", "gi1/0/3"]


def _vlans(client) -> set[int]:
    return {v["vlan_id"] for v in client.get("/api/v1/vlans").json()}


def test_preview_changes_nothing_and_apply_updates_inventory(client):
    sw = find(client, "SW-ACC-01")
    url = f"/api/v1/devices/{sw['id']}/config-import"
    text = CONFIG
    before = ports_of(client, sw["id"])

    r = client.post(url, json={"text": text})
    assert r.status_code == 200, r.text
    preview = r.json()
    assert preview["applied"] is False and preview["hostname"] == "SW-ACC-01"
    assert "gi9/9/9" in preview["unmatched_interfaces"]
    assert ports_of(client, sw["id"]) == before  # preview writes nothing
    assert not [a for a in audit_rows() if "config_import" in str(a)]

    r = client.post(url, json={"text": text, "apply": True})
    out = r.json()
    assert out["applied"] is True and out["changed_ports"] >= 1
    after = {p["name"]: p for p in ports_of(client, sw["id"])}
    shut = next(c for c in out["ports"] if c["port"] == "gi1/0/3")
    assert shut["changes"]["is_admin_up"][1] is False
    assert after["gi1/0/3"]["is_admin_up"] is False
    assert any("config_import" in str(a) for a in audit_rows())
    # Applying the same text again changes nothing.
    assert client.post(url, json={"text": text, "apply": True}).json()["changed_ports"] == 0


def test_unknown_vlan_is_reported_not_created(client):
    sw = find(client, "SW-ACC-01")
    free = max(_vlans(client)) + 100
    text = f"vlan {free}\n name Brand New\ninterface Gi1/0/5\n switchport access vlan {free}\n"
    out = client.post(f"/api/v1/devices/{sw['id']}/config-import", json={"text": text, "apply": True}).json()
    assert out["unknown_vlans"][str(free)] == "Brand New"
    assert free not in _vlans(client)
    assert all("access_vlan" not in c["changes"] for c in out["ports"])


def test_garbage_and_missing_device(client):
    sw = find(client, "SW-ACC-01")
    assert client.post(f"/api/v1/devices/{sw['id']}/config-import", json={"text": "hello world"}).status_code == 422
    missing = "00000000-0000-0000-0000-000000000000"
    assert client.post(f"/api/v1/devices/{missing}/config-import", json={"text": CONFIG}).status_code == 404
