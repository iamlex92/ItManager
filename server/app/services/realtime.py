"""In-process pub/sub for WebSocket clients.

Single-process only. When the server runs more than one worker this moves
to Redis pub/sub (see spec), the interface stays the same.
"""

import asyncio
import uuid
from typing import Any

ALL = "*"


class Hub:
    def __init__(self, queue_size: int = 1000) -> None:
        self._subs: dict[asyncio.Queue[dict[str, Any]], set[str]] = {}
        self._queue_size = queue_size

    def register(self) -> asyncio.Queue[dict[str, Any]]:
        q: asyncio.Queue[dict[str, Any]] = asyncio.Queue(self._queue_size)
        self._subs[q] = set()
        return q

    def unregister(self, q: asyncio.Queue[dict[str, Any]]) -> None:
        self._subs.pop(q, None)

    def subscribe(self, q: asyncio.Queue[dict[str, Any]], key: str) -> None:
        self._subs[q].add(key)

    def unsubscribe(self, q: asyncio.Queue[dict[str, Any]], key: str) -> None:
        self._subs[q].discard(key)

    def publish(self, device_id: uuid.UUID | None, message: dict[str, Any]) -> None:
        key = str(device_id) if device_id else None
        for q, keys in list(self._subs.items()):
            if ALL in keys or (key is not None and key in keys):
                try:
                    q.put_nowait(message)
                except asyncio.QueueFull:
                    # Slow client: drop the event rather than block everyone.
                    pass

    @property
    def client_count(self) -> int:
        return len(self._subs)
