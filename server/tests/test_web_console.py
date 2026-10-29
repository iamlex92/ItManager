"""The web console page, custom scripts, and the change notice the phone reloads on."""

import re

from fastapi.testclient import TestClient

from app import __version__
from app.config import RunbookMode
from app.main import create_app

from .conftest import AUTH, TOKEN, make_settings

SCRIPT = {
    "title": "Очистка очереди печати",
    "description": "Останавливает спулер и чистит очередь",
    "shell": "powershell",
    "body": "Invoke-Command -ComputerName {{server}} { Restart-Service Spooler }",
    "params": [{"name": "server", "label": "Сервер печати", "placeholder": "srv-print"}],
}


def test_console_page_needs_no_token_but_locks_itself_down(seeded):
    with TestClient(create_app(make_settings())) as c:
        r = c.get("/admin")
        assert r.status_code == 200 and "NetAdmin" in r.text and f"веб-консоль · {__version__}" in r.text
        # Scripts carry a fingerprint of their contents, so an update is never served from the browser's cache.
        assert re.search(r"admin\.js\?v=[0-9a-f]{12}\"", r.text) and "{{" not in r.text.split("<body")[0]
        csp = r.headers["content-security-policy"]
        assert "script-src 'self'" in csp and "frame-ancestors 'none'" in csp
        assert r.headers["x-frame-options"] == "DENY"
        js = c.get("/static/admin/admin.js")
        assert js.status_code == 200
        # The console knows every device category the app has, and draws racks and room zones.
        assert '"RACK"' in js.text and "zones" in js.text and "rack_units" in js.text
        assert "admin-more.js" in r.text and c.get("/static/admin/admin-more.js").status_code == 200
        assert c.get("/static/admin/admin-pcs.js").status_code == 200
        assert c.get("/static/admin/admin-settings.js").status_code == 200
        assert c.get("/static/admin/admin-gpo.js").status_code == 200
        tabs = ("vlans", "devices", "map", "alerts", "audit", "runbooks", "printers", "creds", "topology", "templates")
        tabs += ("pcs",)
        for tab in tabs:
            assert f'data-tab="{tab}"' in r.text
        # As in the app: one device card from everywhere, view and edit modes, layers and marks on the map.
        for part in ('id="card-dialog"', 'data-mode="edit"', 'data-layer="L2"', 'data-tool="marks"', 'id="more-menu"'):
            assert part in r.text
        assert c.get("/", follow_redirects=False).headers["location"] == "/admin"
        # The page itself carries no data; everything behind it still needs the token.
        assert c.get("/api/v1/scripts").status_code == 401


def test_scripts_show_up_as_runbooks_and_only_emulate(client):
    created = client.post("/api/v1/scripts", json=SCRIPT)
    assert created.status_code == 201, created.text
    script = created.json()
    assert script["runbook_id"] == f"custom-{script['id']}"
    assert client.post("/api/v1/scripts", json=SCRIPT).json()["detail"]["code"] == "name_taken"

    listed = {rb["id"]: rb for rb in client.get("/api/v1/runbooks").json()}
    rb = listed[script["runbook_id"]]
    assert rb["category"] == "Свои скрипты" and rb["params"][0]["name"] == "server" and rb["critical"] is False

    r = client.post(f"/api/v1/runbooks/{rb['id']}/run", json={"params": {"server": "srv-print"}, "operator": "Pixel"})
    out = r.json()
    assert r.status_code == 200 and out["exit_code"] == 0 and out["simulated"] is True
    assert "Эмуляция" in out["stdout"] and "-ComputerName srv-print" in out["stdout"]
    audit = client.get("/api/v1/audit", params={"kind": "runbook"}).json()
    assert audit[0]["parameters"]["params"] == {"server": "srv-print"}

    missing = client.post(f"/api/v1/runbooks/{rb['id']}/run", json={"params": {}})
    assert missing.status_code == 422

    client.patch(f"/api/v1/scripts/{script['id']}", json={"critical": True})
    assert client.post(f"/api/v1/runbooks/{rb['id']}/run", json={"params": {"server": "x"}}).status_code == 400

    assert client.delete(f"/api/v1/scripts/{script['id']}").status_code == 204
    assert script["runbook_id"] not in {x["id"] for x in client.get("/api/v1/runbooks").json()}
    assert client.post(f"/api/v1/runbooks/{script['runbook_id']}/run", json={}).status_code == 404


def test_script_validation(client):
    bad = {**SCRIPT, "body": "echo {{nope}}"}
    assert client.post("/api/v1/scripts", json=bad).json()["detail"]["code"] == "unknown_placeholder"
    dup = {**SCRIPT, "params": [SCRIPT["params"][0], SCRIPT["params"][0]]}
    assert client.post("/api/v1/scripts", json=dup).status_code == 422
    assert (
        client.post("/api/v1/scripts", json={**SCRIPT, "params": [{"name": "Bad Name", "label": "x"}]}).status_code
        == 422
    )
    assert client.post("/api/v1/runbooks/custom-not-a-uuid/run", json={}).status_code == 404


def test_scripts_stay_emulated_in_real_runbook_mode(seeded):
    with TestClient(create_app(make_settings(runbook_mode=RunbookMode.REAL)), headers=AUTH) as c:
        script = c.post("/api/v1/scripts", json=SCRIPT).json()
        assert script["runbook_id"] in {rb["id"] for rb in c.get("/api/v1/runbooks").json()}
        out = c.post(f"/api/v1/runbooks/{script['runbook_id']}/run", json={"params": {"server": "s"}}).json()
        assert out["simulated"] is True and "Эмуляция" in out["stdout"]


def test_inventory_changes_are_announced(client):
    with client.websocket_connect(f"/api/v1/ws?token={TOKEN}") as ws:
        ws.send_json({"action": "subscribe", "device_id": "*"})
        # Messages are handled in order, so this error means the subscription above is in place.
        ws.send_json({"action": "subscribe", "device_id": "nope"})
        assert ws.receive_json()["type"] == "error"

        client.get("/api/v1/devices")
        r = client.post("/api/v1/devices", json={"name": "AP-WEB", "type": "ACCESS_POINT", "x": 10, "y": 10})
        assert r.status_code == 201
        assert ws.receive_json() == {"type": "inventory_changed"}

        client.post("/api/v1/scripts", json=SCRIPT)
        assert ws.receive_json() == {"type": "inventory_changed"}
        # A refused write announces nothing; the next event is the delete below.
        assert client.post("/api/v1/scripts", json=SCRIPT).status_code == 409
        client.delete(f"/api/v1/devices/{r.json()['id']}")
        assert ws.receive_json() == {"type": "inventory_changed"}
