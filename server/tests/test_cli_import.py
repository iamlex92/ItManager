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
    assert rows[1].ports == ["Gi1/0/1", "Gi1/0/2", "Gi1/0/3"]


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
    assert "gi9/9/9" in preview["new_ports"]
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


def test_new_vlan_is_added_on_apply_only(client):
    sw = find(client, "SW-ACC-01")
    free = max(_vlans(client)) + 100
    text = f"vlan {free}\n name Brand New\ninterface Gi1/0/5\n switchport access vlan {free}\n"
    url = f"/api/v1/devices/{sw['id']}/config-import"
    preview = client.post(url, json={"text": text}).json()
    assert preview["new_vlans"][str(free)] == "Brand New"
    assert free not in _vlans(client)
    out = client.post(url, json={"text": text, "apply": True}).json()
    assert free in _vlans(client)
    port = next(c for c in out["ports"] if c["port"] == "gi1/0/5")
    assert port["changes"]["access_vlan"][1] == free
    assert {p["name"]: p for p in ports_of(client, sw["id"])}["gi1/0/5"]["access_vlan"] == free


ELTEX = """\
vlan database
 vlan 10,20,99
 vlan 20 name Cameras
exit
!
hostname SW-NEW
!
interface vlan 10
 name Office
 ip address 10.10.10.1 255.255.255.0
exit
!
interface range gigabitethernet1/0/1-3
 switchport access vlan 10
exit
!
interface gigabitethernet1/0/2
 shutdown
 switchport access vlan 20
exit
!
interface gigabitethernet1/0/4
exit
!
interface tengigabitethernet1/0/1
 switchport mode trunk
 switchport trunk allowed vlan add 10,20,99
exit
!
interface port-channel1
 switchport mode trunk
exit
"""


def _empty_switch(client) -> dict:
    """A switch added by hand: no ports, and a server whose VLAN list is empty."""
    from sqlalchemy import text

    from .conftest import TEST_DB

    async def wipe():
        from app.db import make_engine

        engine = make_engine(TEST_DB)
        async with engine.begin() as c:
            await c.execute(text("UPDATE ports SET access_vlan = NULL"))
            await c.execute(text("DELETE FROM vlans"))
        await engine.dispose()

    import asyncio

    asyncio.run(wipe())
    return client.post("/api/v1/devices", json={"name": "SW-NEW", "type": "SWITCH", "vendor": "ELTEX"}).json()


def test_eltex_config_fills_an_empty_switch(client):
    sw = _empty_switch(client)
    url = f"/api/v1/devices/{sw['id']}/config-import"
    preview = client.post(url, json={"text": ELTEX}).json()
    assert preview["new_ports"] == ["gi1/0/1", "gi1/0/2", "gi1/0/3", "gi1/0/4", "te1/0/1"]
    assert preview["new_vlans"] == {"1": "default", "10": "Office", "20": "Cameras", "99": "VLAN 99"}
    assert ports_of(client, sw["id"]) == []

    out = client.post(url, json={"text": ELTEX, "apply": True}).json()
    assert out["changed_ports"] == 5
    ports = {p["name"]: p for p in ports_of(client, sw["id"])}
    assert [ports[n]["number"] for n in preview["new_ports"]] == [1, 2, 3, 4, 5]
    assert ports["gi1/0/1"]["access_vlan"] == 10 and ports["gi1/0/1"]["is_admin_up"] is True
    # The port's own block wins over the range
    assert ports["gi1/0/2"]["access_vlan"] == 20 and ports["gi1/0/2"]["is_admin_up"] is False
    # No `switchport access vlan`: VLAN 1
    assert ports["gi1/0/4"]["access_vlan"] == 1
    up = ports["te1/0/1"]
    assert up["mode"] == "TRUNK" and up["tagged_vlans"] == [10, 20, 99]
    assert up["is_uplink"] and up["speed_mbps"] == 10000
    vlans = {v["vlan_id"]: v for v in client.get("/api/v1/vlans").json()}
    assert vlans[10]["subnet_cidr"] == "10.10.10.0/24" and vlans[20]["subnet_cidr"] is None
    assert len({v["color_hex"] for v in vlans.values()}) > 1
    # The same text again: nothing left to do
    again = client.post(url, json={"text": ELTEX, "apply": True}).json()
    assert again["changed_ports"] == 0 and again["new_vlans"] == {}


def test_cisco_config_names_ports_drawn_on_the_map(client):
    sw = _empty_switch(client)
    pc = client.post("/api/v1/devices", json={"name": "PC-X", "type": "WORKSTATION"}).json()
    # A cable drawn on the map before the switch's ports were known gives it a placeholder port1.
    link = client.post("/api/v1/connections/link", json={"device_a_id": sw["id"], "device_b_id": pc["id"]})
    assert link.status_code == 201, link.text
    assert [p["name"] for p in ports_of(client, sw["id"])] == ["port1"]
    text = (
        "hostname SW-NEW\n"
        "interface Vlan20\n description Printers\n ip address 10.20.0.1 255.255.255.0\n"
        "interface GigabitEthernet0/1\n switchport access vlan 20\n switchport mode access\n"
        "interface GigabitEthernet0/2\n switchport trunk allowed vlan 20\n switchport mode trunk\n"
    )
    out = client.post(f"/api/v1/devices/{sw['id']}/config-import", json={"text": text, "apply": True}).json()
    assert out["new_ports"] == ["gi0/2"]
    renamed = next(p for p in out["ports"] if p["matched"])
    assert renamed["changes"]["port_name"] == ["port1", "gi0/1"]
    ports = {p["name"]: p for p in ports_of(client, sw["id"])}
    assert set(ports) == {"gi0/1", "gi0/2"}
    assert ports["gi0/1"]["connected_device_name"] == "PC-X"
    vlans = {v["vlan_id"]: v for v in client.get("/api/v1/vlans").json()}
    assert vlans[20]["name"] == "Printers" and vlans[20]["subnet_cidr"] == "10.20.0.0/24"


def test_garbage_and_missing_device(client):
    sw = find(client, "SW-ACC-01")
    assert client.post(f"/api/v1/devices/{sw['id']}/config-import", json={"text": "hello world"}).status_code == 422
    missing = "00000000-0000-0000-0000-000000000000"
    assert client.post(f"/api/v1/devices/{missing}/config-import", json={"text": CONFIG}).status_code == 404
