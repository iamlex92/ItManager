import asyncio

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from sqlalchemy import text

from app import crypto
from app.db import make_engine
from app.main import create_app

from .conftest import AUTH, TEST_DB, audit_rows, find, make_settings

KEY = "kR1d3Tq0m8xG3zv0q7YkZb9y0b7pC2e3Z0yXH1rS5sM="
OTHER_KEY = "M3n4a5Yp9vX0b1C2d3E4f5G6h7I8j9K0l1M2n3O4p5Q="


def raw(sql: str) -> list[tuple]:
    async def q():
        engine = make_engine(TEST_DB)
        async with engine.connect() as c:
            rows = (await c.execute(text(sql))).all()
        await engine.dispose()
        return [tuple(r) for r in rows]

    return asyncio.run(q())


@pytest.fixture
def secure_client(seeded):
    with TestClient(create_app(make_settings(secret_key=KEY)), headers=AUTH) as c:
        yield c
    crypto.configure(None)


def test_secret_box_roundtrip():
    box = crypto.SecretBox(KEY)
    stored = box.encrypt("s3cret")
    assert stored.startswith("enc:v1:") and "s3cret" not in stored
    assert box.decrypt(stored) == "s3cret"
    assert box.encrypt(stored) == stored  # never double-encrypted
    assert box.decrypt("plain") == "plain"
    with pytest.raises(crypto.SecretsUnavailable):
        crypto.SecretBox(OTHER_KEY).decrypt(stored)
    with pytest.raises(crypto.SecretsUnavailable):
        crypto.SecretBox(None).decrypt(stored)


def test_bad_secret_key_is_refused():
    with pytest.raises(ValidationError, match="SECRET_KEY"):
        make_settings(secret_key="not-a-key")


def test_startup_encrypts_existing_communities(secure_client):
    assert secure_client.get("/api/v1/health").json()["secrets_encrypted"] is True
    stored = [r[0] for r in raw("select snmp_community from devices where snmp_community is not null")]
    assert stored and all(v.startswith("enc:v1:") for v in stored)
    # The API never shows the community, only that one is set.
    sw = find(secure_client, "SW-CORE-01")
    assert sw["has_snmp_community"] is True and "snmp_community" not in sw


def test_new_device_community_is_encrypted_and_still_usable(secure_client):
    floor = secure_client.get("/api/v1/floors").json()[0]["id"]
    body = {"name": "PRN-NEW", "type": "PRINTER", "floor_id": floor, "ip": "10.10.30.99", "snmp_community": "pr1nt"}
    dev = secure_client.post("/api/v1/devices", json=body).json()
    (stored,) = raw(f"select snmp_community from devices where id = '{dev['id']}'")[0]
    assert stored.startswith("enc:v1:") and "pr1nt" not in stored
    assert crypto.box().decrypt(stored) == "pr1nt"


def test_wrong_key_refuses_to_start(secure_client):
    with pytest.raises(crypto.SecretsUnavailable):
        with TestClient(create_app(make_settings(secret_key=OTHER_KEY))):
            pass
    with pytest.raises(crypto.SecretsUnavailable):
        with TestClient(create_app(make_settings())):
            pass


def test_credentials_are_write_only_and_audited(secure_client):
    c = secure_client
    r = c.post(
        "/api/v1/credentials",
        json={"name": "ssh-switches", "kind": "ssh", "username": "netadmin", "secret": "P@ssw0rd!", "operator": "Px"},
    )
    assert r.status_code == 201, r.text
    cred = r.json()
    assert cred["has_secret"] is True and "secret" not in cred and cred["devices"] == 0
    assert c.post("/api/v1/credentials", json={"name": "ssh-switches", "kind": "ssh"}).status_code == 409

    (stored,) = raw("select secret from credentials")[0]
    assert stored.startswith("enc:v1:") and "P@ssw0rd" not in stored

    sw = find(c, "SW-CORE-01")
    patched = c.patch(f"/api/v1/devices/{sw['id']}", json={"ssh_credential_id": cred["id"]}).json()
    assert patched["ssh_credential_id"] == cred["id"]
    assert c.get("/api/v1/credentials").json()[0]["devices"] == 1

    upd = c.patch(f"/api/v1/credentials/{cred['id']}", json={"secret": "n3w", "notes": "rotated"}).json()
    assert upd["notes"] == "rotated" and upd["has_secret"]
    assert c.delete(f"/api/v1/credentials/{cred['id']}").status_code == 204
    assert find(c, "SW-CORE-01")["ssh_credential_id"] is None

    actions = [r[0] for r in audit_rows()]
    assert {"credential_create", "credential_update", "credential_delete"} <= set(actions)
    dump = str(raw("select parameters, output_log from script_audit_logs where script_name like 'credential%'"))
    assert "P@ssw0rd" not in dump and "n3w" not in dump
    entries = c.get("/api/v1/audit", params={"kind": "security"}).json()
    assert {e["title"] for e in entries} == {
        "Добавление учётных данных",
        "Изменение учётных данных",
        "Удаление учётных данных",
    }
    assert entries[0]["kind"] == "security"
    assert not any(e["kind"] == "security" for e in c.get("/api/v1/audit", params={"kind": "switch"}).json())


def test_credentials_without_key_still_work_plaintext(client):
    r = client.post("/api/v1/credentials", json={"name": "winrm", "kind": "winrm", "secret": "x"})
    assert r.status_code == 201 and r.json()["has_secret"]
    assert client.get("/api/v1/health").json()["secrets_encrypted"] is False
