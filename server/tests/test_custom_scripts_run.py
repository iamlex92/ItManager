"""Custom scripts that really run: on the NetAdmin server, on Windows over WinRM, on Astra Linux over SSH."""

import base64
import re

from fastapi.testclient import TestClient

from app.config import RunbookMode
from app.main import create_app
from app.runbooks import custom

from .conftest import AUTH, make_settings

EVIL = "x'; rm -rf / #\"$(reboot)"


def test_parameters_are_strings_never_code():
    assert custom.render("powershell", "Get-ADUser {{u}}", {"u": "o'neil"}) == "Get-ADUser 'o''neil'"
    assert custom.render("bash", "id {{u}}", {"u": EVIL}) == "id 'x'\\''; rm -rf / #\"$(reboot)'"
    assert custom.render("python", "print({{u}})", {"u": EVIL}) == 'print("x\'; rm -rf / #\\"$(reboot)")'
    assert custom.render("cmd", "ping {{h}}", {"h": "pc-01.zavod.local"}) == "ping pc-01.zavod.local"
    for bad in ("a & del *", "a|b", "%PATH%", 'a"b'):
        try:
            custom.render("cmd", "ping {{h}}", {"h": bad})
        except custom.ScriptError:
            continue
        raise AssertionError(bad)


def _script(c, **kw):
    body = {
        "title": "t",
        "shell": "python",
        "body": "print('ok', {{who}})",
        "params": [{"name": "who", "label": "Кто"}],
    }
    r = c.post("/api/v1/scripts", json={**body, **kw})
    assert r.status_code == 201, r.text
    return r.json()


def test_python_on_the_server_needs_writes_and_a_confirmation(seeded):
    with TestClient(create_app(make_settings(allow_runbook_writes=False)), headers=AUTH) as c:
        s = _script(c, runs_on="server")
        rb = next(r for r in c.get("/api/v1/runbooks").json() if r["id"] == s["runbook_id"])
        assert rb["critical"] is True  # a real run is confirmed with the fingerprint every time
        run = f"/api/v1/runbooks/{s['runbook_id']}/run"
        assert c.post(run, json={"params": {"who": "a"}}).status_code == 400
        off = c.post(run, json={"params": {"who": "a"}, "confirm": True})
        assert off.status_code == 403 and off.json()["detail"]["code"] == "writes_disabled"
        # «Проверить» shows the script and runs nothing, writes or not.
        dry = c.post(run, json={"params": {"who": EVIL}, "confirm": True, "dry_run": True}).json()
        assert dry["simulated"] and "print('ok', \"x'; rm" in dry["stdout"]

    with TestClient(create_app(make_settings(allow_runbook_writes=True)), headers=AUTH) as c:
        s = _script(c, title="t2", runs_on="server")
        out = c.post(f"/api/v1/runbooks/{s['runbook_id']}/run", json={"params": {"who": EVIL}, "confirm": True}).json()
        assert out["simulated"] is False and out["exit_code"] == 0
        assert out["stdout"].strip() == "ok x'; rm -rf / #\"$(reboot)"
        audit = c.get("/api/v1/audit", params={"kind": "runbook"}).json()[0]
        assert audit["parameters"]["simulated"] is False
        cmd = _script(c, title="cmd", shell="cmd", body="dir", params=[], runs_on="server")
        r = c.post(f"/api/v1/runbooks/{cmd['runbook_id']}/run", json={"confirm": True})
        assert r.status_code == 422


async def windows_only(host, port):
    return port == 5985


async def ssh_only(host, port):
    return port == 22


