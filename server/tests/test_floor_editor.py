"""The map editor: plan image upload, plan placement, drawings, devices added by hand and cables drawn."""

import struct
import zlib

from .conftest import find


def png(width: int, height: int) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    raw = b"".join(b"\x00" + b"\xff" * (width * 3) for _ in range(height))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def new_floor(client) -> dict:
    r = client.post("/api/v1/floors", json={"building_name": "Здание", "floor_number": 2})
    assert r.status_code == 201, r.text
    return r.json()


def test_plan_upload_placement_and_removal(client):
    floor = new_floor(client)
    assert floor["plan_image_url"] == "" and floor["drawings"] == []
    image = png(40, 20)
    r = client.put(f"/api/v1/floors/{floor['id']}/plan-image?width=40&height=20", content=image)
    assert r.status_code == 200, r.text
    f = r.json()
    # A new floor takes the image's proportions, 1600 map pixels wide by default.
    assert (f["plan_x"], f["plan_y"], f["plan_w"], f["plan_h"]) == (0, 0, 1600, 800)
    assert (f["width_px"], f["height_px"]) == (1600, 800)
    url = f["plan_image_url"]
    assert url.startswith(f"/api/v1/floors/{floor['id']}/plan-image?v=")

    got = client.get(url)
    assert got.status_code == 200 and got.content == image and got.headers["content-type"] == "image/png"
    assert got.headers["x-content-type-options"] == "nosniff"
    assert client.get(url, headers={"Authorization": ""}).status_code == 401

    # Moving and scaling the plan is a PATCH; a new image keeps that placement.
    r = client.patch(f"/api/v1/floors/{floor['id']}", json={"plan_x": 100, "plan_y": 50, "plan_w": 800, "plan_h": 400})
    assert r.status_code == 200
    r = client.put(f"/api/v1/floors/{floor['id']}/plan-image", content=b'<svg xmlns="http://www.w3.org/2000/svg"/>')
    assert r.status_code == 200 and r.json()["plan_w"] == 800 and r.json()["plan_image_url"] != url
    assert client.get(r.json()["plan_image_url"]).headers["content-type"].startswith("image/svg+xml")

    r = client.delete(f"/api/v1/floors/{floor['id']}/plan-image")
    assert r.status_code == 200 and r.json()["plan_image_url"] == "" and r.json()["plan_w"] is None
    assert client.get(url).status_code == 404


def test_plan_upload_rejects_other_files(client):
    floor = new_floor(client)
    for body in (b"", b"%PDF-1.7 ...", b"MZ\x90\x00", b"<html><script>alert(1)</script></html>"):
        r = client.put(f"/api/v1/floors/{floor['id']}/plan-image", content=body)
        assert r.status_code in (415, 422), (body, r.status_code)
    assert (
        client.put("/api/v1/floors/00000000-0000-0000-0000-000000000000/plan-image", content=png(2, 2)).status_code
        == 404
    )


def test_drawings_round_trip_and_validation(client):
    floor = new_floor(client)
    lines = [{"color": "#FF453A", "width": 6, "points": [10, 10, 200.04, 10, 200, 150]}]
    r = client.patch(f"/api/v1/floors/{floor['id']}", json={"drawings": lines})
    assert r.status_code == 200 and r.json()["drawings"] == [
        {"color": "#FF453A", "width": 6.0, "points": [10, 10, 200.0, 10, 200, 150]}
    ]
    assert client.patch(f"/api/v1/floors/{floor['id']}", json={"drawings": [{"points": [1, 2, 3]}]}).status_code == 422
    assert (
        client.patch(
            f"/api/v1/floors/{floor['id']}", json={"drawings": [{"color": "red", "points": [1, 2, 3, 4]}]}
        ).status_code
        == 422
    )
    assert client.patch(f"/api/v1/floors/{floor['id']}", json={"drawings": []}).json()["drawings"] == []


