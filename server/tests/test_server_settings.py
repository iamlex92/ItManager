"""«Настройки сервера»: settings saved over the API override netadmin.env from the next start."""

import time

from fastapi.testclient import TestClient

from app.main import create_app

from .conftest import AUTH, audit_rows, make_settings


def _field(payload: dict, key: str) -> dict:
    return next(f for g in payload["groups"] for f in g["fields"] if f["key"] == key)


def test_saved_settings_apply_from_the_next_start(seeded):
    with TestClient(create_app(make_settings()), headers=AUTH) as c:
        s = c.get("/api/v1/server-settings").json()
        assert [g["title"] for g in s["groups"]][:2] == ["Коммутаторы", "Мониторинг и SNMP"]
        hal = _field(s, "hal_mode")
        assert hal["value"] == "emulator" and hal["source"] == "default" and not hal["restart_required"]
        assert {c["value"] for c in hal["choices"]} == {"emulator", "real"}
        secret = _field(s, "switch_enable_secret")
        assert secret["value"] is None and secret["is_set"] is False
        assert _field(s, "runbook_credential")["credential_kind"] == "winrm"
        assert s["can_restart"] is False and s["restart_pending"] is False

        bad = c.put("/api/v1/server-settings", json={"values": {"switch_ssh_port": 70000}})
        assert bad.status_code == 422 and bad.json()["detail"]["key"] == "switch_ssh_port"
        refused = c.put("/api/v1/server-settings", json={"values": {"api_token": "x" * 20}})
        assert refused.status_code == 422  # bootstrap settings stay in netadmin.env

        r = c.put(
            "/api/v1/server-settings",
            json={
                "values": {
                    "hal_mode": "real",
                    "pc_mode": "real",
                    "switch_enable_secret": "en-pass",
                    "poll_interval_seconds": "30",
                }
            },
        ).json()
        assert _field(r, "hal_mode") | {"choices": None} == _field(r, "hal_mode") | {
            "value": "real",
            "source": "ui",
            "restart_required": True,
            "choices": None,
        }
        assert _field(r, "poll_interval_seconds")["value"] == 30.0
        secret = _field(r, "switch_enable_secret")
        assert secret["is_set"] is True and secret["value"] is None
        assert r["restart_pending"] is True
        # Still the old values until the restart.
        assert c.get("/api/v1/health").json()["hal_mode"] == "emulator"
        # An empty secret keeps it.
        r = c.put("/api/v1/server-settings", json={"values": {"switch_enable_secret": ""}}).json()
        assert _field(r, "switch_enable_secret")["is_set"] is True
        [(_, _, log)] = [row for row in audit_rows() if "настройки сервера" in (row[2] or "").lower()][:1]
        assert "Пароль enable" in log

    with TestClient(create_app(make_settings()), headers=AUTH) as c:
        assert c.get("/api/v1/health").json()["hal_mode"] == "real"
        assert c.app.state.settings.switch_enable_secret == "en-pass"
        assert c.get("/api/v1/pcs/status").json()["mode"] == "real"
        s = c.get("/api/v1/server-settings").json()
        assert s["restart_pending"] is False and _field(s, "hal_mode")["source"] == "ui"

        s = c.put("/api/v1/server-settings", json={"reset": ["hal_mode", "pc_mode"]}).json()
        assert _field(s, "hal_mode")["value"] == "emulator" and _field(s, "hal_mode")["restart_required"] is True


def test_restart_goes_through_the_service(seeded):
    with TestClient(create_app(make_settings()), headers=AUTH) as c:
        r = c.post("/api/v1/server-settings/restart")
        assert r.status_code == 409 and r.json()["detail"]["code"] == "cannot_restart"

        called = []
        c.app.state.restart = lambda: called.append(True)
        assert c.get("/api/v1/server-settings").json()["can_restart"] is True
        assert c.post("/api/v1/server-settings/restart").status_code == 202
        for _ in range(30):
            if called:
                break
            time.sleep(0.1)
        assert called == [True]


def _test_cert() -> str:
    """A throwaway self-signed certificate, made here so no key material lives in the repository."""
    import datetime

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Samba test CA")])
    now = datetime.datetime.now(datetime.UTC)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(1)
        .not_valid_before(now)
        .not_valid_after(now + datetime.timedelta(days=1))
        .sign(key, hashes.SHA256())
    )
    return cert.public_bytes(serialization.Encoding.PEM).decode()


def test_ca_certificate_survives_a_mangled_paste(seeded):
    import ssl

    from app.runbooks.real import normalize_pem

    pem = _test_cert()
    for mangled in (pem.replace("\n", " "), pem.replace("\n", "\r\n"), "﻿" + pem, "  " + pem.replace("\n", "\n  ")):
        ssl.create_default_context().load_verify_locations(cadata=normalize_pem(mangled))
    with TestClient(create_app(make_settings()), headers=AUTH) as c:
        r = c.put("/api/v1/server-settings", json={"values": {"ad_ca_pem": pem.replace("\n", " ")}})
        assert r.status_code == 200, r.text
        bad = c.put(
            "/api/v1/server-settings",
            json={"values": {"ad_ca_pem": "-----BEGIN CERTIFICATE----- !!! -----END CERTIFICATE-----"}},
        )
        assert bad.status_code == 422 and bad.json()["detail"]["key"] == "ad_ca_pem"
    with TestClient(create_app(make_settings()), headers=AUTH) as c:
        stored = c.app.state.settings.ad_ca_pem
        assert stored.startswith("-----BEGIN CERTIFICATE-----\n") and "\n-----END CERTIFICATE-----" in stored


def test_controller_certificate_is_read_for_the_operator_to_check(seeded, tmp_path):
    import datetime
    import socket
    import ssl
    import threading

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "rukrsrvdc01.ecotehs.local")])
    now = datetime.datetime.now(datetime.UTC)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(7)
        .not_valid_before(now)
        .not_valid_after(now + datetime.timedelta(days=1))
        .sign(key, hashes.SHA256())
    )
    (tmp_path / "c.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (tmp_path / "k.pem").write_bytes(
        key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    )
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(tmp_path / "c.pem", tmp_path / "k.pem")
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)

    def serve():
        conn, _ = srv.accept()
        try:
            ctx.wrap_socket(conn, server_side=True).close()
        except OSError:
            pass

    threading.Thread(target=serve, daemon=True).start()
    with TestClient(create_app(make_settings()), headers=AUTH) as c:
        url = f"ldaps://127.0.0.1:{srv.getsockname()[1]}"
        r = c.post("/api/v1/server-settings/controller-certificate", json={"url": url}).json()
        assert r["self_signed"] and "rukrsrvdc01" in r["subject"] and r["sha256"].count(":") == 31
        assert r["pem"].startswith("-----BEGIN CERTIFICATE-----")
        bad = c.post("/api/v1/server-settings/controller-certificate", json={"url": "ldap://x"})
        assert bad.status_code == 409 and bad.json()["detail"]["code"] == "certificate_failed"
    srv.close()