def test_on_a_domain_computer_windows_or_astra(seeded):
    settings = make_settings(
        runbook_mode=RunbookMode.REAL, runbook_credential="domain-admin", allow_runbook_writes=True
    )
    with TestClient(create_app(settings), headers=AUTH) as c:
        c.post("/api/v1/credentials", json={"name": "domain-admin", "kind": "winrm", "username": "ECOTEHS\\adm",
                                            "secret": "p@ss"})  # fmt: skip
        sent = []

        def winrm(login):
            def run(host, script):
                sent.append(("winrm", host, script))
                return 0, "Hello from PowerShell\n", ""

            return run

        def ssh(login):
            async def run(host, command, stdin):
                sent.append(("ssh", host, command, stdin))
                return 0, "astra ok\n", ""

            return run

        c.app.state.pcs.shell_factory = winrm
        c.app.state.astra.runner_factory = ssh
        ps = _script(c, title="ps", shell="powershell", body="Get-ADUser {{who}}", runs_on="pc")
        rb = next(r for r in c.get("/api/v1/runbooks").json() if r["id"] == ps["runbook_id"])
        assert [p["name"] for p in rb["params"]] == ["host", "who"]  # the computer is asked first

        c.app.state.pc_probe = windows_only
        out = c.post(
            f"/api/v1/runbooks/{ps['runbook_id']}/run",
            json={"params": {"host": "PC-BUH-01", "who": "o'neil"}, "confirm": True},
        ).json()
        assert out["simulated"] is False and "Hello from PowerShell" in out["stdout"]
        assert sent[-1][1] == "PC-BUH-01" and sent[-1][2].endswith("Get-ADUser 'o''neil'")
        unknown = c.post(
            f"/api/v1/runbooks/{ps['runbook_id']}/run",
            json={"params": {"host": "evil.example", "who": "a"}, "confirm": True},
        )
        assert unknown.status_code == 403 and len(sent) == 1  # nothing sent to a computer nobody knows

        py = _script(c, title="py", shell="python", body="print({{who}})", runs_on="pc")
        c.app.state.pc_platforms.clear()
        c.app.state.pc_probe = ssh_only
        out = c.post(
            f"/api/v1/runbooks/{py['runbook_id']}/run",
            json={"params": {"host": "PC-BUH-01", "who": "a"}, "confirm": True},
        ).json()
        kind, host, command, stdin = sent[-1]
        assert kind == "ssh" and stdin == "p@ss\n" and "p@ss" not in command
        b64 = re.search(r'b64decode\("([^"]+)"\)', command).group(1)
        assert base64.b64decode(b64).decode() == 'print("a")' and "Astra Linux" in out["stdout"]

        # A language the computer has no shell for is refused before anything is sent.
        r = c.post(
            f"/api/v1/runbooks/{ps['runbook_id']}/run",
            json={"params": {"host": "PC-BUH-01", "who": "a"}, "confirm": True},
        )
        assert r.status_code == 422 and "Astra Linux" in r.json()["detail"]["message"] and len(sent) == 2


def test_windows_wrappers_carry_the_script_as_data():
    py = custom.windows_script("python", "print('привет')")
    b64 = re.search(r"FromBase64String\('([^']+)'\)", py).group(1)
    assert base64.b64decode(b64).decode() == "print('привет')" and "Get-Command py" in py and "??" not in py
    assert "cmd.exe /c $f" in custom.windows_script("cmd", "dir")
    assert custom.linux_command("bash", "echo 'x'").startswith("sudo -S -p '' -- bash -c")


def test_the_same_script_twice_at_once_is_refused(seeded):
    with TestClient(create_app(make_settings(allow_runbook_writes=True)), headers=AUTH) as c:
        s = _script(c, runs_on="server")
        c.app.state.scripts_running = {(s["runbook_id"], "")}  # one is running
        r = c.post(f"/api/v1/runbooks/{s['runbook_id']}/run", json={"params": {"who": "a"}, "confirm": True})
        assert r.status_code == 409 and r.json()["detail"]["code"] == "already_running"
        # A preview is only text: it may run beside it.
        dry = c.post(
            f"/api/v1/runbooks/{s['runbook_id']}/run", json={"params": {"who": "a"}, "dry_run": True, "confirm": True}
        )
        assert dry.status_code == 200
        c.app.state.scripts_running.clear()
        ok = c.post(f"/api/v1/runbooks/{s['runbook_id']}/run", json={"params": {"who": "a"}, "confirm": True})
        assert ok.status_code == 200 and "ok a" in ok.json()["stdout"]
        assert c.app.state.scripts_running == set()  # the slot is given back


async def test_a_dropped_request_kills_the_server_script():
    import asyncio
    import os

    task = asyncio.create_task(custom.run_on_server("python", "import time\ntime.sleep(60)"))
    await asyncio.sleep(0.8)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass
    await asyncio.sleep(0.3)
    # The temporary script file names the process; the bracket keeps pgrep from matching its own shell.
    alive = os.popen('pgrep -f "[n]etadmin-script-" || true').read().split()
    assert alive == []


def _wait(c, job_id, states=("done", "stopped", "failed"), seconds=15):
    import time

    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        job = c.get(f"/api/v1/scripts/jobs/{job_id}").json()
        if job["state"] in states:
            return job
        time.sleep(0.1)
    raise AssertionError(f"the job did not reach {states}: {job}")


