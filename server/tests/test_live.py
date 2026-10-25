import pytest
from starlette.websockets import WebSocketDisconnect

from app.services.switches import device_target, load_switch

from .conftest import TOKEN, find, ports_of


def _pull_cable(client, switch_name, port_number, up):
    """Use the emulator to simulate a cable event, as a real link change would."""
    app = client.app
    sw = find(client, switch_name)

    async def go():
        import uuid

        async with app.state.sessionmaker() as s:
            dev = await load_switch(s, uuid.UUID(sw["id"]))
            await app.state.driver.set_link(device_target(dev), port_number, up)

    client.portal.call(go)
    return sw


def test_link_down_raises_alert_and_up_resolves_it(client):
    sw = _pull_cable(client, "SW-ACC-03", 2, up=False)
    client.post(f"/api/v1/devices/{sw['id']}/refresh")
    port = ports_of(client, sw["id"])[1]
    assert port["is_oper_up"] is False
    alerts = [
        a for a in client.get("/api/v1/alerts", params={"is_resolved": False}).json() if a["port_id"] == port["id"]
    ]
    assert len(alerts) == 1 and alerts[0]["alert_type"] == "LINK_DOWN" and alerts[0]["severity"] == "WARNING"
    assert find(client, "PC-OK-02")["is_online"] is False

    _pull_cable(client, "SW-ACC-03", 2, up=True)
    client.post(f"/api/v1/devices/{sw['id']}/refresh")
    assert not [
        a for a in client.get("/api/v1/alerts", params={"is_resolved": False}).json() if a["port_id"] == port["id"]
    ]
    assert find(client, "PC-OK-02")["is_online"] is True


def test_websocket_requires_token(client):
    with client.websocket_connect("/api/v1/ws?token=wrong") as ws:
        with pytest.raises(WebSocketDisconnect) as e:
            ws.receive_json()
    assert e.value.code == 1008


def test_websocket_snapshot_and_updates(client):
    sw = find(client, "SW-ACC-01")
    with client.websocket_connect(f"/api/v1/ws?token={TOKEN}") as ws:
        ws.send_json({"action": "subscribe", "device_id": sw["id"]})
        snap = ws.receive_json()
        assert snap["type"] == "snapshot" and len(snap["ports"]) == 28

        _pull_cable(client, "SW-ACC-01", 3, up=False)
        client.post(f"/api/v1/devices/{sw['id']}/refresh")
        events = [ws.receive_json(), ws.receive_json()]
        by_type = {e["type"]: e for e in events}
        assert by_type["port_status"]["port"]["number"] == 3
        assert by_type["port_status"]["port"]["is_oper_up"] is False
        assert by_type["alert"]["alert"]["alert_type"] == "LINK_DOWN"


def test_websocket_bad_message(client):
    with client.websocket_connect("/api/v1/ws", headers={"Authorization": f"Bearer {TOKEN}"}) as ws:
        ws.send_json({"action": "subscribe", "device_id": "nope"})
        assert ws.receive_json()["type"] == "error"
