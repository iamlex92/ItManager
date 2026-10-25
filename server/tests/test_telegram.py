import asyncio
import json
import logging
import time

import httpx
from fastapi.testclient import TestClient

from app.main import create_app
from app.services.realtime import Hub
from app.services.telegram import BotApi, TelegramError, TelegramNotifier, format_alert

from .conftest import AUTH, find, make_settings

BOT_TOKEN = "123456:SECRET-bot-token-value"


def _alert(id="a1", severity="WARNING", resolved=False, message="SW-ACC-01 gi1/0/3: link down", **kw):
    return {
        "type": "alert",
        "alert": {
            "id": id,
            "device_id": "d1",
            "severity": severity,
            "alert_type": "LINK_DOWN",
            "message": message,
            "is_resolved": resolved,
            **kw,
        },
    }


def _notifier(**kw):
    sent: list[str] = []

    async def sender(text: str) -> None:
        sent.append(text)

    clock = kw.pop("clock", time.monotonic)
    settings = make_settings(
        **{"telegram_bot_token": BOT_TOKEN, "telegram_chat_id": "-100500", "telegram_delay_seconds": 0, **kw}
    )
    n = TelegramNotifier(settings, Hub(), sender, clock=clock)
    return n, sent


def test_disabled_without_token_or_chat():
    assert not make_settings().telegram_enabled
    assert not make_settings(telegram_bot_token=BOT_TOKEN).telegram_enabled
    assert make_settings(telegram_bot_token=BOT_TOKEN, telegram_chat_id="-1").telegram_enabled


async def test_open_and_resolved_alerts_are_sent_once():
    n, sent = _notifier()
    n.handle(_alert())
    n.handle(_alert())  # published again, e.g. by a refresh
    await n.flush()
    n.handle(_alert(resolved=True))
    await n.flush()
    assert len(sent) == 2
    assert sent[0].startswith("🟠 Предупреждение · Порт упал\nSW-ACC-01 gi1/0/3: link down")
    assert "Эмуляция" in sent[0]
    assert sent[1].startswith("✅ Восстановлено")


async def test_below_min_severity_and_other_events_are_ignored():
    n, sent = _notifier(telegram_min_severity="CRITICAL")
    n.handle(_alert(severity="WARNING"))
    n.handle({"type": "port_status", "device_id": "d1"})
    n.handle(_alert(id="a2", severity="CRITICAL"))
    await n.flush()
    assert len(sent) == 1 and sent[0].startswith("🔴 Критично")


async def test_alert_resolved_within_delay_is_not_sent():
    n, sent = _notifier(telegram_delay_seconds=30)
    n.handle(_alert())
    await asyncio.sleep(0)
    n.handle(_alert(resolved=True))
    await n.flush()
    assert sent == []


async def test_rate_limit_counts_what_was_dropped():
    now = [1000.0]
    n, sent = _notifier(telegram_max_per_minute=2, clock=lambda: now[0])
    for i in range(5):
        n.handle(_alert(id=f"a{i}"))
    await n.flush()
    assert len(sent) == 2
    now[0] += 61
    n.handle(_alert(id="late"))
    await n.flush()
    assert len(sent) == 3 and "Ещё 3 событий не отправлено" in sent[2]


def test_printer_alert_gets_the_device_name():
    text = format_alert(
        {"severity": "WARNING", "alert_type": "TONER_LOW", "message": "TONER:Black 7% Black", "is_resolved": False},
        resolved=False,
        emulated=False,
        device_name="PRN-BUH-01",
    )
    assert text == "🟠 Предупреждение · Мало тонера\nPRN-BUH-01: TONER:Black 7% Black"


async def test_bot_errors_never_contain_the_token(caplog):
    def fail(request: httpx.Request):
        raise httpx.ConnectError(f"cannot reach {request.url}")

    bot = BotApi(BOT_TOKEN, "-1", transport=httpx.MockTransport(fail))
    caplog.set_level(logging.DEBUG)
    try:
        await bot.send("x")
        raise AssertionError("sent")
    except TelegramError as e:
        assert BOT_TOKEN not in str(e) and e.__cause__ is None
    await bot.aclose()
    assert BOT_TOKEN not in caplog.text


def _app(handler, **kw):
    settings = make_settings(telegram_bot_token=BOT_TOKEN, telegram_chat_id="-100500", telegram_delay_seconds=0, **kw)
    return TestClient(create_app(settings, telegram_transport=httpx.MockTransport(handler)), headers=AUTH)


def test_alerts_reach_the_chat_with_device_names(seeded):
    requests: list[httpx.Request] = []

    def ok(request: httpx.Request):
        requests.append(request)
        return httpx.Response(200, json={"ok": True})

    with _app(ok) as c:
        assert c.get("/api/v1/health").json()["telegram_alerts"] is True
        prn = find(c, "PRN-BUH-01")
        r = c.post(
            "/api/v1/alerts",
            json={"device_id": prn["id"], "severity": "CRITICAL", "alert_type": "TONER_LOW", "message": "Black 2%"},
        )
        assert r.status_code == 201
        for _ in range(100):
            if requests:
                break
            time.sleep(0.02)
        assert requests, "no message sent"
        req = requests[0]
        assert req.url.path == f"/bot{BOT_TOKEN}/sendMessage"
        body = json.loads(req.content)
        assert body["chat_id"] == "-100500"
        assert body["text"].startswith("🔴 Критично · Мало тонера\nPRN-BUH-01: Black 2%")

        assert c.post("/api/v1/notifications/telegram/test").json() == {"sent": True}
        assert "Проверка связи" in json.loads(requests[-1].content)["text"]


def test_test_message_reports_a_bad_token(seeded, caplog):
    with _app(lambda r: httpx.Response(401, json={"ok": False, "description": "Unauthorized"})) as c:
        r = c.post("/api/v1/notifications/telegram/test")
    assert r.status_code == 502
    assert r.json()["detail"] == {
        "code": "telegram_failed",
        "message": "Telegram ответил 401: неверный TELEGRAM_BOT_TOKEN",
    }
    assert BOT_TOKEN not in r.text and BOT_TOKEN not in caplog.text


def test_test_message_when_telegram_is_off(client):
    assert client.get("/api/v1/health").json()["telegram_alerts"] is False
    r = client.post("/api/v1/notifications/telegram/test")
    assert r.status_code == 409 and r.json()["detail"]["code"] == "telegram_disabled"
