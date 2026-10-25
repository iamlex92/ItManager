import asyncio
import contextlib
import uuid

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, status

from ..services.realtime import ALL
from ..services.serialize import port_out
from ..services.switches import load_switch
from ..services.topology import load_graph
from .deps import token_ok

router = APIRouter()


def _ws_token(ws: WebSocket) -> str | None:
    token = ws.query_params.get("token")
    if token:
        return token
    auth = ws.headers.get("authorization", "")
    if auth.lower().startswith("bearer "):
        return auth[7:]
    return None


@router.websocket("/ws")
async def live(ws: WebSocket):
    """Live port status and alerts.

    Client -> {"action": "subscribe", "device_id": "<uuid>" | "*"}
              {"action": "unsubscribe", "device_id": ...}
    Server -> {"type": "snapshot", "device_id", "ports": [...]} after subscribe
              {"type": "port_status", "device_id", "port": {...}}
              {"type": "device_status", "device_id", "is_online"}
              {"type": "alert", "alert": {...}}
              {"type": "error", "message"}
    """
    await ws.accept()
    if not token_ok(ws.app.state.settings, _ws_token(ws)):
        # Accept first so the client gets a readable close code and reason.
        await ws.close(code=status.WS_1008_POLICY_VIOLATION, reason="unauthorized")
        return
    hub = ws.app.state.hub
    queue = hub.register()

    async def pump() -> None:
        while True:
            await ws.send_json(await queue.get())

    sender = asyncio.create_task(pump())
    try:
        while True:
            msg = await ws.receive_json()
            action = msg.get("action") if isinstance(msg, dict) else None
            key = str(msg.get("device_id", "")) if isinstance(msg, dict) else ""
            if action not in {"subscribe", "unsubscribe"} or not key:
                await queue.put({"type": "error", "message": "expected {action: subscribe|unsubscribe, device_id}"})
                continue
            if key != ALL:
                try:
                    key = str(uuid.UUID(key))
                except ValueError:
                    await queue.put({"type": "error", "message": "device_id must be a UUID or *"})
                    continue
            if action == "unsubscribe":
                hub.unsubscribe(queue, key)
                continue
            hub.subscribe(queue, key)
            if key != ALL:
                await queue.put(await _snapshot(ws, uuid.UUID(key)))
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        hub.unregister(queue)
        sender.cancel()
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await sender


async def _snapshot(ws: WebSocket, device_id: uuid.UUID) -> dict:
    async with ws.app.state.sessionmaker() as session:
        device = await load_switch(session, device_id)
        if device is None:
            return {"type": "error", "message": f"device {device_id} not found"}
        graph = await load_graph(session)
        return {
            "type": "snapshot",
            "device_id": str(device_id),
            "is_online": device.is_online,
            "ports": [port_out(p, graph).model_dump(mode="json") for p in device.ports],
        }
