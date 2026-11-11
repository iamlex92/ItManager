"""RDP shadowing («Подключиться напрямую»): the server asks the computer which user sessions it has (qwinsta, over
WinRM) and builds the command the administrator runs on their own Windows computer:

    mstsc /shadow:<session id> /v:<computer> /control /noConsentPrompt

It works when the «Set rules for remote control of Remote Desktop Services user sessions» policy is «Full Control
without user's permission» on the computer. Nothing is connected from the server: the helper's own mstsc does that."""

import re
from dataclasses import asdict, dataclass

# The state column of qwinsta is in the computer's language.
ACTIVE = {"active", "активно", "активен", "активный", "активна"}
DISCONNECTED = {"disc", "disconnected", "диск", "отключен", "отключено", "отключён"}
LISTENING = {"listen", "прослуш", "прослушивание", "ожидание"}

HOST = re.compile(r"^[A-Za-z0-9.\-]{1,253}$")


@dataclass
class Session:
    id: int
    name: str
    user: str
    state: str
    active: bool
    # A user is logged on and the session is not waiting or disconnected: one that can be shadowed.
    shadowable: bool


def parse_qwinsta(raw: str) -> list[Session]:
    """The sessions of qwinsta's table (English or Russian). The first line is the header; each row has the session
    name (empty for a disconnected user), the user (empty for console and services), the numeric id and the state."""
    rows: list[Session] = []
    lines = [ln.rstrip("\r") for ln in raw.splitlines() if ln.strip()]
    for line in lines[1:]:
        marked = line[:1] == ">"
        body = " " + line[1:] if marked else line
        tokens = body.split()
        at = next((i for i, t in enumerate(tokens) if t.isdigit()), None)
        if at is None:
            continue
        before = tokens[:at]
        state = tokens[at + 1] if at + 1 < len(tokens) else ""
        if len(before) >= 2:
            name, user = before[0], before[1]
        elif len(before) == 1:
            # qwinsta aligns the columns: the session name starts at the left edge, the user name further in. A lone
            # word deep in the row is a user with no session name (a disconnected one).
            indent = len(body) - len(body.lstrip())
            name, user = ("", before[0]) if indent >= 8 else (before[0], "")
        else:
            name = user = ""
        sid = int(tokens[at])
        low = state.lower()
        active = low in ACTIVE
        bad = low in DISCONNECTED or low in LISTENING
        rows.append(Session(sid, name, user, state, active, bool(user) and sid > 0 and not bad))
    return rows


def command(host: str, session_id: int, control: bool = True) -> str:
    """The command for the administrator's computer. [control]: false watches without taking the mouse."""
    return f"mstsc /shadow:{session_id} /v:{host}" + (" /control" if control else "") + " /noConsentPrompt"


def shadow_out(host: str, raw: str) -> dict:
    sessions = parse_qwinsta(raw)
    candidates = sorted((s for s in sessions if s.shadowable), key=lambda s: (not s.active, s.id))
    chosen = candidates[0] if candidates else None
    out = {
        "method": "mstsc-shadow",
        "host": host,
        "sessions": [asdict(s) for s in sessions],
        "session_id": chosen.id if chosen else None,
        "user": chosen.user if chosen else None,
        "command": command(host, chosen.id) if chosen else None,
        "command_view": command(host, chosen.id, control=False) if chosen else None,
        "commands": {str(s.id): command(host, s.id) for s in candidates},
        "needs": [
            "Политика «Установить правила удаленного управления для пользовательских сеансов служб удаленных рабочих "
            "столов» = «Полный контроль без разрешения пользователя»",
            "Брандмауэр: разрешён «Теневое подключение к службе удаленных рабочих столов (RPC)» и порт RDP 3389",
            "Ваша учётная запись — администратор этого компьютера",
        ],
        "raw": raw.strip()[:4000],
    }
    if chosen is None:
        out["message"] = "На компьютере нет сеанса вошедшего пользователя: подключаться не к чему."
    return out


def valid_session_id(value: str | int) -> int:
    n = int(value)
    if not 0 < n < 65536:
        raise ValueError("номер сеанса")
    return n
