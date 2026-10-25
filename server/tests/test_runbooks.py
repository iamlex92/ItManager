from .conftest import find


def run(client, runbook_id, confirm=True, **params):
    return client.post(
        f"/api/v1/runbooks/{runbook_id}/run",
        json={"params": params, "confirm": confirm, "auth_method": "biometric", "operator": "Pixel 10a"},
    )


def test_catalog(client):
    rbs = {rb["id"]: rb for rb in client.get("/api/v1/runbooks").json()}
    assert set(rbs) == {
        "ad_reset_password",
        "ad_unlock_account",
        "ad_set_enabled",
        "fs_effective_access",
        "backup_database",
        "vm_snapshot",
        "service_restart",
    }
    assert rbs["ad_reset_password"]["critical"] and not rbs["fs_effective_access"]["critical"]
    action = rbs["ad_set_enabled"]["params"][1]
    assert action["kind"] == "choice" and {c["value"] for c in action["choices"]} == {"enable", "disable"}
    assert client.get("/api/v1/health").json()["runbook_mode"] == "emulator"


def test_critical_needs_confirmation_and_is_not_audited_without_it(client):
    r = run(client, "ad_reset_password", confirm=False, account="i.ivanov")
    assert r.status_code == 400 and r.json()["detail"]["code"] == "confirmation_required"
    assert client.get("/api/v1/audit", params={"kind": "runbook"}).json() == []


def test_param_validation(client):
    assert run(client, "nope").status_code == 404
    r = run(client, "ad_unlock_account")
    assert r.status_code == 422 and "Учётная запись" in r.json()["detail"]["message"]
    assert run(client, "ad_set_enabled", account="i.ivanov", action="delete").status_code == 422
    assert run(client, "ad_unlock_account", account="i.ivanov", extra="x").status_code == 422


def test_reset_password_secret_shown_once_not_audited(client):
    r = run(client, "ad_reset_password", account="FACTORY\\I.Ivanov")
    body = r.json()
    assert r.status_code == 200 and body["exit_code"] == 0 and body["simulated"]
    assert body["secret"] and body["secret"] not in body["stdout"]
    assert "pwdLastSet = 0" in body["stdout"] and "заблокирована" in body["stdout"]

    [entry] = client.get("/api/v1/audit", params={"kind": "runbook"}).json()
    assert entry["id"] == body["audit_id"] and entry["title"] == "Сброс пароля"
    assert entry["triggered_by"].startswith("Pixel 10a · ")
    assert entry["parameters"]["params"] == {"account": "FACTORY\\I.Ivanov"}
    assert entry["parameters"]["auth_method"] == "biometric"
    assert body["secret"] not in str(entry)


def test_unlock_changes_state(client):
    first = run(client, "ad_unlock_account", account="m.smirnova").json()
    assert first["exit_code"] == 0 and "lockoutTime = 0" in first["stdout"]
    again = run(client, "ad_unlock_account", account="m.smirnova").json()
    assert "не заблокирована" in again["stdout"]


def test_unknown_account_fails_with_stderr(client):
    body = run(client, "ad_set_enabled", account="ghost", action="disable").json()
    assert body["exit_code"] == 1 and "Cannot find an object" in body["stderr"]
    [entry] = client.get("/api/v1/audit").json()
    assert entry["exit_code"] == 1 and "Cannot find" in entry["stderr"]


def test_effective_access(client):
    out = run(client, "fs_effective_access", principal="i.ivanov", path=r"\\fs01\Бухгалтерия").json()["stdout"]
    assert "Эффективные права: Modify" in out
    # A deny on Modify for the shop group leaves read access only.
    out = run(client, "fs_effective_access", principal="s.sidorov", path=r"\\fs01\Общая").json()["stdout"]
    assert "Эффективные права: ReadAndExecute" in out
    out = run(client, "fs_effective_access", principal="GG_Sklad_RW", path=r"\\fs01\ОТК").json()["stdout"]
    assert "нет доступа" in out
    assert run(client, "fs_effective_access", principal="i.ivanov", path=r"\\fs01\Нет").json()["exit_code"] == 1


def test_infrastructure_runbooks(client):
    assert "successfully processed" in run(client, "backup_database", database="mes").json()["stdout"]
    assert run(client, "vm_snapshot", vm="vm-mes", name="before-update").json()["exit_code"] == 0
    dup = run(client, "vm_snapshot", vm="vm-mes", name="before-update").json()
    assert dup["exit_code"] == 1 and "already exists" in dup["stderr"]
    assert "Running" in run(client, "service_restart", service="srv-print/Spooler").json()["stdout"]


def test_audit_filters_include_switch_writes(client):
    run(client, "ad_unlock_account", account="i.ivanov")
    sw = find(client, "SW-CORE-01")
    assert client.post(f"/api/v1/devices/{sw['id']}/ping", json={"target": "10.10.10.22"}).status_code == 200
    kinds = {e["kind"] for e in client.get("/api/v1/audit").json()}
    assert kinds == {"runbook", "switch"}
    assert {e["kind"] for e in client.get("/api/v1/audit", params={"kind": "switch"}).json()} == {"switch"}
    assert client.get("/api/v1/audit", params={"kind": "runbook"}).json()[0]["title"] == "Разблокировка учётной записи"