def test_devices_added_by_hand_and_linked(client):
    floor = new_floor(client)
    made = []
    for name, kind in (("CAM-NEW", "CAMERA"), ("AP-NEW", "ACCESS_POINT"), ("PP-NEW", "PATCH_PANEL")):
        r = client.post(
            "/api/v1/devices", json={"name": name, "type": kind, "floor_id": floor["id"], "x": 100, "y": 200}
        )
        assert r.status_code == 201, r.text
        made.append(r.json())
    cam, ap, pp = made

    r = client.post("/api/v1/connections/link", json={"device_a_id": cam["id"], "device_b_id": pp["id"]})
    assert r.status_code == 201, r.text
    link = r.json()
    assert link["device_a_id"] == cam["id"] and link["device_b_id"] == pp["id"] and link["cable_type"] == "Cat6"
    # A patch panel port takes a front and a rear cable, so the next cable lands on the same port.
    r = client.post(
        "/api/v1/connections/link", json={"device_a_id": ap["id"], "device_b_id": pp["id"], "cable_type": "OM3"}
    )
    assert r.status_code == 201
    pp_ports = client.get(f"/api/v1/devices/{pp['id']}/ports").json()
    assert len(pp_ports) == 1 and pp_ports[0]["name"] == "1"
    assert [p["name"] for p in client.get(f"/api/v1/devices/{cam['id']}/ports").json()] == ["eth0"]

    again = client.post("/api/v1/connections/link", json={"device_a_id": pp["id"], "device_b_id": cam["id"]})
    assert again.status_code == 409 and again.json()["detail"]["code"] == "already_linked"
    same = client.post("/api/v1/connections/link", json={"device_a_id": cam["id"], "device_b_id": cam["id"]})
    assert same.status_code == 422

    # Moving, editing and deleting are the existing device endpoints.
    assert (
        client.patch(f"/api/v1/devices/{cam['id']}", json={"x": 300.5, "y": 40, "name": "CAM-HALL"}).json()["x"]
        == 300.5
    )
    assert client.delete(f"/api/v1/connections/{link['id']}").status_code == 204
    assert client.delete(f"/api/v1/devices/{cam['id']}").status_code == 204


def test_link_to_a_full_switch_is_refused(client):
    switch = find(client, "SW-ACC-03")
    ports = client.get(f"/api/v1/devices/{switch['id']}/ports").json()
    cams = []
    for i in range(len(ports) + 1):
        r = client.post("/api/v1/devices", json={"name": f"CAM-T{i}", "type": "CAMERA", "x": 1, "y": 1})
        cams.append(r.json())
    codes = [
        client.post("/api/v1/connections/link", json={"device_a_id": c["id"], "device_b_id": switch["id"]}).status_code
        for c in cams
    ]
    assert codes[-1] == 409 and 201 in codes
    # No new port was invented on the switch.
    assert len(client.get(f"/api/v1/devices/{switch['id']}/ports").json()) == len(ports)


def test_cable_route_with_bends_on_two_floors(client):
    ground = client.get("/api/v1/floors").json()[0]
    upper = new_floor(client)
    sw = find(client, "SW-ACC-03")
    r = client.post(
        "/api/v1/devices", json={"name": "AP-2F", "type": "ACCESS_POINT", "floor_id": upper["id"], "x": 400, "y": 300}
    )
    ap = r.json()
    link = client.post("/api/v1/connections/link", json={"device_a_id": sw["id"], "device_b_id": ap["id"]}).json()
    assert link["waypoints"] == []

    route = [
        {"x": 120.04, "y": 80},
        {"x": 900, "y": 80, "floor_id": ground["id"]},
        {"x": 50, "y": 60, "floor_id": upper["id"]},
    ]
    r = client.patch(f"/api/v1/connections/{link['id']}", json={"waypoints": route})
    assert r.status_code == 200, r.text
    assert r.json()["waypoints"] == [
        {"x": 120.0, "y": 80.0, "floor_id": None},
        {"x": 900.0, "y": 80.0, "floor_id": ground["id"]},
        {"x": 50.0, "y": 60.0, "floor_id": upper["id"]},
    ]
    # Other fields are left as they were; listing carries the route too.
    assert r.json()["cable_type"] == "Cat6"
    listed = client.get("/api/v1/connections", params={"device_id": ap["id"]}).json()
    assert len(listed[0]["waypoints"]) == 3

    too_many = [{"x": i, "y": i} for i in range(201)]
    assert client.patch(f"/api/v1/connections/{link['id']}", json={"waypoints": too_many}).status_code == 422
    assert client.patch(f"/api/v1/connections/{link['id']}", json={"waypoints": [{"x": 1}]}).status_code == 422
    # Straight again.
    assert client.patch(f"/api/v1/connections/{link['id']}", json={"waypoints": []}).json()["waypoints"] == []


