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


def test_real_detection_weighs_ports_and_ttl(seeded):
    settings = make_settings(runbook_mode=RunbookMode.REAL)
    open_ports = {
        "10.77.0.21": {3389},  # RDP alone, ping blocked: Windows
        "10.77.0.22": {22},  # SSH alone, ping blocked: Linux
        "10.77.0.26": {22, 3389},  # xrdp on Linux: the TTL says Linux
        "10.77.0.27": {22},  # OpenSSH on Windows: the TTL says Windows
        "10.77.0.28": {22, 3389},  # both and no ping: not guessed
        "10.77.0.29": {5985},  # WinRM is Windows whatever the TTL says
    }

    async def probe(host, port):
        return port in open_ports.get(host, set())

    async def ttl(host):
        return {
            "10.77.0.23": 128, "10.77.0.24": 64, "10.77.0.26": 62, "10.77.0.27": 126, "10.77.0.29": 64,
        }.get(host)  # fmt: skip

    names = {
        "w-rdp": "10.77.0.21", "l-ssh": "10.77.0.22", "w-ttl": "10.77.0.23", "l-ttl": "10.77.0.24",
        "silent": "10.77.0.25", "l-xrdp": "10.77.0.26", "w-ssh": "10.77.0.27", "both": "10.77.0.28",
        "w-winrm": "10.77.0.29",
    }  # fmt: skip
    with TestClient(create_app(settings), headers=AUTH) as c:
        c.app.state.pc_probe = probe
        c.app.state.pc_ttl = ttl
        for name, ip in names.items():
            assert c.post("/api/v1/devices", json={"name": name, "type": "WORKSTATION", "ip": ip}).status_code == 201
        summary = c.post("/api/v1/pcs/detect-os").json()
        got = {n: find(c, n)["metadata"].get("os") for n in names}
        assert got == {
            "w-rdp": "windows", "l-ssh": "astra", "w-ttl": "windows", "l-ttl": "astra", "silent": None,
            "l-xrdp": "astra", "w-ssh": "windows", "both": None, "w-winrm": "windows",
        }  # fmt: skip
        assert find(c, "w-ttl")["metadata"]["os_source"] == "ttl"
        assert find(c, "l-ssh")["metadata"]["os_source"] == "ports"
        # What the verdict rests on is kept, so a wrong one can be understood.
        note = find(c, "l-xrdp")["metadata"]["os_note"]
        assert "SSH 22" in note and "RDP 3389" in note and "TTL 62" in note
        assert summary["unknown"] >= 2
