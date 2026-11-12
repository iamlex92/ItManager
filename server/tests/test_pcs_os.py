"""Which computers run Windows and which Astra Linux: found by ports and TTL, remembered on the device."""

from fastapi.testclient import TestClient

from app.config import RunbookMode
from app.main import create_app

from .conftest import AUTH, find, make_settings


def test_emulated_detection_marks_every_computer(client):
    made = client.post("/api/v1/devices", json={"name": "astra-buh-09", "type": "WORKSTATION", "ip": "10.10.10.99"})
    assert made.status_code == 201, made.text
    r = client.post("/api/v1/pcs/detect-os")
    assert r.status_code == 200
    summary = r.json()
    assert summary["windows"] >= 1 and summary["astra"] == 1 and summary["unknown"] == 0
    assert (find(client, "astra-buh-09")["metadata"]["os"], find(client, "PC-BUH-01")["metadata"]["os"]) == (
        "astra",
        "windows",
    )
    # A second run changes nothing.
    assert client.post("/api/v1/pcs/detect-os").json()["changed"] == 0
    audit = client.get("/api/v1/audit", params={"kind": "runbook"}).json()
    assert any(a["action"] == "pc:detect-os" for a in audit)


def test_a_value_set_by_hand_is_kept(client):
    pc = find(client, "PC-BUH-01")
    client.patch(
        f"/api/v1/devices/{pc['id']}", json={"metadata": {**pc["metadata"], "os": "astra", "os_source": "manual"}}
    )
    summary = client.post("/api/v1/pcs/detect-os").json()
    assert summary["manual"] == 1
    assert find(client, "PC-BUH-01")["metadata"]["os"] == "astra"


def test_real_detection_uses_ports_then_ttl(seeded):
    settings = make_settings(runbook_mode=RunbookMode.REAL)
    open_ports = {"10.77.0.21": {3389}, "10.77.0.22": {22}}

    async def probe(host, port):
        return port in open_ports.get(host, set())

    async def ttl(host):
        return {"10.77.0.23": 128, "10.77.0.24": 64}.get(host)

    with TestClient(create_app(settings), headers=AUTH) as c:
        c.app.state.pc_probe = probe
        c.app.state.pc_ttl = ttl
        for name, ip in (("w-ports", "10.77.0.21"), ("l-ssh", "10.77.0.22"), ("w-ttl", "10.77.0.23"),
                         ("l-ttl", "10.77.0.24"), ("silent", "10.77.0.25")):  # fmt: skip
            assert c.post("/api/v1/devices", json={"name": name, "type": "WORKSTATION", "ip": ip}).status_code == 201
        summary = c.post("/api/v1/pcs/detect-os").json()
        got = {n: find(c, n)["metadata"].get("os") for n in ("w-ports", "l-ssh", "w-ttl", "l-ttl", "silent")}
        assert got == {"w-ports": "windows", "l-ssh": "astra", "w-ttl": "windows", "l-ttl": "astra", "silent": None}
        assert find(c, "w-ttl")["metadata"]["os_source"] == "ttl"
        assert find(c, "l-ssh")["metadata"]["os_source"] == "ports"
        assert summary["unknown"] >= 1