def test_floors_are_named_ordered_and_kept_while_in_use(client):
    first = client.get("/api/v1/floors").json()[0]
    basement = client.post("/api/v1/floors", json={"building_name": "АБК", "floor_number": 0, "name": "Подвал"}).json()
    second = client.post("/api/v1/floors", json={"building_name": "АБК", "floor_number": 2}).json()
    assert basement["name"] == "Подвал" and second["name"] is None
    # Bottom to top.
    assert [f["id"] for f in client.get("/api/v1/floors").json()] == [basement["id"], first["id"], second["id"]]

    r = client.patch(f"/api/v1/floors/{second['id']}", json={"name": "2 этаж, серверная", "floor_number": -1})
    assert r.json()["name"] == "2 этаж, серверная"
    assert client.get("/api/v1/floors").json()[0]["id"] == second["id"]
    # Clearing the name brings back "floor N".
    assert client.patch(f"/api/v1/floors/{second['id']}", json={"name": "  "}).json()["name"] is None

    # A floor with devices on it is not deleted: they would lose their place.
    busy = client.delete(f"/api/v1/floors/{first['id']}")
    assert busy.status_code == 409 and busy.json()["detail"]["code"] == "floor_not_empty"
    assert client.delete(f"/api/v1/floors/{basement['id']}").status_code == 204


def test_group_edit_and_delete_of_devices(client):
    floor = new_floor(client)
    made = [
        client.post("/api/v1/devices", json={"name": f"HOST-{i}", "type": "UNKNOWN", "x": 10 * i, "y": 5}).json()
        for i in range(3)
    ]
    ids = [d["id"] for d in made]
    r = client.post(
        "/api/v1/devices/bulk", json={"ids": ids[:2], "set": {"type": "UPS", "floor_id": floor["id"], "vendor": "APC"}}
    )
    assert r.status_code == 200 and r.json() == {"changed": 2, "missing": 0}, r.text
    after = {d["id"]: d for d in client.get("/api/v1/devices").json()}
    assert [after[i]["type"] for i in ids] == ["UPS", "UPS", "UNKNOWN"]
    assert after[ids[0]]["floor_id"] == floor["id"] and after[ids[0]]["vendor"] == "APC"
    # Position and name stay as they were.
    assert after[ids[1]]["name"] == "HOST-1" and after[ids[1]]["x"] == 10

    assert client.post("/api/v1/devices/bulk", json={"ids": ids, "set": {}}).status_code == 422
    assert client.post("/api/v1/devices/bulk", json={"ids": ids, "set": {"type": "TOASTER"}}).status_code == 422
    gone = "00000000-0000-0000-0000-000000000000"
    assert client.post("/api/v1/devices/bulk", json={"ids": ids, "set": {"floor_id": gone}}).status_code == 404

    r = client.post("/api/v1/devices/bulk-delete", json={"ids": [*ids[:2], gone]})
    assert r.json() == {"changed": 2, "missing": 1}
    left = {d["id"] for d in client.get("/api/v1/devices").json()}
    assert ids[2] in left and not left & set(ids[:2])


