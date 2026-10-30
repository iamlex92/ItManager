"""Domain computers (the old Connect-DomainPC.ps1): emulated answers, the confirmation and write switches,
the real WinRM path with a fake shell, the subnet sweep and the key inventory with its CSV."""

import json
import time

from fastapi.testclient import TestClient

from app.config import RunbookMode
from app.main import create_app
from app.pcs import scripts
from app.pcs.backend import normalize_licences, parse_output

from .conftest import AUTH, make_settings


def test_emulated_actions_and_confirmations(client):
    st = client.get("/api/v1/pcs/status").json()
    assert st == {"mode": "emulator", "writes_enabled": True, "login_configured": False, "ad_configured": False}

    info = client.post("/api/v1/pcs/pc-buh-01.factory.local/info", json={}).json()
    assert info["ok"] and info["simulated"] and info["data"]["computer"] == "PC-BUH-01"
    keys = {"os", "uptime_minutes", "cpu", "ram_gb", "windows_registry_key", "office_keys", "one_c"}
    assert keys <= set(info["data"])
    disks = client.post("/api/v1/pcs/PC-BUH-01/disks", json={}).json()["data"]
    assert disks[0]["drive"] == "C:" and disks[0]["total_gb"] > disks[0]["free_gb"]
    procs = client.post("/api/v1/pcs/PC-BUH-01/processes", json={}).json()["data"]
    assert len(procs) == 20 and procs[0]["ram_mb"] >= procs[-1]["ram_mb"]

    # Changes that hurt if mistaken need the confirmation; gpupdate does not.
    r = client.post("/api/v1/pcs/PC-BUH-01/reboot", json={})
    assert r.status_code == 400 and r.json()["detail"]["code"] == "confirmation_required"
    assert client.post("/api/v1/pcs/PC-BUH-01/gpupdate", json={}).json()["ok"]
    killed = client.post("/api/v1/pcs/PC-BUH-01/kill", json={"arg": "chrome.exe", "confirm": True}).json()
    assert killed["data"] == {"stopped": 1, "name": "chrome"}
    after = client.post("/api/v1/pcs/PC-BUH-01/processes", json={}).json()["data"]
    assert "chrome" not in {p["name"] for p in after}

    bad = client.post("/api/v1/pcs/PC-BUH-01/kill", json={"arg": "C:\\evil.exe; rm", "confirm": True})
    assert bad.status_code == 422
    assert client.post("/api/v1/pcs/bad host!/info", json={}).status_code == 422
    out = client.post("/api/v1/pcs/PC-BUH-01/command", json={"arg": "Get-Service spooler", "confirm": True}).json()
    assert "Get-Service spooler" in out["data"]["output"]

    # Audited under «Сценарии»; reads keep no output (the keys stay out of the log), the console keeps its command.
    audit = client.get("/api/v1/audit", params={"kind": "runbook"}).json()
    by_action = {a["action"]: a for a in audit}
    assert by_action["pc:info"]["title"] == "ПК: системная информация" and by_action["pc:info"]["stdout"] is None
    assert by_action["pc:command"]["parameters"]["arg"] == "Get-Service spooler"
    assert by_action["pc:kill"]["parameters"]["confirmed"] is True


def test_sweep_and_key_inventory(client):
    hits = client.post("/api/v1/pcs/sweep", json={"prefix": "10.10.10"}).json()
    assert hits and all(h["ip"].startswith("10.10.10.") for h in hits)
    assert any(h["device_name"] == "PC-BUH-01" for h in hits)
    assert client.post("/api/v1/pcs/sweep", json={"prefix": "8.8.8"}).status_code == 422

    job = client.post("/api/v1/pcs/keys", json={"source": "subnet", "prefix": "10.10.10"}).json()
    for _ in range(100):
        job = client.get(f"/api/v1/pcs/keys/{job['id']}").json()
        if job["status"] != "running":
            break
        time.sleep(0.05)
    assert job["status"] == "done" and job["simulated"] and job["done"] == job["online"] == len(hits)
    row = job["rows"][0]
    assert row["ok"] and row["computer"] and row["windows_registry_key"] and row["office"]
    csv = client.get(f"/api/v1/pcs/keys/{job['id']}/csv")
    assert csv.status_code == 200 and "attachment" in csv.headers["content-disposition"]
    text = csv.content.decode("utf-8")
    assert text.startswith("\ufeffИмя ПК;Пользователь;ОС;Windows (BIOS);Windows (Реестр);MS Office;Лицензии 1С")
    assert text.count("\r\n") == len(job["rows"]) + 1

    ad = client.post("/api/v1/pcs/keys", json={"source": "ad"}).json()
    assert ad["source"] == "ad"


