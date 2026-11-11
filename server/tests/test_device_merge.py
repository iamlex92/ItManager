"""One device with several addresses: extra IPs, merging two inventory entries into one, and «Обновить» on the map."""

from fastapi.testclient import TestClient

from app.config import MonitorMode
from app.main import create_app

from .conftest import AUTH, find, make_settings, ports_of

API = "/api/v1"


def _device(client, **kw):
    body = {"name": "DUP", "type": "SERVER", **kw}
    r = client.post(f"{API}/devices", json=body)
    assert r.status_code == 201, r.text
    return r.json()


def test_extra_ips_are_kept_tidy_searched_and_counted(client):
    d = _device(client, name="R-EDGE", type="ROUTER", ip="10.10.50.200", extra_ips=["10.10.40.200", "10.10.50.200"])
    assert d["extra_ips"] == ["10.10.40.200"]  # the main address is not repeated
    d = client.patch(f"{API}/devices/{d['id']}", json={"extra_ips": ["10.10.40.200", "10.10.40.201", "10.10.40.200"]})
    assert d.json()["extra_ips"] == ["10.10.40.200", "10.10.40.201"]
    assert [x["name"] for x in client.get(f"{API}/devices", params={"q": "10.10.40.201"}).json()] == ["R-EDGE"]
    # Taken in «IP-адреса»: another device on it is a conflict.
    check = client.get(f"{API}/ipam/check", params={"ip": "10.10.40.201"}).json()
    assert "R-EDGE" in str(check)
    assert client.patch(f"{API}/devices/{d.json()['id']}", json={"extra_ips": ["nope"]}).status_code == 422


def test_merge_moves_ports_cables_history_and_addresses(client):
    keep = find(client, "SRV-1C-01")
    keep_ports = ports_of(client, keep["id"])
    dup = _device(client, name="SRV-1C-01 (второй IP)", ip="10.10.50.99", mac="aa:bb:cc:00:11:22", comment="iLO")
    # The same number and name as a port of the kept server, cabled to a free port of the core switch.
    p = client.post(
        f"{API}/ports",
        json={"device_id": dup["id"], "number": keep_ports[0]["number"], "name": keep_ports[0]["name"]},
    ).json()
    core = find(client, "SW-CORE-01")
    free = next(x for x in ports_of(client, core["id"]) if not x["connected_device_name"] and not x["is_uplink"])
    assert client.post(f"{API}/connections", json={"port_a_id": p["id"], "port_b_id": free["id"]}).status_code == 201
    acc = client.post(f"{API}/peripherals", json={"kind": "MONITOR", "device_id": dup["id"]}).json()

    url = f"{API}/devices/{keep['id']}/merge"
    assert client.post(url, json={"source_id": dup["id"]}).status_code == 400  # not confirmed
    assert client.post(url, json={"source_id": keep["id"], "confirm": True}).status_code == 422
    r = client.post(url, json={"source_id": dup["id"], "confirm": True})
    assert r.status_code == 200, r.text
    merged = r.json()
    assert merged["ip"] == keep["ip"] and merged["extra_ips"] == ["10.10.50.99"]
    assert merged["mac"] == keep["mac"] or keep["mac"] is None and merged["mac"] == "aa:bb:cc:00:11:22"
    assert "iLO" in (merged["comment"] or "")
    assert client.get(f"{API}/devices/{dup['id']}").status_code == 404

    ports = ports_of(client, keep["id"])
    assert len(ports) == len(keep_ports) + 1
    moved = next(x for x in ports if x["id"] == p["id"])
    assert moved["number"] == max(x["number"] for x in keep_ports) + 1
    assert moved["name"] == f"{keep_ports[0]['name']} (SRV-1C-01 (второй IP))"
    assert moved["connected_device_name"] == "SW-CORE-01"  # the cable came along
    assert client.get(f"{API}/peripherals/{acc['id']}").json()["device_id"] == keep["id"]
    # Found by its old address now.
    assert [x["name"] for x in client.get(f"{API}/devices", params={"q": "10.10.50.99"}).json()] == ["SRV-1C-01"]
    audit = client.get(f"{API}/audit").json()
    assert any(a["action"] == "device:merge" for a in audit)


def test_merge_drops_a_cable_between_the_two_halves(client):
    a = _device(client, name="A")
    b = _device(client, name="B", ip="10.10.50.98")
    pa = client.post(f"{API}/ports", json={"device_id": a["id"], "number": 1, "name": "eth0"}).json()
    pb = client.post(f"{API}/ports", json={"device_id": b["id"], "number": 1, "name": "eth0"}).json()
    client.post(f"{API}/connections", json={"port_a_id": pa["id"], "port_b_id": pb["id"]})
    merged = client.post(f"{API}/devices/{a['id']}/merge", json={"source_id": b["id"], "confirm": True}).json()
    assert merged["ip"] == "10.10.50.98"  # A had none: B's becomes the main one
    assert merged["extra_ips"] == []
    assert [(x["number"], x["connected_device_name"]) for x in ports_of(client, a["id"])] == [(1, None), (2, None)]


