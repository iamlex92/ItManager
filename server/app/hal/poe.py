"""PoE: the power budget of a switch and what each port draws.

`show power inline` is read as plain text. Cisco IOS prints «Available / Used / Remaining» per module and a table
with the watts of every port; Eltex MES prints «Nominal Power» and «Consumed Power» and a table of ports with their
state. The parser reads what is there and leaves out what the switch did not say (None), so a model that prints less
still gives a budget or a port list instead of an error.
"""

import re
from dataclasses import dataclass, field

from ..cliimport.parse import PHYSICAL, if_family, normalize_if_name
from . import commands


def is_port_name(token: str) -> bool:
    return if_family(normalize_if_name(token)) in PHYSICAL and bool(re.search(r"\d", token))


# What the oper column says when power is flowing, and when something is wrong with the powered device.
ON = {"on", "delivering", "powering"}
FAULT = {"faulty", "fault", "test-fail", "power-deny", "denied", "overload", "short", "err-disable", "error"}


@dataclass(frozen=True)
class PoePort:
    name: str
    admin: str  # auto, static, off ...
    state: str  # on, off, fault
    power_w: float | None = None
    device: str | None = None
    max_w: float | None = None


@dataclass(frozen=True)
class PoeReport:
    budget_w: float | None
    used_w: float | None
    ports: tuple[PoePort, ...] = field(default_factory=tuple)

    @property
    def percent(self) -> int | None:
        if not self.budget_w or self.used_w is None:
            return None
        return round(self.used_w / self.budget_w * 100)


def level_of(percent: int | None) -> str:
    """ok below 75 %, warn from 75 %, critical from 90 %; unknown without a budget."""
    if percent is None:
        return "unknown"
    return "critical" if percent >= 90 else "warn" if percent >= 75 else "ok"


def _num(text: str) -> float | None:
    try:
        return float(text.replace(",", "."))
    except ValueError:
        return None


def _state(word: str) -> str:
    w = word.lower().strip("()")
    return "on" if w in ON else "fault" if w in FAULT else "off"


def _cisco_budget(text: str) -> tuple[float | None, float | None]:
    # Older IOS: «Available:370.0(w)  Used:123.4(w)  Remaining:246.6(w)».
    if m := re.search(r"Available:\s*([\d.]+)\s*\(w\)\s+Used:\s*([\d.]+)\s*\(w\)", text, re.IGNORECASE):
        return float(m.group(1)), float(m.group(2))
    # Newer IOS: a table «Module  Available  Used  Remaining» with one row per module (or switch of a stack).
    avail = used = 0.0
    found = False
    in_table = False
    for line in text.splitlines():
        if re.match(r"\s*(module|switch)\s+available\s+used", line, re.IGNORECASE):
            in_table = True
            continue
        if in_table:
            if m := re.match(r"\s*\d+\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s*$", line):
                avail += float(m.group(1))
                used += float(m.group(2))
                found = True
            elif found and not re.match(r"[\s\-]*$", line):
                break
    return (avail, used) if found else (None, None)


def _cisco_ports(text: str) -> list[PoePort]:
    out = []
    for line in text.splitlines():
        words = line.split()
        if len(words) < 4 or not is_port_name(words[0]):
            continue
        power = _num(words[3])
        if power is None:
            continue
        # Interface Admin Oper Power Device... Class Max: the device name may hold spaces.
        tail = words[4:]
        max_w = _num(tail[-1]) if tail and _num(tail[-1]) is not None and len(tail) >= 3 else None
        if max_w is not None:
            tail = tail[:-1]
        if tail and re.fullmatch(r"\d|n/a", tail[-1], re.IGNORECASE):
            tail = tail[:-1]
        device = " ".join(tail).strip()
        out.append(
            PoePort(
                name=normalize_if_name(words[0]),
                admin=words[1].lower(),
                state=_state(words[2]),
                power_w=power,
                device=None if not device or device.lower() == "n/a" else device,
                max_w=max_w,
            )
        )
    return out


def _eltex_budget(text: str) -> tuple[float | None, float | None]:
    nominal = re.search(r"Nominal Power:\s*([\d.]+)", text, re.IGNORECASE)
    used = re.search(r"Consumed Power:\s*([\d.]+)", text, re.IGNORECASE)
    return (float(nominal.group(1)) if nominal else None, float(used.group(1)) if used else None)


_ELTEX_ADMIN = {"auto", "static", "never", "disable", "disabled"}


def _eltex_ports(text: str) -> list[PoePort]:
    out = []
    for line in text.splitlines():
        words = line.split()
        if len(words) < 3 or not is_port_name(words[0]):
            continue
        rest = [w.lower() for w in words[1:]]
        admin = next((w for w in rest if w in _ELTEX_ADMIN), "auto")
        states = [w for w in rest if w in ON | FAULT | {"off", "searching"}]
        # «Powered Device» is free text before the state columns: keep what is not a column word.
        power = next((n for n in (_num(w) for w in rest) if n is not None), None)
        device_words = [
            w
            for w in words[1:]
            if w.lower() not in _ELTEX_ADMIN | ON | FAULT | {"off", "searching", "low", "high", "critical"}
            and _num(w) is None
            and not w.lower().startswith("class")
        ]
        out.append(
            PoePort(
                name=normalize_if_name(words[0]),
                admin=admin,
                state=_state(states[-1]) if states else "off",
                power_w=power,
                device=" ".join(device_words) or None,
            )
        )
    return out


def parse_report(vendor: str, text: str) -> PoeReport:
    if vendor.upper() == commands.CISCO:
        budget, used = _cisco_budget(text)
        ports = _cisco_ports(text)
    else:
        budget, used = _eltex_budget(text)
        ports = _eltex_ports(text)
    if used is None and ports and any(p.power_w is not None for p in ports):
        used = round(sum(p.power_w or 0 for p in ports), 1)
    return PoeReport(budget, used, tuple(ports))