async def winrm_only(host, port):
    return port == 5985


def test_real_mode_sends_the_login_only_to_known_computers(seeded):
    settings = make_settings(runbook_mode=RunbookMode.REAL, runbook_credential="domain-admin")
    with TestClient(create_app(settings), headers=AUTH) as c:
        c.app.state.pc_probe = winrm_only
        no_login = c.post("/api/v1/pcs/PC-BUH-01/info", json={})
        assert no_login.status_code == 409 and "RUNBOOK_CREDENTIAL" in no_login.json()["detail"]["message"]
        c.post("/api/v1/credentials", json={"name": "domain-admin", "kind": "winrm", "username": "FACTORY\\adm",
                                            "secret": "p@ss"})  # fmt: skip
        calls = []

        def fake_factory(login):
            def run(host, script):
                calls.append((host, login.username, script))
                if "Win32_LogicalDisk -Filter 'DriveType=3'" in script:
                    return 0, json.dumps({"drive": "C:", "label": "", "free_gb": 10.5, "total_gb": 100}), ""
                return 0, "\ufeff" + json.dumps({
                    "computer": "PC-BUH-01", "user": "", "os": "Windows 11 Pro", "windows_bios_key": None,
                    "windows_registry_key": "AAAAA-BBBBB-CCCCC-DDDDD-EEEEE", "office_installed": True,
                    "office_keys": "7G2HE", "one_c": None, "uptime_minutes": 90, "cpu": "i5", "ram_gb": 16,
                }), ""  # fmt: skip

            return run

        c.app.state.pcs.shell_factory = fake_factory
        assert c.get("/api/v1/pcs/status").json()["mode"] == "real"

        unknown = c.post("/api/v1/pcs/evil.example.com/info", json={})
        assert unknown.status_code == 403 and unknown.json()["detail"]["code"] == "unknown_host"
        assert calls == []  # nothing was sent to it

        info = c.post("/api/v1/pcs/PC-BUH-01/info", json={}).json()
        assert info["ok"] and not info["simulated"]
        assert info["data"]["office_keys"] == ["7G2HE"] and info["data"]["one_c"] == [] and info["data"]["user"] is None
        disks = c.post("/api/v1/pcs/10.10.10.21/disks", json={}).json()  # the inventory knows it by IP too
        assert disks["data"] == [{"drive": "C:", "label": "", "free_gb": 10.5, "total_gb": 100}]
        assert calls[0][:2] == ("PC-BUH-01", "FACTORY\\adm")

        # Changes are off until ALLOW_RUNBOOK_WRITES=true.
        r = c.post("/api/v1/pcs/PC-BUH-01/reboot", json={"confirm": True})
        assert r.status_code == 403 and r.json()["detail"]["code"] == "writes_disabled"


def test_scripts_carry_arguments_safely():
    s = scripts.kill("chrome")
    assert "FromBase64String('Y2hyb21l')" in s and "chrome" not in s.split("FromBase64String")[0]
    assert "Invoke-Expression" in scripts.command("Get-Date; Remove-Item x")
    assert "Remove-Item x" not in scripts.command("Get-Date; Remove-Item x")
    assert "ConvertTo-Json" in scripts.INFO and "OA3xOriginalProductKey" in scripts.KEYS
    assert parse_output('WARNING: x\n{"a": 1}') == {"a": 1}
    norm = normalize_licences({"windows_bios_key": "  ", "office_keys": "ABCDE", "one_c": {"name": "1.lic"}})
    assert norm["windows_bios_key"] is None and norm["office_keys"] == ["ABCDE"]
    assert norm["one_c"] == [{"name": "1.lic"}]
