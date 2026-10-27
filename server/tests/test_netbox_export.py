import csv
import io
import zipfile

from app.netbox.export import interface_type, slugify

from .conftest import find, ports_of


def test_slug_and_type_helpers():
    assert slugify("Серверная, 2 этаж") == "servernaya-2-etazh"
    assert slugify("???") == "x"
    assert interface_type(10000) == "10gbase-x-sfpp"
    assert interface_type(1000) == "1000base-t"
    assert interface_type(100) == "100base-tx"


def test_netbox_zip_has_every_file_and_the_inventory(client):
    r = client.get("/api/v1/export/netbox.zip")
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/zip"
    z = zipfile.ZipFile(io.BytesIO(r.content))
    names = set(z.namelist())
    assert {"sites.csv", "devices.csv", "interfaces.csv", "cables.csv", "vlans.csv", "README.txt"} <= names

    def rows(name: str) -> list[dict]:
        return list(csv.DictReader(io.StringIO(z.read(name).decode("utf-8-sig"))))

    devices = rows("devices.csv")
    sw = find(client, devices[0]["name"])
    assert sw["name"] in {d["name"] for d in devices}
    assert len(devices) == len(client.get("/api/v1/devices").json())
    assert all(d["role"] and d["manufacturer"] and d["site"] for d in devices)
    assert {int(v["vid"]) for v in rows("vlans.csv")} >= {1, 10, 20, 30, 40, 50, 99}
    expected_ports = sum(len(ports_of(client, d["id"])) for d in client.get("/api/v1/devices").json())
    assert len(rows("interfaces.csv")) == expected_ports
    for c in rows("cables.csv"):
        assert c["side_a_device"] and c["side_b_device"]


def test_netbox_export_needs_the_token(client):
    r = client.get("/api/v1/export/netbox.zip", headers={"Authorization": "Bearer wrong"})
    assert r.status_code == 401
