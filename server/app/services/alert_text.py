"""How an alert reads on the screen."""

import re

# --- how an alert reads on the screen -------------------------------------------------------------------------------
# The stored text stays as it was written (open alerts are found again by its beginning); what people see is Russian.

_SUPPLY = {
    "black": "чёрный тонер", "cyan": "голубой тонер", "magenta": "пурпурный тонер", "yellow": "жёлтый тонер",
    "waste": "ёмкость для отработки", "drum": "фотобарабан", "toner": "тонер",
}  # fmt: skip
_LINK = re.compile(r"^(?P<dev>.+) (?P<port>\S+): link down$")
_HOST = re.compile(r"^(?P<dev>.+) is not responding$")
_LEVEL = re.compile(r"^(?P<dev>.+?): (?P<what>.+) (?P<n>\d+)% (?P<kind>left|free space)$")


def say(message: str) -> str:
    """«SW-1 gi1/0/3: link down» -> «SW-1, порт gi1/0/3: нет линка»; anything unknown is shown as it is."""
    if m := _LINK.match(message):
        return f"{m['dev']}, порт {m['port']}: нет линка"
    if m := _HOST.match(message):
        return f"{m['dev']} не отвечает"
    if m := _LEVEL.match(message):
        what = _SUPPLY.get(m["what"].strip().lower(), m["what"].strip())
        tail = "свободно" if m["kind"] == "free space" else "осталось"
        return f"{m['dev']}: {what}, {tail} {m['n']} %"
    return message