def test_a_script_runs_as_a_job_and_the_output_is_followed(seeded):
    with TestClient(create_app(make_settings(allow_runbook_writes=True)), headers=AUTH) as c:
        s = _script(
            c,
            runs_on="server",
            body="import sys, time\nprint('first', flush=True)\ntime.sleep(1.5)\nprint('second')\nsys.exit(3)",
            params=[],
        )
        url = f"/api/v1/scripts/{s['id']}/jobs"
        # Confirmed like any real run.
        assert c.post(url, json={}).status_code == 400
        started = c.post(url, json={"confirm": True, "operator": "Иванов"})
        assert started.status_code == 202, started.text
        job = started.json()
        assert job["state"] == "running" and job["runs_on"] == "server"
        # The second start while it runs is refused.
        assert c.post(url, json={"confirm": True}).status_code == 409
        # The first line is there before the script ends.
        import time

        seen = ""
        deadline = time.monotonic() + 8
        while "first" not in seen and time.monotonic() < deadline:
            part = c.get(f"/api/v1/scripts/jobs/{job['id']}", params={"out_from": len(seen)}).json()
            seen += part["stdout"]
            time.sleep(0.1)
        assert "first" in seen and part["state"] == "running"
        done = _wait(c, job["id"])
        assert done["state"] == "done" and done["exit_code"] == 3
        assert done["stdout"].split() == ["first", "second"] and done["out_len"] == len("first\nsecond\n")
        # Only the new part when asked from an offset.
        tail = c.get(f"/api/v1/scripts/jobs/{job['id']}", params={"out_from": done["out_len"]}).json()
        assert tail["stdout"] == "" and tail["out_len"] == done["out_len"]
        audit = c.get("/api/v1/audit", params={"kind": "runbook"}).json()[0]
        assert audit["exit_code"] == 3 and "first" in str(audit) and audit["triggered_by"].startswith("Иванов")
        assert c.app.state.scripts_running == set()
        assert [j["id"] for j in c.get("/api/v1/scripts/jobs").json()][0] == job["id"]


def test_a_running_script_can_be_stopped(seeded):
    import os

    with TestClient(create_app(make_settings(allow_runbook_writes=True)), headers=AUTH) as c:
        s = _script(c, runs_on="server", body="import time\nprint('start', flush=True)\ntime.sleep(60)", params=[])
        job = c.post(f"/api/v1/scripts/{s['id']}/jobs", json={"confirm": True}).json()
        _wait(c, job["id"], states=("running",))
        stopped = c.post(f"/api/v1/scripts/jobs/{job['id']}/stop").json()
        assert stopped["state"] == "stopped" and stopped["exit_code"] == 130 and "Остановлено" in stopped["error"]
        assert c.app.state.scripts_running == set()
        # Stopping what has ended changes nothing; the process is gone.
        assert c.post(f"/api/v1/scripts/jobs/{job['id']}/stop").json()["state"] == "stopped"
        assert os.popen('pgrep -f "[n]etadmin-script-" || true').read().split() == []
        audit = c.get("/api/v1/audit", params={"kind": "runbook"}).json()[0]
        assert audit["exit_code"] == 130
        # It can be started again at once.
        again = c.post(f"/api/v1/scripts/{s['id']}/jobs", json={"confirm": True})
        assert again.status_code == 202
        c.post(f"/api/v1/scripts/jobs/{again.json()['id']}/stop")


def test_job_refusals(seeded):
    with TestClient(create_app(make_settings(allow_runbook_writes=False)), headers=AUTH) as c:
        shown = _script(c, title="shown")
        assert (
            c.post(f"/api/v1/scripts/{shown['id']}/jobs", json={"confirm": True}).status_code == 422
        )  # nothing to run
        real = _script(c, title="real", runs_on="server")
        job = c.post(f"/api/v1/scripts/{real['id']}/jobs", json={"params": {"who": "a"}, "confirm": True}).json()
        # Writes are off: the job fails with the reason, and the slot is given back.
        failed = _wait(c, job["id"])
        assert failed["state"] == "failed" and "Разрешить изменения" in failed["error"]
        assert c.app.state.scripts_running == set()
        assert c.get("/api/v1/scripts/jobs/00000000-0000-0000-0000-000000000000").status_code == 404
