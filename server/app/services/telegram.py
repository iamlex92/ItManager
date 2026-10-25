"""Alerts to a Telegram chat.

Off unless both TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID are set. The notifier listens to the same
hub as the app's live channel, so every alert source (poller, traps, heartbeat, printers, the API)
is covered without touching them. It only sends outwards to Telegram; nothing reaches equipment.

The bot token is part of the Bot API URL, so neither URLs nor httpx errors are ever logged.
"""

import asyncio
import logging
import time
from collections import deque
from collections.abc import Awaitable, Callable
from typing import Any

import httpx

from ..config import HalMode, MonitorMode, Settings
from .realtime import ALL, Hub

log = logging.getLogger(__name__)
# httpx logs every request URL at INFO, and the URL carries the token.
logging.getLogger("httpx").setLevel(logging.WARNING)

SEVERITY_RANK = {"INFO": 0, "WARNING": 1, "CRITICAL": 2}
SEVERITY_LABEL = {"INFO": "🔵 Информация", "WARNING": "🟠 Предупреждение", "CRITICAL": "🔴 Критично"}
ALERT_TYPE_LABEL = {
    "LINK_DOWN": "Порт упал",
    "HOST_DOWN": "Не отвечает",
    "TONER_LOW": "Мало тонера",
    "PRINTER_ERROR": "Ошибка МФУ",
    "CPU_OVERLOAD": "Перегрузка CPU",
}

Sender = Callable[[str], Awaitable[None]]
NameLookup = Callable[[str], Awaitable[str | None]]


class TelegramError(Exception):
    """A send failed; the message says why without the token."""


class BotApi:
    def __init__(self, token: str, chat_id: str, proxy: str = "", transport: httpx.AsyncBaseTransport | None = None):
        self._url = f"https://api.telegram.org/bot{token.strip()}/sendMessage"
        self._chat_id = chat_id.strip()
        self._client = httpx.AsyncClient(timeout=10, proxy=proxy.strip() or None, transport=transport)

    async def send(self, text: str) -> None:
        for _ in range(2):
            try:
                r = await self._client.post(
                    self._url, json={"chat_id": self._chat_id, "text": text, "disable_web_page_preview": True}
                )
            except httpx.HTTPError as e:
                # str(e) may contain the URL with the token.
                raise TelegramError(f"нет связи с api.telegram.org ({type(e).__name__})") from None
            if r.status_code == 429:
                retry = _retry_after(r)
                log.warning("telegram rate limit, retrying in %ss", retry)
                await asyncio.sleep(retry)
                continue
            if r.status_code != 200:
                raise TelegramError(_describe(r))
            return
        raise TelegramError("Telegram просит подождать (429)")

    async def aclose(self) -> None:
        await self._client.aclose()


def _retry_after(r: httpx.Response) -> float:
    try:
        return min(float(r.json().get("parameters", {}).get("retry_after", 5)), 60.0)
    except ValueError:
        return 5.0


def _describe(r: httpx.Response) -> str:
    try:
        desc = str(r.json().get("description", ""))[:200]
    except ValueError:
        desc = ""
    hint = {401: "неверный TELEGRAM_BOT_TOKEN", 400: "проверьте TELEGRAM_CHAT_ID", 403: "бот не добавлен в чат"}
    return f"Telegram ответил {r.status_code}: {hint.get(r.status_code, desc or 'ошибка')}"


def format_alert(alert: dict[str, Any], resolved: bool, emulated: bool, device_name: str | None = None) -> str:
    kind = ALERT_TYPE_LABEL.get(alert.get("alert_type", ""), alert.get("alert_type", ""))
    head = "✅ Восстановлено" if resolved else SEVERITY_LABEL.get(alert.get("severity", ""), alert.get("severity", ""))
    message = str(alert.get("message", ""))
    # Printer alerts say what ran low, not on which printer.
    if device_name and not message.startswith(device_name):
        message = f"{device_name}: {message}"
    lines = [f"{head} · {kind}" if kind else head, message]
    if emulated:
        lines.append("Эмуляция: реальное оборудование не опрашивается.")
    return "\n".join(lines)


