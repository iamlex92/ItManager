"""Group Policy: the emulated domain end to end, the gates on changes, and the real path with a fake WinRM shell."""

import base64
import json
import re
import time

from fastapi.testclient import TestClient

from app.config import RunbookMode
from app.gpo import scripts
from app.gpo.backend import GpoError, check_registry, check_target
from app.main import create_app

from .conftest import AUTH, make_settings

USB = "8f2d6b41-3c7e-4a55-b1d2-6e9a0c4f7d02"
BUH = "5c3e0f0a-8a3b-4b8e-9d1e-0b6f4a1e2c01"
OU_BUH = "OU=Бухгалтерия,OU=Компьютеры,DC=factory,DC=local"


def test_view_edit_and_apply_in_the_emulated_domain(client):
    s = client.get("/api/v1/gpo").json()
    assert s["mode"] == "emulator" and s["writes_enabled"] is True
    names = {g["name"]: g for g in s["gpos"]}
    assert "Default Domain Policy" in names and names["Запрет USB-накопителей"]["links"][0]["enforced"] is True
    assert any(o["dn"] == OU_BUH for o in client.get("/api/v1/gpo/ous").json())

    d = client.get(f"/api/v1/gpo/{BUH}").json()
    assert d["simulated"] and d["gpo"]["status"] == "UserSettingsDisabled"
    assert {r["name"] for r in d["registry"]} >= {"Restricted", "ServerList"}
    assert "<h1>" in client.get(f"/api/v1/gpo/{BUH}/report").text

    # Who it reaches: the accounting PC of the inventory; what that PC applied.
    scope = client.get(f"/api/v1/gpo/{BUH}/computers").json()
    reached = [c["name"] for c in scope["computers"]]
    assert "PC-BUH-01" in reached and all("BUH" in n for n in reached)
    applied = client.get("/api/v1/gpo/computer/PC-BUH-01").json()
    assert {g["name"] for g in applied["gpos"]} >= {
        "Default Domain Policy",
        "Бухгалтерия: подключение дисков и принтеров",
    }

    # Changes need the confirmation.
    r = client.put(
        f"/api/v1/gpo/{BUH}/registry", json={"key": "HKLM\\Software\\Policies\\X", "name": "A", "value": "1"}
    )
    assert r.status_code == 400 and r.json()["detail"]["code"] == "confirmation_required"

    d = client.put(
        f"/api/v1/gpo/{BUH}/registry",
        json={
            "key": "HKEY_LOCAL_MACHINE\\Software\\Policies\\Microsoft\\Windows\\Personalization",
            "name": "NoLockScreen",
            "type": "DWord",
            "value": "1",
            "confirm": True,
        },
    ).json()
    row = next(r for r in d["registry"] if r["name"] == "NoLockScreen")
    assert row["key"].startswith("HKLM\\Software") and row["value"] == "1"
    d = client.post(
        f"/api/v1/gpo/{BUH}/registry/remove", json={"key": row["key"], "name": "NoLockScreen", "confirm": True}
    ).json()
    assert "NoLockScreen" not in {r["name"] for r in d["registry"]}
    bad = client.put(
        f"/api/v1/gpo/{BUH}/registry",
        json={"key": "HKLM\\X", "name": "N", "type": "DWord", "value": "abc", "confirm": True},
    )
    assert bad.status_code == 409 and "целое" in bad.json()["detail"]["message"]

    d = client.put(f"/api/v1/gpo/{BUH}/status", json={"status": "AllSettingsDisabled", "confirm": True}).json()
    assert d["gpo"]["status"] == "AllSettingsDisabled"
    d = client.patch(f"/api/v1/gpo/{BUH}/links", json={"target": OU_BUH, "enabled": False, "confirm": True}).json()
    assert d["gpo"]["links"][0]["enabled"] is False
    assert client.get(f"/api/v1/gpo/{BUH}/computers").json()["computers"] == []
    d = client.post(f"/api/v1/gpo/{BUH}/links/remove", json={"target": OU_BUH, "confirm": True}).json()
    assert d["gpo"]["links"] == []

    created = client.post("/api/v1/gpo", json={"name": "Цех: экран блокировки", "confirm": True})
    assert created.status_code == 201
    gid = created.json()["gpo"]["id"]
    shop = "OU=Цех,OU=Компьютеры,DC=factory,DC=local"
    d = client.post(f"/api/v1/gpo/{gid}/links", json={"target": shop, "enforced": True, "confirm": True}).json()
    assert d["gpo"]["links"][0]["enforced"] is True

    # Apply: gpupdate on the computers it reaches, as a job.
    job = client.post(f"/api/v1/gpo/{USB}/apply", json={"confirm": True})
    assert job.status_code == 202, job.text
    job = job.json()
    for _ in range(50):
        job = client.get(f"/api/v1/gpo/apply/{job['id']}").json()
        if job["status"] == "done":
            break
        time.sleep(0.05)
    assert job["status"] == "done" and job["done"] == job["total"] > 0 and all(r["ok"] for r in job["rows"])
    outside = client.post(f"/api/v1/gpo/{USB}/apply", json={"computers": ["SRV-NONEXISTENT"], "confirm": True})
    assert outside.status_code == 422

    assert client.post(f"/api/v1/gpo/{gid}/delete", json={"confirm": True}).status_code == 204
    assert gid not in {g["id"] for g in client.get("/api/v1/gpo").json()["gpos"]}

    audit = client.get("/api/v1/audit", params={"kind": "runbook"}).json()
    titles = {a["title"] for a in audit}
    assert {
        "GPO: параметр реестра",
        "GPO: состояние",
        "GPO: применение на компьютерах",
        "GPO: удаление политики",
    } <= titles


