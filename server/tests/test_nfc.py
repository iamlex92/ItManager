from .conftest import find


def test_resolve_by_payload_and_uid(client):
    sw = find(client, "SW-CORE-01")
    payload = client.get(f"/api/v1/devices/{sw['id']}/nfc-payload").json()
    assert payload == {"app": "netadmin", "v": 1, "id": sw["id"], "type": "switch", "name": "SW-CORE-01"}
    assert client.post("/api/v1/nfc/resolve", json={"payload": payload}).json()["id"] == sw["id"]
    # UID in any case / separators
    raw = sw["nfc_tag_uid"].replace(":", "").lower()
    assert client.post("/api/v1/nfc/resolve", json={"uid": raw}).json()["id"] == sw["id"]
    assert client.get(f"/api/v1/devices/by-nfc/{raw}").json()["id"] == sw["id"]


def test_resolve_errors(client):
    sw = find(client, "SW-CORE-01")
    foreign = {"app": "other", "v": 1, "id": sw["id"]}
    assert client.post("/api/v1/nfc/resolve", json={"payload": foreign}).status_code == 422
    assert client.post("/api/v1/nfc/resolve", json={}).status_code == 422
    assert client.post("/api/v1/nfc/resolve", json={"uid": "04:00:00:00:00:00:99"}).status_code == 404
    assert client.post("/api/v1/nfc/resolve", json={"uid": "zz"}).status_code == 422
    assert client.get("/api/v1/devices/by-nfc/not-hex").status_code == 422


def test_bind_conflict_force_and_unbind(client):
    core = find(client, "SW-CORE-01")
    pc = find(client, "PC-BUH-01")
    r = client.put(f"/api/v1/devices/{pc['id']}/nfc", json={"uid": "04-aa-bb-cc-dd-ee-ff"})
    assert r.status_code == 200 and r.json()["nfc_tag_uid"] == "04:AA:BB:CC:DD:EE:FF"

    r = client.put(f"/api/v1/devices/{pc['id']}/nfc", json={"uid": core["nfc_tag_uid"]})
    assert r.status_code == 409
    assert r.json()["detail"]["code"] == "tag_bound_elsewhere" and r.json()["detail"]["device_name"] == "SW-CORE-01"

    r = client.put(f"/api/v1/devices/{pc['id']}/nfc", json={"uid": core["nfc_tag_uid"], "force": True})
    assert r.status_code == 200
    assert find(client, "SW-CORE-01")["nfc_tag_uid"] is None

    assert client.delete(f"/api/v1/devices/{pc['id']}/nfc").status_code == 204
    assert find(client, "PC-BUH-01")["nfc_tag_uid"] is None


def test_patch_normalizes_uid(client):
    pc = find(client, "PC-BUH-01")
    r = client.patch(f"/api/v1/devices/{pc['id']}", json={"nfc_tag_uid": "04a1b2c3d4e5f6"})
    assert r.json()["nfc_tag_uid"] == "04:A1:B2:C3:D4:E5:F6"
