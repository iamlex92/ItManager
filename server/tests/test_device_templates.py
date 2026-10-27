from .conftest import ports_of


def test_template_crud_and_name_is_unique(client):
    body = {"name": "Eltex 24", "device_type": "SWITCH", "vendor": "Eltex", "model": "MES2324", "port_count": 24}
    r = client.post("/api/v1/device-templates", json=body)
    assert r.status_code == 201
    tid = r.json()["id"]
    assert r.json()["port_prefix"] == "Port" and r.json()["port_speed_mbps"] == 1000
    assert client.post("/api/v1/device-templates", json=body).status_code == 409
    assert [t["name"] for t in client.get("/api/v1/device-templates").json()] == ["Eltex 24"]

    r = client.patch(f"/api/v1/device-templates/{tid}", json={"port_count": 8, "port_prefix": "gi1/0/"})
    assert r.status_code == 200 and r.json()["port_count"] == 8
    assert client.delete(f"/api/v1/device-templates/{tid}").status_code == 204
    assert client.get("/api/v1/device-templates").json() == []
    assert client.delete(f"/api/v1/device-templates/{tid}").status_code == 404


def test_create_device_from_template_gets_the_ports(client):
    tid = client.post(
        "/api/v1/device-templates",
        json={
            "name": "Access 8",
            "device_type": "SWITCH",
            "vendor": "Cisco",
            "model": "C1000-8",
            "port_count": 8,
            "port_prefix": "Gi1/0/",
            "port_speed_mbps": 1000,
        },
    ).json()["id"]
    r = client.post(f"/api/v1/device-templates/{tid}/create-device", json={"name": "SW-TPL-01", "ip": "10.77.0.5"})
    assert r.status_code == 201
    dev = r.json()
    assert (dev["name"], dev["type"], dev["vendor"], dev["model"], dev["ip"]) == (
        "SW-TPL-01", "SWITCH", "Cisco", "C1000-8", "10.77.0.5"
    )  # fmt: skip
    ports = ports_of(client, dev["id"])
    assert [p["name"] for p in ports] == [f"Gi1/0/{n}" for n in range(1, 9)]
    assert {p["speed_mbps"] for p in ports} == {1000}


def test_template_without_ports_and_bad_input(client):
    tid = client.post("/api/v1/device-templates", json={"name": "PC", "device_type": "WORKSTATION"}).json()["id"]
    dev = client.post(f"/api/v1/device-templates/{tid}/create-device", json={"name": "PC-TPL-1"}).json()
    assert ports_of(client, dev["id"]) == []
    missing = "00000000-0000-0000-0000-000000000000"
    assert client.post(f"/api/v1/device-templates/{missing}/create-device", json={"name": "x"}).status_code == 404
    assert client.post("/api/v1/device-templates", json={"name": "Bad", "device_type": "TOASTER"}).status_code == 422
