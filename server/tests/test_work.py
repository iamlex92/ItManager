from datetime import date, timedelta

from app.services.repeat import next_due

from .conftest import find


def _d(n: int) -> str:
    return (date.today() + timedelta(days=n)).isoformat()


def test_repeat_arithmetic_keeps_the_calendar_honest():
    assert next_due(date(2026, 1, 31), "monthly") == date(2026, 2, 28)
    assert next_due(date(2024, 2, 29), "yearly") == date(2025, 2, 28)
    assert next_due(date(2026, 11, 13), "weekly", 2) == date(2026, 11, 27)
    assert next_due(date(2026, 12, 31), "daily", 1) == date(2027, 1, 1)
    assert next_due(date(2026, 11, 13), "monthly", 3) == date(2027, 2, 13)
    assert next_due(date(2026, 11, 13), "none") is None


def test_task_lifecycle_and_ordering(client):
    pc = find(client, "PC-BUH-01")
    late = client.post("/api/v1/tasks", json={"title": "Просрочено", "due_date": _d(-3)}).json()
    soon = client.post("/api/v1/tasks", json={"title": "Скоро", "due_date": _d(2), "device_id": pc["id"]}).json()
    nodate = client.post("/api/v1/tasks", json={"title": "Без срока"}).json()
    titles = [t["title"] for t in client.get("/api/v1/tasks").json()]
    assert titles.index("Просрочено") < titles.index("Скоро") < titles.index("Без срока")
    assert late["days_left"] == -3 and soon["device_name"] == "PC-BUH-01" and nodate["days_left"] is None
    s = client.get("/api/v1/tasks/summary").json()
    assert s["overdue"] >= 1 and s["week"] >= 1
    assert [t["title"] for t in client.get("/api/v1/tasks", params={"device_id": pc["id"]}).json()] == ["Скоро"]
    done = client.post(f"/api/v1/tasks/{soon['id']}/complete").json()
    assert done["done"]["is_done"] and done["next"] is None
    assert "Скоро" not in [t["title"] for t in client.get("/api/v1/tasks").json()]
    assert "Скоро" in [t["title"] for t in client.get("/api/v1/tasks", params={"include_done": True}).json()]
    assert client.post(f"/api/v1/tasks/{soon['id']}/reopen").json()["is_done"] is False
    assert (
        client.post(
            "/api/v1/tasks", json={"title": "x", "device_id": "00000000-0000-0000-0000-000000000000"}
        ).status_code
        == 404
    )
    for t in (late, soon, nodate):
        client.delete(f"/api/v1/tasks/{t['id']}")


def test_finishing_a_repeating_task_makes_the_next_one_in_the_future(client):
    t = client.post("/api/v1/tasks", json={"title": "Проверить ИБП", "due_date": _d(-40), "repeat": "monthly"}).json()
    out = client.post(f"/api/v1/tasks/{t['id']}/complete").json()
    nxt = out["next"]
    assert nxt and nxt["title"] == "Проверить ИБП" and nxt["repeat"] == "monthly"
    # Done 40 days late: the next one is not in the past.
    assert nxt["days_left"] >= 0
    # Finishing it again does not make a second copy.
    assert client.post(f"/api/v1/tasks/{t['id']}/complete").json()["next"] is None
    # A repeat without a date counts from today.
    t2 = client.post("/api/v1/tasks", json={"title": "Каждую неделю", "repeat": "weekly"}).json()
    assert t2["due_date"] == date.today().isoformat()
    for x in (t, nxt, t2):
        client.delete(f"/api/v1/tasks/{x['id']}")


def test_documents_are_searched_and_the_list_carries_only_an_excerpt(client):
    sw = find(client, "SW-ACC-01")
    long_text = "# Коммутатор\n\n" + "Строка паспорта. " * 40 + "\nуникальноеслово42"
    d = client.post(
        "/api/v1/documents",
        json={"title": "Паспорт серверной", "category": "passport", "body": long_text, "device_id": sw["id"]},
    ).json()
    assert d["device_name"] == "SW-ACC-01" and d["body"] == long_text
    brief = client.get("/api/v1/documents", params={"q": "уникальноеслово42"}).json()
    assert [b["title"] for b in brief] == ["Паспорт серверной"]
    assert "body" not in brief[0] and len(brief[0]["excerpt"]) <= 160
    assert client.get("/api/v1/documents", params={"q": "Паспорт серверной", "category": "note"}).json() == []
    assert client.get("/api/v1/documents", params={"device_id": sw["id"]}).json()[0]["id"] == d["id"]
    pinned = client.post("/api/v1/documents", json={"title": "Я", "pinned": True}).json()
    order = [b["title"] for b in client.get("/api/v1/documents").json()]
    assert order[0] == "Я"
    upd = client.patch(f"/api/v1/documents/{d['id']}", json={"body": "новое"}).json()
    assert upd["body"] == "новое" and upd["title"] == "Паспорт серверной"
    assert client.patch(f"/api/v1/documents/{d['id']}", json={"category": "bogus"}).status_code == 422
    for x in (d, pinned):
        assert client.delete(f"/api/v1/documents/{x['id']}").status_code == 204
    assert client.get(f"/api/v1/documents/{d['id']}").status_code == 404