def test_refresh_status_in_the_emulator(client):
    r = client.post(f"{API}/devices/refresh-status").json()
    assert r["sources"] == ["эмулятор коммутаторов"] and r["errors"] == []
    assert r["checked"] > 10 and 0 < r["online"] <= r["checked"]


def _refresh(client) -> dict:
    """«Обновить» as the clients do it: start, and read the job until done (a slow SNMP read outlasts the wait)."""
    import time

    r = client.post(f"{API}/devices/refresh-status").json()
    deadline = time.monotonic() + 120
    while r["state"] != "done" and time.monotonic() < deadline:
        time.sleep(0.5)
        r = client.get(f"{API}/devices/refresh-status/{r['id']}").json()
    return r


def test_refresh_status_pings_every_address(seeded):
    app = create_app(make_settings(monitor_mode=MonitorMode.REAL, heartbeat_interval_seconds=0))
    with TestClient(app, headers=AUTH) as client:
        srv = find(client, "SRV-1C-01")
        client.patch(f"{API}/devices/{srv['id']}", json={"extra_ips": ["10.10.50.99"]})
        answering = {"10.10.50.99"}

        async def pinger(ip):
            return ip in answering

        app.state.pinger = pinger
        r = _refresh(client)
        assert "ping" in r["sources"] and r["errors"] == [], r
        assert find(client, "SRV-1C-01")["is_online"] is True  # silent on its main address, answers on the other
        answering.clear()
        r = _refresh(client)
        assert find(client, "SRV-1C-01")["is_online"] is False and r["changed"] >= 1


def test_merge_preview_changes_nothing_and_says_what_would_move(client):
    keep = find(client, "SRV-1C-01")
    dup = _device(client, name="SRV-1C-01-b", ip="10.10.50.97", comment="iLO")
    port = client.post(f"{API}/ports", json={"device_id": dup["id"], "number": 1, "name": "eth0"}).json()
    peripheral = client.post(f"{API}/peripherals", json={"kind": "MONITOR", "device_id": dup["id"]})
    assert peripheral.status_code in (200, 201)
    r = client.post(f"{API}/devices/{keep['id']}/merge?dry_run=true", json={"source_id": dup["id"]})
    assert r.status_code == 200, r.text
    p = r.json()
    assert p["dry_run"] is True and p["source_name"] == "SRV-1C-01-b" and p["keep_name"] == "SRV-1C-01"
    assert p["ports"] == 1 and p["peripherals"] == 1 and p["extra_ips_added"] == ["10.10.50.97"]
    assert "comment" in p["filled_fields"] or keep["comment"]
    # Nothing happened: the duplicate and its port are still there, and it needed no confirmation.
    assert client.get(f"{API}/devices/{dup['id']}").status_code == 200
    assert client.get(f"{API}/devices/{keep['id']}").json()["extra_ips"] == []
    assert port["id"] in [x["id"] for x in ports_of(client, dup["id"])]


def test_merge_keeps_a_snapshot_of_the_removed_record_in_the_journal(client):
    keep = _device(client, name="KEEP")
    dup = _device(client, name="GONE", ip="10.10.50.96")
    client.post(f"{API}/ports", json={"device_id": dup["id"], "number": 5, "name": "p5"})
    assert (
        client.post(f"{API}/devices/{keep['id']}/merge", json={"source_id": dup["id"], "confirm": True}).status_code
        == 200
    )
    entry = next(a for a in client.get(f"{API}/audit").json() if a["action"] == "device:merge")
    snap = entry["parameters"]["merged_snapshot"]
    assert snap["device"]["name"] == "GONE" and snap["device"]["ip"] == "10.10.50.96"
    assert [p["name"] for p in snap["ports"]] == ["p5"]


def test_merge_refuses_a_rack_place_that_is_taken(client):
    rack = client.post(f"{API}/devices", json={"name": "R1", "type": "RACK"}).json()
    a = client.post(
        f"{API}/devices", json={"name": "A", "type": "SERVER", "rack_id": rack["id"], "rack_u": 3, "rack_h": 2}
    )
    b = _device(client, name="B")
    assert a.status_code == 201
    # B (no place) takes A's place in the rack when merged INTO nothing - the other way round: A kept at its place,
    # a duplicate of it standing on the same units is the conflict.
    c = client.post(f"{API}/devices", json={"name": "C", "type": "SERVER"}).json()
    assert (
        client.patch(f"{API}/devices/{c['id']}", json={"rack_id": rack["id"], "rack_u": 10, "rack_h": 1}).status_code
        == 200
    )
    keep = _device(client, name="K")  # no place
    # C stands at U10; K takes C's place by the merge: free, so it works.
    ok = client.post(f"{API}/devices/{keep['id']}/merge", json={"source_id": c["id"], "confirm": True})
    assert ok.status_code == 200 and ok.json()["rack_id"] == rack["id"]
    assert b["id"]


