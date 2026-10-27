"""Peripherals attached to devices, the network topology and switch faceplate drawings."""

from .conftest import find, ports_of

SVG = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 482 44">'
    '<rect width="482" height="44" fill="#333"/></svg>'
)


def test_peripheral_lifecycle(client):
    pc = find(client, "PC-BUH-01")
    r = client.post(
        "/api/v1/peripherals",
        json={"kind": "MONITOR", "vendor": "Dell", "model": "P2422H", "specs": '24"', "device_id": pc["id"]},
    )
    assert r.status_code == 201, r.text
    monitor = r.json()
    assert monitor["device_name"] == "PC-BUH-01" and monitor["specs"] == '24"'
    r = client.post("/api/v1/peripherals", json={"kind": "KEYBOARD", "vendor": " ", "serial_number": "KB1"})
    keyboard = r.json()
    assert keyboard["device_id"] is None and keyboard["vendor"] is None

    assert [p["id"] for p in client.get("/api/v1/peripherals", params={"device_id": pc["id"]}).json()] == [
        monitor["id"]
    ]
    assert [p["id"] for p in client.get("/api/v1/peripherals", params={"unassigned": True}).json()] == [
        keyboard["id"]
    ]
    assert len(client.get("/api/v1/peripherals", params={"q": "p2422"}).json()) == 1

    r = client.patch(f"/api/v1/peripherals/{keyboard['id']}", json={"device_id": pc["id"]})
    assert r.json()["device_name"] == "PC-BUH-01"
    assert len(client.get("/api/v1/peripherals", params={"device_id": pc["id"]}).json()) == 2

    # Not to a rack or a socket, and not to a device that does not exist.
    socket = find(client, "R-1.01")
    assert client.patch(f"/api/v1/peripherals/{keyboard['id']}", json={"device_id": socket["id"]}).status_code == 422
    missing = "00000000-0000-0000-0000-000000000000"
    assert client.patch(f"/api/v1/peripherals/{keyboard['id']}", json={"device_id": missing}).status_code == 404
    assert client.patch(f"/api/v1/peripherals/{keyboard['id']}", json={"kind": None}).status_code == 422

    # Deleting the computer puts its peripherals back in stock.
    assert client.delete(f"/api/v1/devices/{pc['id']}").status_code == 204
    stock = client.get("/api/v1/peripherals", params={"unassigned": True}).json()
    assert {p["id"] for p in stock} == {monitor["id"], keyboard["id"]}
    assert client.delete(f"/api/v1/peripherals/{monitor['id']}").status_code == 204
    assert client.get(f"/api/v1/peripherals/{monitor['id']}").status_code == 404


def test_topology_traces_through_the_cable_plant(client):
    topo = client.get("/api/v1/topology").json()
    names = {n["id"]: n["name"] for n in topo["nodes"]}
    types = {n["type"] for n in topo["nodes"]}
    assert "SOCKET" not in types and "PATCH_PANEL" not in types
    pairs = {frozenset((names[link["a_id"]], names[link["b_id"]])): link for link in topo["links"]}
    # PC -> socket -> patch panel -> switch shows as one link to the switch
    link = pairs[frozenset(("PC-BUH-01", "SW-ACC-01"))]
    assert link["via"] and link["kind"] == "CABLE" and link["cable_types"] == ["Cat6"]
    assert len(link["connection_ids"]) == len(link["via"]) + 1
    # Uplinks between switches
    assert frozenset(("SW-CORE-01", "SW-ACC-01")) in pairs
    up = pairs[frozenset(("SW-CORE-01", "SW-ACC-01"))]
    assert up["is_up"] is True and up["speed_mbps"]
    # One entry per pair of ports
    assert len(topo["links"]) == len(pairs)
    core = next(n for n in topo["nodes"] if n["name"] == "SW-CORE-01")
    assert core["degree"] >= 3