class TelegramNotifier:
    def __init__(
        self,
        settings: Settings,
        hub: Hub,
        sender: Sender,
        device_name: NameLookup | None = None,
        clock: Callable[[], float] = time.monotonic,
    ):
        self._hub = hub
        self._send = sender
        self._device_name = device_name
        self._clock = clock
        self._min_rank = SEVERITY_RANK[settings.telegram_min_severity]
        self._delay = max(settings.telegram_delay_seconds, 0.0)
        self._notify_resolved = settings.telegram_notify_resolved
        self._max_per_minute = max(settings.telegram_max_per_minute, 1)
        self._emulated = settings.hal_mode is HalMode.EMULATOR and settings.monitor_mode is MonitorMode.EMULATOR
        self._pending: dict[str, asyncio.Task] = {}
        # Alerts whose "open" message went out; only those get a "resolved" one.
        self._sent: dict[str, None] = {}
        self._outbox: asyncio.Queue[tuple[dict[str, Any], bool]] = asyncio.Queue(500)
        self._recent: deque[float] = deque()
        self._suppressed = 0

    async def run(self) -> None:
        q = self._hub.register()
        self._hub.subscribe(q, ALL)
        worker = asyncio.create_task(self._deliver())
        try:
            while True:
                self.handle(await q.get())
        finally:
            self._hub.unregister(q)
            worker.cancel()
            for t in self._pending.values():
                t.cancel()

    def handle(self, message: dict[str, Any]) -> None:
        if message.get("type") != "alert":
            return
        alert = message.get("alert") or {}
        alert_id = str(alert.get("id"))
        if alert.get("is_resolved"):
            pending = self._pending.pop(alert_id, None)
            if pending:
                # Came back within the delay: nobody needs to hear about it.
                pending.cancel()
            if alert_id in self._sent:
                del self._sent[alert_id]
                if self._notify_resolved:
                    self._queue(alert, resolved=True)
            return
        if SEVERITY_RANK.get(alert.get("severity", ""), 0) < self._min_rank:
            return
        if alert_id in self._sent or alert_id in self._pending:
            return
        self._pending[alert_id] = asyncio.create_task(self._after_delay(alert_id, alert))

    async def _after_delay(self, alert_id: str, alert: dict[str, Any]) -> None:
        if self._delay:
            await asyncio.sleep(self._delay)
        self._pending.pop(alert_id, None)
        self._sent[alert_id] = None
        while len(self._sent) > 5000:
            del self._sent[next(iter(self._sent))]
        self._queue(alert, resolved=False)

    def _queue(self, alert: dict[str, Any], resolved: bool) -> None:
        try:
            self._outbox.put_nowait((alert, resolved))
        except asyncio.QueueFull:
            self._suppressed += 1

    async def _deliver(self) -> None:
        while True:
            await self._deliver_one(*await self._outbox.get())

    async def _deliver_one(self, alert: dict[str, Any], resolved: bool) -> None:
        now = self._clock()
        while self._recent and now - self._recent[0] >= 60:
            self._recent.popleft()
        if len(self._recent) >= self._max_per_minute:
            self._suppressed += 1
            return
        text = await self._text(alert, resolved)
        if self._suppressed:
            text += f"\n\nЕщё {self._suppressed} событий не отправлено из-за лимита. Смотрите приложение."
            self._suppressed = 0
        self._recent.append(now)
        try:
            await self._send(text)
        except TelegramError as e:
            log.warning("telegram alert not sent: %s", e)
        except Exception:  # noqa: BLE001 - a broken send must not stop alerting
            log.exception("telegram alert not sent")

    async def _text(self, alert: dict[str, Any], resolved: bool) -> str:
        name = None
        if self._device_name and alert.get("device_id"):
            try:
                name = await self._device_name(str(alert["device_id"]))
            except Exception:  # noqa: BLE001 - the alert still goes out without the name
                log.exception("device name lookup failed")
        return format_alert(alert, resolved, self._emulated, name)

    async def flush(self) -> None:
        """Sends what is queued now, without the background worker (tests)."""
        await asyncio.sleep(0)
        while not self._outbox.empty():
            await self._deliver_one(*self._outbox.get_nowait())