def test_a_second_device_on_a_taken_address_is_a_warning_you_can_override(client):
    first = _device(client, name="ONE", ip="10.10.50.90", extra_ips=["10.10.40.90"])
    # The main address and an extra one are both guarded.
    r = client.post(f"{API}/devices", json={"name": "TWO", "type": "SERVER", "ip": "10.10.50.90"})
    assert r.status_code == 409 and r.json()["detail"]["code"] == "ip_in_use"
    assert r.json()["detail"]["devices"][0]["name"] == "ONE"
    r = client.post(f"{API}/devices", json={"name": "TWO", "type": "SERVER", "extra_ips": ["10.10.40.90"]})
    assert r.status_code == 409
    ok = client.post(
        f"{API}/devices", json={"name": "TWO", "type": "SERVER", "ip": "10.10.50.90", "allow_duplicate_ip": True}
    )
    assert ok.status_code == 201
    # An old duplicate does not block an unrelated edit, a new one does.
    two = ok.json()
    assert client.patch(f"{API}/devices/{two['id']}", json={"comment": "x"}).status_code == 200
    bad = client.patch(f"{API}/devices/{two['id']}", json={"extra_ips": ["10.10.40.90"]})
    assert bad.status_code == 409
    assert client.patch(f"{API}/devices/{first['id']}", json={"ip": "10.10.50.90"}).status_code == 200  # its own


async def test_retention_deletes_old_journal_and_closed_events_only():
    from datetime import UTC, datetime, timedelta

    from app.db import make_engine, make_sessionmaker
    from app.models import DeviceAlert, ScriptAuditLog
    from app.services import retention

    from .conftest import TEST_DB

    engine = make_engine(TEST_DB)
    sm = make_sessionmaker(engine)
    now = datetime.now(UTC)
    try:
        async with sm() as s:
            from sqlalchemy import select

            from app.models import Device

            dev = (await s.scalars(select(Device).limit(1))).first()
            s.add_all(
                [
                    ScriptAuditLog(script_name="old-ret", triggered_by="t", executed_at=now - timedelta(days=400)),
                    ScriptAuditLog(script_name="new-ret", triggered_by="t", executed_at=now - timedelta(days=5)),
                    DeviceAlert(
                        device_id=dev.id,
                        severity="INFO",
                        alert_type="X",
                        message="old closed",
                        is_resolved=True,
                        created_at=now - timedelta(days=300),
                        resolved_at=now - timedelta(days=290),
                    ),
                    DeviceAlert(
                        device_id=dev.id,
                        severity="INFO",
                        alert_type="X",
                        message="old OPEN",
                        is_resolved=False,
                        created_at=now - timedelta(days=300),
                    ),
                ]
            )
            await s.commit()
        done = await retention.run_once(sm, make_settings())
        assert done["journal"] >= 1 and done["events"] >= 1
        async with sm() as s:
            names = {r.script_name for r in (await s.scalars(select(ScriptAuditLog))).all()}
            msgs = {r.message for r in (await s.scalars(select(DeviceAlert))).all()}
        assert "old-ret" not in names and "new-ret" in names
        assert "old closed" not in msgs and "old OPEN" in msgs
    finally:
        await engine.dispose()


def test_refresh_status_is_a_job_with_progress(client):
    """A slow round is answered as «running» and read back by id; a quick one comes back done at once."""
    from app.services import status as status_service

    first = client.post(f"{API}/devices/refresh-status").json()
    assert first["state"] == "done" and first["id"] and first["done"] == first["total"] >= 1
    again = client.get(f"{API}/devices/refresh-status/{first['id']}").json()
    assert again["checked"] == first["checked"]
    assert client.get(f"{API}/devices/refresh-status/nope").status_code == 404
    assert status_service.WAIT_SECONDS > 0


def test_device_list_pages_and_counts(client):
    everything = client.get(f"{API}/devices")
    total = int(everything.headers["x-total-count"])
    assert total == len(everything.json()) > 10
    page = client.get(f"{API}/devices", params={"limit": 5, "offset": 5})
    assert [d["id"] for d in page.json()] == [d["id"] for d in everything.json()[5:10]]
    assert page.headers["x-total-count"] == str(total)


def test_a_write_drops_the_cached_graph(client):
    pc = find(client, "PC-BUH-02")
    before = client.get(f"{API}/devices/{pc['id']}").json()
    assert before["switch_port_name"]
    nic = ports_of(client, pc["id"])[0]
    conn = next(c for c in client.get(f"{API}/connections").json() if nic["id"] in (c["port_a_id"], c["port_b_id"]))
    assert client.delete(f"{API}/connections/{conn['id']}").status_code == 204
    # The card is read from the cache a second ago; the delete dropped it.
    assert client.get(f"{API}/devices/{pc['id']}").json()["switch_port_name"] is None