def test_topology_ties_virtual_machines_to_hosts(client):
    host = find(client, "SRV-DC-01")
    vm = client.post("/api/v1/devices", json={"name": "VM-1", "type": "VIRTUAL", "host_id": host["id"]}).json()
    links = client.get("/api/v1/topology").json()["links"]
    assert any(link["kind"] == "VIRTUAL" and link["b_id"] == vm["id"] for link in links)


def test_faceplate_upload_marks_and_copy(client):
    sw = find(client, "SW-ACC-01")
    assert client.get(f"/api/v1/devices/{sw['id']}/faceplate").status_code == 404
    r = client.put(f"/api/v1/devices/{sw['id']}/faceplate", content=SVG.encode())
    assert r.status_code == 200, r.text
    plate = r.json()
    assert (plate["width"], plate["height"], plate["ports"]) == (482, 44, [])
    svg = client.get(f"/api/v1/devices/{sw['id']}/faceplate/svg")
    assert svg.headers["content-type"].startswith("image/svg+xml") and "sandbox" in svg.headers[
        "content-security-policy"
    ]

    ports = ports_of(client, sw["id"])
    marks = [
        {"port_id": p["id"], "x": 0.05 + i * 0.02, "y": 0.2, "w": 0.015, "h": 0.3} for i, p in enumerate(ports[:3])
    ]
    r = client.put(f"/api/v1/devices/{sw['id']}/faceplate/ports", json={"ports": marks})
    assert r.status_code == 200, r.text
    assert [m["port_name"] for m in r.json()["ports"]] == [p["name"] for p in ports[:3]]
    # Another device's port, a port twice, a mark off the drawing
    other = ports_of(client, find(client, "SW-ACC-02")["id"])[0]
    bad = [{"port_id": other["id"], "x": 0, "y": 0, "w": 0.1, "h": 0.1}]
    assert client.put(f"/api/v1/devices/{sw['id']}/faceplate/ports", json={"ports": bad}).status_code == 422
    assert client.put(
        f"/api/v1/devices/{sw['id']}/faceplate/ports", json={"ports": [marks[0], marks[0]]}
    ).status_code == 422
    off = [{**marks[0], "x": 1.5}]
    assert client.put(f"/api/v1/devices/{sw['id']}/faceplate/ports", json={"ports": off}).status_code == 422

    # A new drawing keeps the marks
    r = client.put(f"/api/v1/devices/{sw['id']}/faceplate", content=SVG.replace("482", "480", 1).encode())
    assert r.json()["width"] == 480 and len(r.json()["ports"]) == 3

    # Copy to a switch of another vendor: ports match by number when names differ
    target = find(client, "SW-ACC-02")
    r = client.post(f"/api/v1/devices/{target['id']}/faceplate/copy", json={"from_device_id": sw["id"]})
    assert r.status_code == 200, r.text
    assert [m["port_number"] for m in r.json()["ports"]] == [p["number"] for p in ports[:3]]

    assert client.delete(f"/api/v1/devices/{sw['id']}/faceplate").status_code == 204
    assert client.get(f"/api/v1/devices/{sw['id']}/faceplate").status_code == 404


def test_faceplate_refuses_what_is_not_svg(client):
    sw = find(client, "SW-ACC-01")
    url = f"/api/v1/devices/{sw['id']}/faceplate"
    assert client.put(url, content=b"\x89PNG....").status_code == 415
    assert client.put(url, content=b"<html><body/></html>").status_code == 415
    bomb = b'<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><svg viewBox="0 0 1 1">&a;</svg>'
    assert client.put(url, content=bomb).status_code == 415
    assert client.put(url, content=b'<svg xmlns="http://www.w3.org/2000/svg"/>').status_code == 415
    assert client.put(url, content=b'<svg width="100px" height="20px"/>').json()["width"] == 100


def test_device_knows_its_switch_port(client):
    pc = find(client, "PC-BUH-01")
    sw = find(client, "SW-ACC-01")
    assert pc["switch_id"] == sw["id"] and pc["switch_port_name"] == "gi1/0/1"
    port = next(p for p in ports_of(client, sw["id"]) if p["id"] == pc["switch_port_id"])
    assert port["name"] == "gi1/0/1"
    assert sw["switch_port_id"] is None