def test_checks_before_anything_reaches_powershell():
    for bad in ("HKLM", "HKCR\\Software\\X", "Software\\Policies"):
        try:
            check_registry(bad, "N", "String", "v")
            raise AssertionError(bad)
        except GpoError:
            pass
    assert check_registry("hkcu\\Software\\Policies\\A", "N", "QWord", "0x10")[3] == 16
    for bad in ("OU=X", "DC=factory;rm", "CN=x,OU=y"):
        try:
            check_target(bad)
            raise AssertionError(bad)
        except GpoError:
            pass
    assert check_target("OU=Бухгалтерия,DC=factory,DC=local")


def _decoded_args(script: str) -> dict:
    raw = re.search(r"FromBase64String\('([^']+)'\)", script).group(1)
    return json.loads(base64.b64decode(raw))


def test_real_mode_runs_on_the_gpo_host_with_arguments_as_data(seeded):
    settings = make_settings(
        pc_mode="real",
        runbook_mode=RunbookMode.REAL,
        runbook_credential="domain-admin",
        gpo_host="dc01.factory.local",
        allow_runbook_writes=False,
    )
    with TestClient(create_app(settings), headers=AUTH) as c:
        assert c.get("/api/v1/gpo").status_code == 409  # no login stored yet
        c.post(
            "/api/v1/credentials",
            json={"name": "domain-admin", "kind": "winrm", "username": "FACTORY\\adm", "secret": "p@ss"},
        )
        calls = []

        def factory(login):
            def run(host, script):
                calls.append((host, script))
                if "Get-GPO -All" in script:
                    return (
                        0,
                        json.dumps(
                            {
                                "gpos": {
                                    "id": BUH,
                                    "name": "Бухгалтерия",
                                    "status": "AllSettingsEnabled",
                                    "links": {
                                        "target": OU_BUH,
                                        "target_name": "factory.local/Бух",
                                        "enabled": True,
                                        "enforced": False,
                                        "order": 1,
                                    },
                                }
                            }
                        ),
                        "",
                    )
                return 0, json.dumps({"ok": True}), ""

            return run

        c.app.state.gpo.shell_factory = factory
        s = c.get("/api/v1/gpo").json()
        assert s["mode"] == "real" and s["host"] == "dc01.factory.local" and s["writes_enabled"] is False
        assert s["gpos"][0]["links"][0]["target"] == OU_BUH  # one-item arrays from ConvertTo-Json are lists here
        assert calls[0][0] == "dc01.factory.local"

        r = c.put(
            f"/api/v1/gpo/{BUH}/registry",
            json={"key": "HKLM\\Software\\Policies\\A", "name": "B", "value": "x", "confirm": True},
        )
        assert r.status_code == 403 and r.json()["detail"]["code"] == "writes_disabled"

    # The registry script carries the key and value only inside the base64 arguments.
    script = scripts.registry_set(BUH, "HKLM\\Software\\Policies\\A'; Remove-GPO", "B", "String", "x'y", "")
    assert "Remove-GPO" not in script and "x'y" not in script
    args = _decoded_args(script)
    assert args["key"].endswith("Remove-GPO") and args["value"] == "x'y"
