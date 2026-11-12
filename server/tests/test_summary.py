def test_summary_counts_and_lists(client):
    r = client.get("/api/v1/summary")
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"devices", "alerts", "recent"}
    assert body["devices"]["total"] >= body["devices"]["online"] >= 0
    assert body["alerts"]["open"] >= body["alerts"]["critical"] >= 0
    assert all({"id", "name", "type"} <= set(d) for d in body["devices"]["down"])


def test_search_all_finds_by_device_and_vlan(client):
    first = client.get("/api/v1/devices", params={"limit": 1}).json()
    name = (first["items"] if isinstance(first, dict) else first)[0]["name"]
    r = client.get("/api/v1/search/all", params={"q": name[:4]})
    assert r.status_code == 200
    assert set(r.json()) == {"devices", "ports", "vlans", "peripherals"}
    assert any(d["name"] == name for d in r.json()["devices"])
    assert client.get("/api/v1/search/all", params={"q": "x"}).status_code == 422
