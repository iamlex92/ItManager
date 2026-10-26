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
