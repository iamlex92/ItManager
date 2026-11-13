from datetime import date, timedelta

from app.services.lifecycle import level_of

from .conftest import audit_rows, find


def _days(n: int) -> str:
    return (date.today() + timedelta(days=n)).isoformat()


def test_levels_by_days_left():
    assert [level_of(d) for d in (-1, 0, 7, 8, 30, 31, 60, 61)] == [
        "expired", "week", "week", "month", "month", "soon", "soon", "ok",
    ]  # fmt: skip


def test_device_lifecycle_fields_round_trip_and_are_searchable(client):
    pc = find(client, "PC-BUH-01")
    body = {
        "serial_number": "SN-LC-777",
        "inventory_number": "INV-0042",
        "purchase_date": "2024-03-01",
        "warranty_until": _days(20),
        "supplier": "Рога и копыта",
        "assigned_to": "Иванова, бухгалтерия",
    }
    out = client.patch(f"/api/v1/devices/{pc['id']}", json=body).json()
    assert (
        out["serial_number"] == "SN-LC-777"
        and out["warranty_until"] == _days(20)
        and out["assigned_to"].startswith("Иванова")
    )
    assert [d["name"] for d in client.get("/api/v1/search", params={"q": "SN-LC-777"}).json()] == ["PC-BUH-01"]
    items = client.get("/api/v1/lifecycle/expirations").json()
    mine = [i for i in items if i["source"] == "warranty" and i["device_id"] == pc["id"]]
    assert mine and mine[0]["level"] == "month" and mine[0]["days_left"] == 20
    client.patch(f"/api/v1/devices/{pc['id']}", json={"warranty_until": None})


def test_license_key_is_hidden_in_lists_and_revealing_it_is_journaled(client):
    r = client.post("/api/v1/licenses", json={
        "software": "Kaspersky Endpoint", "kind": "per_device", "seats": 2, "key": "AAAAA-BBBBB-CCCCC-1234",
        "valid_until": _days(5), "supplier": "Софтлайн",
    })  # fmt: skip
    assert r.status_code == 201, r.text
    lic = r.json()
    assert lic["has_key"] and lic["key_hint"] == "••••1234" and "key" not in lic
    assert "AAAAA" not in str(client.get("/api/v1/licenses").json())
    shown = client.post(f"/api/v1/licenses/{lic['id']}/reveal")
    assert shown.json() == {"key": "AAAAA-BBBBB-CCCCC-1234"}
    rows = [r for r in audit_rows() if r[0] == "license_reveal"]
    assert rows and "Kaspersky Endpoint" in rows[-1][2] and "AAAAA" not in str(audit_rows())
    exp = [i for i in client.get("/api/v1/lifecycle/expirations").json() if i["source"] == "license"]
    assert any(i["title"].endswith("Kaspersky Endpoint") and i["level"] == "week" for i in exp)
    client.delete(f"/api/v1/licenses/{lic['id']}")


def test_license_seats_follow_the_computers_it_stands_on(client):
    ids = [find(client, n)["id"] for n in ("PC-BUH-01", "PC-BUH-02", "PC-BUH-03")]
    lic = client.post("/api/v1/licenses", json={"software": "1С:Предприятие", "seats": 2, "device_ids": ids[:2]}).json()
    assert lic["used"] == 2 and lic["over"] is False
    more = client.patch(f"/api/v1/licenses/{lic['id']}", json={"device_ids": ids}).json()
    assert more["used"] == 3 and more["over"] is True
    assert client.get("/api/v1/lifecycle/summary").json()["over_licensed"] >= 1
    # Leaving the key out of an edit keeps it; an empty one clears it.
    keyed = client.patch(f"/api/v1/licenses/{lic['id']}", json={"key": "K-1"}).json()
    assert client.patch(f"/api/v1/licenses/{lic['id']}", json={"notes": "x"}).json()["has_key"] is True
    assert (
        keyed["has_key"] and client.patch(f"/api/v1/licenses/{lic['id']}", json={"key": ""}).json()["has_key"] is False
    )
    assert (
        client.patch(
            f"/api/v1/licenses/{lic['id']}", json={"device_ids": [str(__import__("uuid").uuid4())]}
        ).status_code
        == 404
    )
    client.delete(f"/api/v1/licenses/{lic['id']}")


def test_deadlines_come_in_order_and_done_ones_drop_out(client):
    a = client.post("/api/v1/deadlines", json={"title": "Батарея ИБП", "kind": "battery", "due_date": _days(3)}).json()
    b = client.post(
        "/api/v1/deadlines", json={"title": "Домен example.ru", "kind": "domain", "due_date": _days(40)}
    ).json()
    c = client.post("/api/v1/deadlines", json={"title": "Договор", "kind": "contract", "due_date": _days(-2)}).json()
    titles = [i["title"] for i in client.get("/api/v1/lifecycle/expirations").json() if i["source"] == "deadline"]
    assert titles == ["Договор", "Батарея ИБП", "Домен example.ru"]
    assert client.get("/api/v1/lifecycle/expirations", params={"within_days": 10}).json()
    client.patch(f"/api/v1/deadlines/{c['id']}", json={"is_done": True})
    titles = [i["title"] for i in client.get("/api/v1/lifecycle/expirations").json() if i["source"] == "deadline"]
    assert "Договор" not in titles
    for d in (a, b, c):
        client.delete(f"/api/v1/deadlines/{d['id']}")
