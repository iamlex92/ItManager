"""Parsers for Cisco IOS and Eltex MES style configuration text.

Tolerant on purpose: lines they do not understand are ignored, so a whole `show running-config` can be pasted.
"""

import re
from dataclasses import dataclass, field

# Longest family names first so "gigabitethernet" is not read as "ethernet".
_FAMILIES = (
    ("tengigabitethernet", "te"),
    ("twentyfivegige", "twe"),
    ("fortygigabitethernet", "fo"),
    ("hundredgige", "hu"),
    ("gigabitethernet", "gi"),
    ("fastethernet", "fa"),
    ("port-channel", "po"),
    ("ethernet", "et"),
    ("loopback", "lo"),
    ("vlan", "vlan"),
)

_SPEEDS = {"10": 10, "100": 100, "1000": 1000, "2500": 2500, "5000": 5000, "10000": 10000, "25000": 25000}


def normalize_if_name(raw: str) -> str:
    """GigabitEthernet1/0/1, "gigabitethernet 1/0/1", Gig 1/0/1 and Gi1/0/1 all become gi1/0/1."""
    s = raw.strip().lower().replace(" ", "")
    m = re.search(r"\d", s)
    if m is None or m.start() == 0:
        return s
    prefix, rest = s[: m.start()], s[m.start() :]
    if len(prefix) >= 2:
        for full, short in _FAMILIES:
            if full.startswith(prefix):
                return short + rest
    return prefix + rest


def parse_vlan_ids(text: str, limit: int = 4094) -> list[int]:
    """"10,20,30-32" -> [10, 20, 30, 31, 32]. Bad pieces are skipped."""
    out: list[int] = []
    for piece in re.split(r"[,\s;]+", text.strip()):
        if not piece:
            continue
        if "-" in piece:
            a, _, b = piece.partition("-")
            if a.isdigit() and b.isdigit() and int(a) <= int(b) <= limit:
                out.extend(range(int(a), int(b) + 1))
        elif piece.isdigit() and 1 <= int(piece) <= limit:
            out.append(int(piece))
    return out


@dataclass
class ParsedInterface:
    name: str
    description: str = ""
    mode: str | None = None  # ACCESS or TRUNK
    access_vlan: int | None = None
    allowed_vlans: list[int] | None = None  # None: not stated, [] : none
    allowed_all: bool = False
    shutdown: bool = False
    speed_mbps: int | None = None


@dataclass
class ParsedConfig:
    hostname: str | None = None
    vlan_names: dict[int, str] = field(default_factory=dict)
    interfaces: list[ParsedInterface] = field(default_factory=list)


def looks_like_running_config(text: str) -> bool:
    return bool(re.search(r"^\s*(interface\s+\S+|hostname\s+\S+)", text, re.MULTILINE | re.IGNORECASE))


def parse_running_config(text: str) -> ParsedConfig:
    cfg = ParsedConfig()
    cur_if: ParsedInterface | None = None
    cur_vlan: int | None = None
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip():
            continue
        t = line.strip()
        if not line[0].isspace():
            if cur_if is not None:
                cfg.interfaces.append(cur_if)
            cur_if, cur_vlan = None, None
            low = t.lower()
            if low.startswith("hostname "):
                cfg.hostname = t.split(None, 1)[1].strip()
            elif low.startswith("interface "):
                cur_if = ParsedInterface(name=t.split(None, 1)[1].strip())
            elif low.startswith("vlan "):
                ids = parse_vlan_ids(t.split(None, 1)[1])
                for i in ids:
                    cfg.vlan_names.setdefault(i, "")
                cur_vlan = ids[0] if len(ids) == 1 else None
            continue
        low = t.lower()
        if cur_vlan is not None and low.startswith("name "):
            cfg.vlan_names[cur_vlan] = t.split(None, 1)[1].strip()
            continue
        i = cur_if
        if i is None:
            continue
        if low.startswith("description "):
            i.description = t.split(None, 1)[1].strip()
        elif low == "shutdown":
            i.shutdown = True
        elif low == "no shutdown":
            i.shutdown = False
        elif low.startswith("switchport mode access"):
            i.mode = "ACCESS"
        elif low.startswith("switchport mode trunk") or low.startswith("switchport mode general"):
            i.mode = "TRUNK"
        elif low.startswith("switchport access vlan "):
            ids = parse_vlan_ids(t.rsplit(None, 1)[1])
            if ids:
                i.access_vlan = ids[0]
                i.mode = i.mode or "ACCESS"
        elif low.startswith("switchport trunk allowed vlan ") or low.startswith("switchport general allowed vlan "):
            _allowed(i, t.split(None, 4)[4] if len(t.split(None, 4)) > 4 else "")
        elif low.startswith("speed "):
            i.speed_mbps = _SPEEDS.get(t.split()[1])
    if cur_if is not None:
        cfg.interfaces.append(cur_if)
    return cfg


def _allowed(i: ParsedInterface, rest: str) -> None:
    """Handles `add`, `remove`, `all`, `none`, `except` and a plain list the way IOS does."""
    words = rest.split(None, 1)
    action = words[0].lower() if words else ""
    spec = words[1] if len(words) > 1 else ""
    i.mode = i.mode or "TRUNK"
    if action == "all" or rest.strip().lower() in ("all", "1-4094"):
        i.allowed_all, i.allowed_vlans = True, None
    elif action == "none":
        i.allowed_all, i.allowed_vlans = False, []
    elif action == "except":
        i.allowed_all, i.allowed_vlans = True, None
    elif action == "add":
        i.allowed_vlans = sorted(set(i.allowed_vlans or []) | set(parse_vlan_ids(spec)))
        i.allowed_all = False
    elif action == "remove":
        i.allowed_vlans = sorted(set(i.allowed_vlans or []) - set(parse_vlan_ids(spec)))
    else:
        i.allowed_vlans, i.allowed_all = sorted(set(parse_vlan_ids(rest))), False


@dataclass
class VlanBriefRow:
    vlan_id: int
    name: str
    ports: list[str]


_BRIEF_HEAD = re.compile(
    r"^(\d{1,4})\s+(.+?)\s{2,}(active|act/unsup|suspended|act/lshut|sus/lshut)\b\s*(.*)$",
    re.IGNORECASE,
)


def looks_like_vlan_brief(text: str) -> bool:
    return bool(re.search(r"^\s*vlan\s+name\s+status", text, re.MULTILINE | re.IGNORECASE))


def parse_vlan_brief(text: str) -> list[VlanBriefRow]:
    """`show vlan brief`: VLAN id, name and the access ports, with continuation lines for long port lists."""
    rows: list[VlanBriefRow] = []
    for line in text.splitlines():
        m = _BRIEF_HEAD.match(line.rstrip())
        if m:
            rows.append(VlanBriefRow(int(m[1]), m[2].strip(), _ports(m[4])))
        elif rows and line.startswith("     ") and line.strip():
            rows[-1].ports.extend(_ports(line))
    return [r for r in rows if r.vlan_id not in range(1002, 1006)]


def _ports(s: str) -> list[str]:
    return [p.strip() for p in s.split(",") if p.strip()]
