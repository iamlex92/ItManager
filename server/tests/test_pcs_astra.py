"""Astra Linux computers: found by their SSH port, driven with sudo, and remote help for both platforms."""

import base64
import json
import re

from fastapi.testclient import TestClient

from app.config import RunbookMode
from app.main import create_app
from app.pcs.astra import program
from app.pcs.backend import Action

from .conftest import AUTH, make_settings


def test_emulated_astra_page_and_remote_help(client):
    info = client.post("/api/v1/pcs/astra-buh-01/info", json={}).json()
    assert info["platform"] == "astra" and info["data"]["os"].startswith("Astra Linux")
    win = client.post("/api/v1/pcs/PC-BUH-01/info", json={}).json()
    assert win["platform"] == "windows"

    # Windows: the helper runs msra on their own computer; nothing is sent to the PC.
    offer = client.post("/api/v1/pcs/PC-BUH-01/assist", json={}).json()
    assert offer["ok"] and offer["data"]["command"] == "msra.exe /offerra PC-BUH-01"
    # Astra: x11vnc with a one-time password, which stays out of the audit log.
    seed_user = client.post("/api/v1/pcs/astra-buh-01/info", json={}).json()["data"]["user"]
    vnc = client.post("/api/v1/pcs/astra-buh-01/assist", json={}).json()
    if seed_user:
        assert vnc["ok"] and vnc["data"]["url"] == "vnc://astra-buh-01:5900" and vnc["data"]["password"]
        audit = client.get("/api/v1/audit", params={"kind": "runbook"}).json()
        entry = next(a for a in audit if a["action"] == "pc:assist" and "astra" in str(a))
        assert vnc["data"]["password"] not in json.dumps(entry, ensure_ascii=False)

    # Linux-only actions need the confirmation, and are refused on Windows.
    assert client.post("/api/v1/pcs/astra-buh-01/update", json={}).status_code == 400
    assert client.post("/api/v1/pcs/astra-buh-01/update", json={"confirm": True}).json()["ok"]
    sudo = client.post("/api/v1/pcs/astra-buh-01/admin_add", json={"arg": "ECOTEHS\\kharca01", "confirm": True})
    assert sudo.json()["data"]["user"] == "ECOTEHS\\kharca01"
    bad = client.post("/api/v1/pcs/astra-buh-01/admin_add", json={"arg": "x; rm -rf /", "confirm": True})
    assert bad.status_code == 422
    assert client.post("/api/v1/pcs/PC-BUH-01/update", json={"confirm": True}).status_code == 422


async def ssh_only(host, port):
    return port == 22


def test_real_astra_goes_over_ssh_with_sudo(seeded):
    settings = make_settings(
        runbook_mode=RunbookMode.REAL, runbook_credential="domain-admin", linux_credential="astra-admin",
        allow_runbook_writes=True,
    )  # fmt: skip
    with TestClient(create_app(settings), headers=AUTH) as c:
        c.app.state.pc_probe = ssh_only
        for name, user in (("domain-admin", "ECOTEHS\\adm"), ("astra-admin", "root-helper")):
            c.post("/api/v1/credentials", json={"name": name, "kind": "ssh", "username": user, "secret": "p@ss"})
        calls = []

        def factory(login):
            async def run(host, command, stdin):
                calls.append((host, login.username, command, stdin))
                b64 = re.search(r'b64decode\("([^"]+)"\)', command).group(1)
                source = base64.b64decode(b64).decode()
                if "x11vnc" in source:
                    return 0, json.dumps({"method": "vnc", "port": 5901, "password": "Ab3dEf9h", "user": "ivanova",
                                          "display": ":0", "view_only": False}), ""  # fmt: skip
                if "netadmin-admins" in source and "pwd.getpwnam" in source:
                    return 0, json.dumps({"error": "Пользователя nobody2 нет на компьютере"}), ""
                if "MemTotal" in source:
                    return 0, json.dumps({"platform": "astra", "os": "Astra Linux 1.7", "user": "ivanova"}), ""
                return 1, "", "kharca01 is not in the sudoers file."

            return run

        c.app.state.astra.runner_factory = factory
        info = c.post("/api/v1/pcs/PC-BUH-01/info", json={}).json()
        assert info["platform"] == "astra" and info["data"]["user"] == "ivanova" and not info["simulated"]
        host, user, command, stdin = calls[0]
        # Its own credential, the password to sudo on stdin, never in the command line.
        assert user == "root-helper" and stdin == "p@ss\n" and command.startswith("sudo -S -p '' -- python3 -c")
        assert "p@ss" not in command

        vnc = c.post("/api/v1/pcs/PC-BUH-01/assist", json={}).json()["data"]
        assert vnc["address"] == "PC-BUH-01:1" and vnc["url"] == "vnc://PC-BUH-01:5901"
        missing = c.post("/api/v1/pcs/PC-BUH-01/admin_add", json={"arg": "nobody2", "confirm": True}).json()
        assert not missing["ok"] and "nobody2" in missing["error"]
        denied = c.post("/api/v1/pcs/PC-BUH-01/disks", json={}).json()
        assert not denied["ok"] and "sudo" in denied["error"]

        # Nothing answers: said plainly, not a stack trace.
        async def nothing(host, port):
            return False

        c.app.state.pc_probe = nothing
        r = c.post("/api/v1/pcs/10.10.10.22/info", json={})
        if r.status_code != 403:  # the inventory may not know that address
            assert r.status_code == 409 and "ни по WinRM" in r.json()["detail"]["message"]


def test_programs_carry_arguments_as_data():
    src = program(Action.COMMAND, "echo '\"; rm -rf /' $HOME")
    first = src.splitlines()[0]
    assert first.startswith("ARG_JSON = ") and json.loads(json.loads(first[len("ARG_JSON = ") :])) == (
        "echo '\"; rm -rf /' $HOME"
    )
    assert json.loads(json.loads(program(Action.ASSIST, "view").splitlines()[0][11:])) == {"view_only": True}