def test_virtual_machine_is_bound_to_its_host(client):
    host = client.post("/api/v1/devices", json={"name": "ESXI-01", "type": "SERVER", "x": 1, "y": 1}).json()
    vm = client.post(
        "/api/v1/devices", json={"name": "VM-1C", "type": "VIRTUAL", "host_id": host["id"], "x": 2, "y": 2}
    ).json()
    assert vm["type"] == "VIRTUAL" and vm["host_id"] == host["id"]
    assert client.patch(f"/api/v1/devices/{vm['id']}", json={"host_id": vm["id"]}).status_code == 422
    gone = "00000000-0000-0000-0000-000000000000"
    assert client.patch(f"/api/v1/devices/{vm['id']}", json={"host_id": gone}).status_code == 404
    r = client.post("/api/v1/devices/bulk", json={"ids": [vm["id"]], "set": {"host_id": host["id"]}})
    assert r.status_code == 200
    # Removing the host leaves the machine without one.
    client.delete(f"/api/v1/devices/{host['id']}")
    assert client.get(f"/api/v1/devices/{vm['id']}").json()["host_id"] is None


def test_zones_comment_and_rack(client):
    floor = new_floor(client)
    zone = {
        "id": "z1",
        "name": "Серверная",
        "is_room": True,
        "color": "#0A84FF",
        "points": [0, 0, 100, 0, 100, 80, 0, 80],
    }
    r = client.patch(f"/api/v1/floors/{floor['id']}", json={"zones": [zone]})
    assert r.status_code == 200 and r.json()["zones"] == [zone]
    assert client.get(f"/api/v1/floors/{floor['id']}").json()["zones"][0]["name"] == "Серверная"
    bad = {**zone, "points": [0, 0, 1, 1]}
    assert client.patch(f"/api/v1/floors/{floor['id']}", json={"zones": [bad]}).status_code == 422

    rack = client.post("/api/v1/devices", json={"name": "Стойка 1", "type": "RACK", "x": 5, "y": 5}).json()
    assert rack["rack_units"] == 42
    sw = client.post(
        "/api/v1/devices",
        json={
            "name": "SW-A",
            "type": "SWITCH",
            "comment": "Порт 24 — аплинк",
            "rack_id": rack["id"],
            "rack_u": 40,
            "rack_h": 2,
        },
    ).json()
    assert sw["comment"] == "Порт 24 — аплинк" and (sw["rack_id"], sw["rack_u"], sw["rack_h"]) == (rack["id"], 40, 2)

    def mount(name: str, u: int, h: int = 1):
        return client.post(
            "/api/v1/devices",
            json={"name": name, "type": "SERVER", "rack_id": rack["id"], "rack_u": u, "rack_h": h},
        )

    assert mount("S-1", 41).status_code == 409  # unit 41 belongs to SW-A (40-41)
    assert mount("S-2", 42, 2).status_code == 422  # sticks out of a 42 U rack
    assert mount("S-3", 42).status_code == 201
    assert (
        client.post(
            "/api/v1/devices", json={"name": "S-4", "type": "SERVER", "rack_id": sw["id"], "rack_u": 1}
        ).status_code
        == 422
    )
    assert (
        client.post("/api/v1/devices", json={"name": "S-5", "type": "SERVER", "rack_id": rack["id"]}).status_code == 422
    )

    # Moving a device inside the rack, then taking it out.
    r = client.patch(f"/api/v1/devices/{sw['id']}", json={"rack_u": 10})
    assert r.status_code == 200 and r.json()["rack_u"] == 10
    r = client.patch(f"/api/v1/devices/{sw['id']}", json={"rack_id": None})
    assert r.status_code == 200 and r.json()["rack_id"] is None and r.json()["rack_u"] is None
    # The rack cannot shrink below a mounted device.
    assert client.patch(f"/api/v1/devices/{rack['id']}", json={"rack_units": 20}).status_code == 409
    assert client.patch(f"/api/v1/devices/{rack['id']}", json={"rack_units": 48}).status_code == 200
    # Deleting a rack leaves its devices unmounted.
    assert client.delete(f"/api/v1/devices/{rack['id']}").status_code == 204
    left = [d for d in client.get("/api/v1/devices").json() if d["name"] == "S-3"][0]
    assert left["rack_id"] is None
