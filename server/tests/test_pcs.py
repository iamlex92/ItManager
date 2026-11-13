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


def test_unreachable_computer_says_why():
    import asyncio

    from app.pcs.service import PcError, detect_platform

    async def closed(host: str, port: int) -> bool:
        return False

    async def known(host: str) -> str | None:
        return "192.168.82.55"

    async def unknown(host: str) -> str | None:
        return None

    settings = make_settings()

    async def run(resolve):
        return await detect_platform("PC-01", settings, False, {}, closed, resolve)

    for resolve, expected in ((known, ("192.168.82.55", "служба WinRM", "gpupdate")), (unknown, ("DNS",))):
        try:
            asyncio.run(run(resolve))
        except PcError as e:
            assert all(part in str(e) for part in expected), str(e)
        else:
            raise AssertionError("a computer that answers nowhere must be reported")


def test_ad_search_failure_is_an_answer_not_a_500(seeded, monkeypatch):
    """Whatever the LDAP side throws while listing the domain's computers, the person gets a message, not «500»."""
    from app.api import pcs as pcs_api

    class Broken:
        def __init__(self, settings, login):
            pass

        def windows_computers(self):
            raise TimeoutError("receive timed out")

    monkeypatch.setattr(pcs_api, "LdapDirectory", Broken)
    settings = make_settings(
        runbook_mode=RunbookMode.REAL, runbook_credential="domain-admin", ad_ldap_url="ldaps://dc.example.local"
    )
    with TestClient(create_app(settings), headers=AUTH) as c:
        c.post(
            "/api/v1/credentials", json={"name": "domain-admin", "kind": "winrm", "username": "F\\adm", "secret": "x"}
        )
        r = c.post("/api/v1/pcs/keys", json={"source": "ad"})
        assert r.status_code == 502, r.text
        assert r.json()["detail"]["code"] == "ad_failed" and "TimeoutError" in r.json()["detail"]["message"]


def test_ldap_directory_lists_windows_computers_and_checks_names():
    """The real directory has the two computer lookups the domain-PC code calls (they once lived in the Protocol)."""
    from types import SimpleNamespace

    from app.runbooks.real import LdapDirectory, Login

    class Standard:
        def paged_search(self, base, flt, attributes, paged_size, generator):
            assert base == "DC=f,DC=local" and "operatingSystem=*Windows*" in flt
            yield {"type": "searchResRef"}
            yield {"type": "searchResEntry", "attributes": {"name": "PC-1", "dNSHostName": "pc-1.f.local"}}
            yield {"type": "searchResEntry", "attributes": {"name": ["PC-2"], "dNSHostName": None}}
            yield {"type": "searchResEntry", "attributes": {"name": "PC-3"}}

    class Conn:
        extend = SimpleNamespace(standard=Standard())
        response: list = []

        def search(self, base, flt, search_scope, attributes, size_limit):
            self.last = flt
            self.response = [{"type": "searchResEntry", "dn": "CN=PC-1,DC=f"}] if "pc-1" in flt.lower() else []

    settings = make_settings(ad_base_dn="DC=f,DC=local")
    d = LdapDirectory(settings, Login("u", "p"), connection=Conn())
    assert d.windows_computers() == ["pc-1.f.local", "PC-2", "PC-3"]
    assert d.windows_computers(limit=2) == ["pc-1.f.local", "PC-2"]
    assert d.computer_exists("pc-1.f.local") is True
    assert d.computer_exists("nobody") is False


def _run_keys(client):
    job = client.post("/api/v1/pcs/keys", json={"source": "subnet", "prefix": "10.10.10"}).json()
    for _ in range(100):
        job = client.get(f"/api/v1/pcs/keys/{job['id']}").json()
        if job["status"] != "running":
            break
        time.sleep(0.05)
    return job


def test_key_inventory_reconciled_with_the_license_register(client):
    job = _run_keys(client)
    base = f"/api/v1/pcs/keys/{job['id']}/reconcile"
    r = client.get(base).json()
    # Nothing in the register yet: every finding is unmatched and can be entered.
    assert r["licenses"] == [] and r["unmatched"] and all(u["adoptable"] for u in r["unmatched"])
    win = next(u for u in r["unmatched"] if u["product"] == "windows")
    assert win["hint"].startswith("••••")

    # Enter a Windows key found on a computer: it is linked to the computers that have it.
    done = client.post(f"{base}/adopt", json={"product": "windows", "ref": win["ref"]}).json()
    lic = next(x for x in done["licenses"] if x["software"] == "Windows")
    assert lic["registered"] == lic["found"] == len(win["hosts"]) and not lic["to_link"] and not lic["missing"]
    assert all(u["ref"] != win["ref"] for u in done["unmatched"])
    again = client.post(f"{base}/adopt", json={"product": "windows", "ref": win["ref"]})
    assert again.status_code == 409
    assert client.post(f"{base}/adopt", json={"product": "windows", "ref": "NOSUCH"}).status_code == 404

    # A license typed in by hand is found by its key; the computers are not yet linked until «apply».
    other = next(u for u in done["unmatched"] if u["product"] == "windows")
    typed = other["ref"]
    made = client.post(
        "/api/v1/licenses",
        json={
            "software": "Windows 10 Pro",
            "kind": "per_device",
            "key": "-".join([typed[i : i + 5] for i in range(0, 25, 5)]),
        },
    ).json()
    r2 = client.get(base).json()
    item = next(x for x in r2["licenses"] if x["id"] == made["id"])
    assert item["found"] == len(other["hosts"]) and item["registered"] == 0 and item["to_link"]
    applied = client.post(f"{base}/apply", json={}).json()
    item = next(x for x in applied["licenses"] if x["id"] == made["id"])
    assert item["registered"] == item["found"] and not item["to_link"]
    assert client.get("/api/v1/licenses").json()  # the register itself sees the links

    # A computer scanned without the license, but listed for it in the register, is reported as missing.
    dev = client.get("/api/v1/devices").json()
    pc = next(d for d in dev if d["name"] == "PC-BUH-01")
    lone = client.post(
        "/api/v1/licenses",
        json={"software": "Windows", "key": "AAAAA-BBBBB-CCCCC-DDDDD-EEEEE", "device_ids": [pc["id"]]},
    ).json()
    item = next(x for x in client.get(base).json()["licenses"] if x["id"] == lone["id"])
    assert item["found"] == 0 and "PC-BUH-01" in item["missing"]

    # 1С, by the program's name; the journal records the reconcile but never a key.
    one = client.post("/api/v1/licenses", json={"software": "1С:Предприятие 8.3", "seats": 1}).json()
    item = next(x for x in client.get(base).json()["licenses"] if x["id"] == one["id"])
    assert item["checkable"] and item["found"] >= 1 and item["over"] is (item["found"] > 1)
