"""«Проверить» in the server settings: one small read per connection, the reason in words, never an exception."""

from fastapi.testclient import TestClient

from app.config import RunbookMode
from app.main import create_app
from app.netsrv.source import RealNetServices

from .conftest import AUTH, make_settings

C = "/api/v1/server-settings/check"


def test_emulation_says_there_is_nothing_to_reach(client):
    for what in ("switch", "ldap", "winrm"):
        r = client.post(f"{C}/{what}", json={}).json()
        assert r["ok"] and r["simulated"] and "Эмуляция" in r["text"]
    dhcp = client.post(f"{C}/dhcp", json={}).json()
    assert dhcp["ok"] and dhcp["simulated"] and "областей" in dhcp["text"]
    dns = client.post(f"{C}/dns", json={}).json()
    assert dns["ok"] and "записей A" in dns["text"]
    assert client.post(f"{C}/nope", json={}).status_code == 422


def test_real_checks_report_the_reason(seeded):
    settings = make_settings(runbook_mode=RunbookMode.REAL, dhcp_host="dhcp.factory.local")
    with TestClient(create_app(settings), headers=AUTH) as c:
        # No login chosen, no controller named: each says what is missing instead of failing.
        assert "учётная запись" in c.post(f"{C}/ldap", json={}).json()["text"]
        winrm = c.post(f"{C}/winrm", json={}).json()
        assert not winrm["ok"] and "не к кому" in winrm["text"]
        dns = c.post(f"{C}/dns", json={}).json()
        assert not dns["ok"] and "зоны" in dns["text"].lower()

        async def boom(host, command, stdin):
            raise OSError("no route to host")

        c.post("/api/v1/credentials", json={"name": "adm", "kind": "ssh", "username": "adm", "secret": "pw"})
        c.app.state.settings.runbook_credential = "adm"
        c.app.state.netsrv = RealNetServices(settings, runner_factory=lambda login: boom)
        dhcp = c.post(f"{C}/dhcp", json={}).json()
        assert not dhcp["ok"] and "no route to host" in dhcp["text"]
