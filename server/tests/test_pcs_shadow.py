"""«Подключиться напрямую»: the computer's sessions from qwinsta and the mstsc /shadow command for one of them."""

from app.pcs.shadow import command, parse_qwinsta, shadow_out

ENGLISH = """ SESSIONNAME       USERNAME                 ID  STATE   TYPE        DEVICE
 services                                    0  Disc
>console           ivanova                   1  Active
 rdp-tcp#4         petrov                    3  Active
                   sidorov                   5  Disc
 rdp-tcp                                 65536  Listen
"""

RUSSIAN = """ СЕАНС             ПОЛЬЗОВАТЕЛЬ             ID  СОСТОЯНИЕ  ТИП         УСТРОЙСТВО
 services                                    0  Диск
 console           kharca01                  2  Активно
                   admin                     4  Диск
 rdp-tcp                                 65536  Прослуш
"""

NOBODY = """ SESSIONNAME       USERNAME                 ID  STATE   TYPE        DEVICE
 services                                    0  Disc
 console                                     1  Conn
 rdp-tcp                                 65536  Listen
"""


def test_sessions_are_read_in_english_and_russian():
    rows = parse_qwinsta(ENGLISH)
    assert [(s.id, s.name, s.user, s.active, s.shadowable) for s in rows] == [
        (0, "services", "", False, False),
        (1, "console", "ivanova", True, True),
        (3, "rdp-tcp#4", "petrov", True, True),
        (5, "", "sidorov", False, False),  # disconnected: there is no screen to shadow
        (65536, "rdp-tcp", "", False, False),
    ]
    ru = parse_qwinsta(RUSSIAN)
    assert [(s.id, s.user, s.active) for s in ru if s.user] == [(2, "kharca01", True), (4, "admin", False)]


def test_the_active_session_gets_the_ready_command():
    out = shadow_out("PC-BUH-01", ENGLISH)
    assert out["session_id"] == 1 and out["user"] == "ivanova"
    assert out["command"] == "mstsc /shadow:1 /v:PC-BUH-01 /control /noConsentPrompt"
    assert out["command_view"] == "mstsc /shadow:1 /v:PC-BUH-01 /noConsentPrompt"
    # Every session that can be shadowed has its own command, the active ones first.
    assert list(out["commands"]) == ["1", "3"]
    assert command("10.0.0.5", 7) == "mstsc /shadow:7 /v:10.0.0.5 /control /noConsentPrompt"
    ru = shadow_out("PC-2", RUSSIAN)
    assert ru["session_id"] == 2 and ru["command"].startswith("mstsc /shadow:2 /v:PC-2")


def test_no_logged_on_user_says_so():
    out = shadow_out("PC-3", NOBODY)
    assert out["session_id"] is None and out["command"] is None and "нет сеанса" in out["message"]


def test_shadow_through_the_api_in_the_emulator(client):
    r = client.post("/api/v1/pcs/PC-BUH-01/shadow", json={})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["ok"] and body["simulated"] and body["platform"] == "windows"
    data = body["data"]
    assert data["command"] == f"mstsc /shadow:{data['session_id']} /v:PC-BUH-01 /control /noConsentPrompt"
    # A read: no confirmation, and the journal has it with the command but without the raw table.
    entry = next(
        a for a in client.get("/api/v1/audit", params={"kind": "runbook"}).json() if a["action"] == "pc:shadow"
    )
    assert "mstsc /shadow:" in (entry["stdout"] or "")
    assert client.post("/api/v1/pcs/bad host/shadow", json={}).status_code in (404, 422)
