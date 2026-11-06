"""The backup server's copy of the database: packed on the main server, taken over the backup's own data."""

import asyncio
import json
import zipfile

import httpx
from fastapi.testclient import TestClient

from app.main import create_app
from app.services import dbsync

from .conftest import AUTH, find, make_settings

API = "/api/v1"
BACKUP_TOKEN = "backup-token-0123456789abcdef"


def test_backup_takes_the_main_servers_database_and_keeps_its_settings(seeded, tmp_path):
    main = create_app(make_settings(backup_server_url="http://backup:8000", backup_server_token=BACKUP_TOKEN))
    backup = create_app(make_settings(api_token=BACKUP_TOKEN, sync_accept=True))
    # Both apps share the test database here, so «the backup» changes it between the pack and the push.
    with (
        TestClient(main, headers=AUTH) as m,
        TestClient(backup, headers={"Authorization": f"Bearer {BACKUP_TOKEN}"}) as b,
    ):
        sw = find(m, "SW-CORE-01")
        package = tmp_path / "package.zip"
        manifest = m.portal.call(dbsync.pack, main.state.settings, package)
        assert manifest["tables"]["devices"] > 10 and "server_settings" not in manifest["tables"]
        assert manifest["revision"] and manifest["key"] == ""

        # Something happened on the backup since: it is overwritten by the package.
        assert b.delete(f"{API}/devices/{sw['id']}").status_code == 204
        b.put(f"{API}/server-settings", json={"values": {"sync_interval_minutes": 7}})
        r = b.post(f"{API}/sync/receive", content=package.read_bytes(), headers={"Content-Type": "application/zip"})
        assert r.status_code == 200, r.text
        assert r.json()["role"] == "backup" and r.json()["received_rows"] == sum(manifest["tables"].values())
        assert b.get(f"{API}/devices/{sw['id']}").json()["name"] == "SW-CORE-01"
        # Its own settings stay.
        saved = b.get(f"{API}/server-settings").json()
        field = next(f for g in saved["groups"] for f in g["fields"] if f["key"] == "sync_interval_minutes")
        assert field["value"] == 7
        assert b.get(f"{API}/health").json()["sync_role"] == "backup"
        assert b.get(f"{API}/health").json()["last_sync_at"]

        # The main server pushes over HTTP to the backup app; the result is in its status.
        async def forward(request: httpx.Request) -> httpx.Response:
            assert request.headers["authorization"] == f"Bearer {BACKUP_TOKEN}"
            body = await request.aread()
            r = await asyncio.to_thread(b.post, request.url.path, content=body)
            return httpx.Response(r.status_code, content=r.content, headers={"content-type": "application/json"})

        status = dbsync.SyncStatus()
        transport = httpx.MockTransport(forward)
        m.portal.call(lambda: dbsync.push(main.state.settings, status, transport=transport))
        assert status.push_error is None and status.push_rows > 0 and status.pushed_at

        # A main server can refuse to be overwritten: it does not take packages.
        refused = m.post(f"{API}/sync/receive", content=package.read_bytes())
        assert refused.status_code == 403 and refused.json()["detail"]["code"] == "sync_not_accepted"
        audit = [a for a in m.get(f"{API}/audit").json() if a["action"].startswith("sync:")]
        assert {a["action"] for a in audit} >= {"sync:receive"}


def test_a_package_from_a_different_key_or_schema_is_refused(seeded, tmp_path):
    backup = create_app(make_settings(api_token=BACKUP_TOKEN, sync_accept=True))
    with TestClient(backup, headers={"Authorization": f"Bearer {BACKUP_TOKEN}"}) as b:
        package = tmp_path / "package.zip"
        b.portal.call(dbsync.pack, backup.state.settings, package)

        def variant(**changes) -> bytes:
            out = tmp_path / "variant.zip"
            with zipfile.ZipFile(package) as src, zipfile.ZipFile(out, "w") as dst:
                for item in src.namelist():
                    data = src.read(item)
                    if item == dbsync.MANIFEST:
                        data = json.dumps({**json.loads(data), **changes}).encode()
                    dst.writestr(item, data)
            return out.read_bytes()

        before = len(b.get(f"{API}/devices").json())
        key = b.post(f"{API}/sync/receive", content=variant(key="0123456789abcdef"))
        assert key.status_code == 409 and "SECRET_KEY" in key.json()["detail"]["message"]
        old = b.post(f"{API}/sync/receive", content=variant(revision="0001"))
        assert old.status_code == 409 and "версии базы" in old.json()["detail"]["message"]
        assert b.post(f"{API}/sync/receive", content=b"not a zip").status_code == 409
        # Nothing was touched.
        assert len(b.get(f"{API}/devices").json()) == before
        assert b.get(f"{API}/sync").json()["receive_error"]


def test_push_says_what_is_missing(seeded):
    app = create_app(make_settings())
    with TestClient(app, headers=AUTH) as c:
        r = c.post(f"{API}/sync/push")
        assert r.status_code == 422 and r.json()["detail"]["code"] == "no_backup_server"
    status = dbsync.SyncStatus()
    try:
        asyncio.run(dbsync.push(make_settings(backup_server_url="http://x:8000"), status))
    except dbsync.SyncError as e:
        assert "токен" in str(e)
    else:
        raise AssertionError("push without a token must fail")
