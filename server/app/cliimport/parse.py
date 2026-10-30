"""Parsers for Cisco IOS and Eltex MES style configuration text.

Tolerant on purpose: lines they do not understand are ignored, so a whole `show running-config` can be pasted.
"""

import ipaddress
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

# Families that are front-panel ports; vlan, port-channel, loopback, tunnel and the like are not.
PHYSICAL = {"fa", "gi", "te", "twe", "fo", "hu", "et"}
# Default speed of a port by family, Mbit/s.
FAMILY_SPEED = {"fa": 100, "gi": 1000, "et": 1000, "te": 10000, "twe": 25000, "fo": 40000, "hu": 100000}

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


def if_family(name: str) -> str:
    """The letters of a normalized interface name: "gi" for gi1/0/1."""
    m = re.match(r"[a-z-]*", name)
    return m.group(0) if m else ""


def if_numbers(name: str) -> tuple[int, ...]:
    """The numbers of an interface name, for ordering: gi1/0/12 -> (1, 0, 12)."""
    return tuple(int(n) for n in re.findall(r"\d+", name))


def expand_interface_range(spec: str) -> list[str]:
    """`gi1/0/1-4,gi1/0/7` or `GigabitEthernet1/0/1 - 4` -> normalized names gi1/0/1 ... gi1/0/4, gi1/0/7.
    A piece without a family (or a bare number) takes the family (and slot) of the piece before it."""
    out: list[str] = []
    family = prev_base = ""
    for piece in re.split(r",", spec):
        piece = re.sub(r"\s*-\s*", "-", piece.strip())
        if not piece:
            continue
        m = re.match(r"^([A-Za-z-]*)\s*([\d/]*?)(\d+)(?:-(\d+))?$", piece)
        if m is None:
            continue
        letters, base, first, last = m.group(1), m.group(2), int(m.group(3)), m.group(4)
        if letters:
            family = normalize_if_name(letters + base + str(first))
            family = family[: len(family) - len(base + str(first))]
        if not letters and not base:
            base = prev_base
        prev_base = base
        for n in range(first, (int(last) if last else first) + 1):
            out.append(family + base + str(n))
    return out


def parse_vlan_ids(text: str, limit: int = 4094) -> list[int]:
    """ "10,20,30-32" -> [10, 20, 30, 31, 32]. Bad pieces are skipped."""
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
    # `no switchport` or a VRF: a routed port, no VLAN settings.
    routed: bool = False


@dataclass
class ParsedVlan:
    name: str = ""
    # From the VLAN interface's address: 10.10.10.1 255.255.255.0 -> 10.10.10.0/24.
    subnet: str | None = None


@dataclass
class ParsedConfig:
    hostname: str | None = None
    vlan_names: dict[int, str] = field(default_factory=dict)
    vlan_subnets: dict[int, str] = field(default_factory=dict)
    interfaces: list[ParsedInterface] = field(default_factory=list)

    @property
    def ports(self) -> list[ParsedInterface]:
        """Front-panel interfaces, not VLAN interfaces, port-channels or loopbacks."""
        return [i for i in self.interfaces if if_family(normalize_if_name(i.name)) in PHYSICAL]


def looks_like_running_config(text: str) -> bool:
    return bool(re.search(r"^\s*(interface\s+\S+|hostname\s+\S+)", text, re.MULTILINE | re.IGNORECASE))


def _subnet(words: list[str]) -> str | None:
    """`ip address 10.0.0.1 255.255.255.0` or `ip address 10.0.0.1/24` -> 10.0.0.0/24."""
    try:
        if len(words) >= 2 and "/" not in words[0]:
            return str(ipaddress.ip_interface(f"{words[0]}/{words[1]}").network)
        if words and "/" in words[0]:
            return str(ipaddress.ip_interface(words[0]).network)
    except ValueError:
        return None
    return None


def parse_running_config(text: str) -> ParsedConfig:
    cfg = ParsedConfig()
    group: list[ParsedInterface] = []  # the interface block being read; several for `interface range`
    svi: int | None = None  # the VLAN of an `interface vlan N` block
    cur_vlan: int | None = None  # a Cisco `vlan N` block
    in_vlan_db = False  # Eltex `vlan database`
    by_name: dict[str, ParsedInterface] = {}

    def iface(name: str) -> ParsedInterface:
        """One entry per port, whether it is set in its own block, in a range, or both."""
        key = normalize_if_name(name)
        if key not in by_name:
            by_name[key] = ParsedInterface(name=name)
            cfg.interfaces.append(by_name[key])
        return by_name[key]

    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip() or line.strip().startswith(("!", "#")):
            continue
        t = line.strip()
        low = t.lower()
        if not line[0].isspace():
            group, svi, cur_vlan, in_vlan_db = [], None, None, False
            if low.startswith("hostname "):
                cfg.hostname = t.split(None, 1)[1].strip().strip('"')
            elif low.startswith("interface range "):
                group = [iface(name) for name in expand_interface_range(t.split(None, 2)[2])]
            elif low.startswith("interface "):
                name = t.split(None, 1)[1].strip()
                norm = normalize_if_name(name)
                if if_family(norm) == "vlan":
                    ids = parse_vlan_ids(norm[4:])
                    svi = ids[0] if ids else None
                    if svi is not None:
                        cfg.vlan_names.setdefault(svi, "")
                else:
                    group = [iface(name)]
            elif low == "vlan database":
                in_vlan_db = True
            elif low.startswith("vlan "):
                cur_vlan = _vlan_line(cfg, t.split(None, 1)[1])
            continue
        if in_vlan_db:
            if low.startswith("vlan "):
                _vlan_line(cfg, t.split(None, 1)[1])
            continue
        if cur_vlan is not None and low.startswith("name "):
            cfg.vlan_names[cur_vlan] = t.split(None, 1)[1].strip().strip('"')
            continue
        if svi is not None:
            if low.startswith("name "):
                cfg.vlan_names[svi] = t.split(None, 1)[1].strip().strip('"')
            elif low.startswith("description ") and not cfg.vlan_names.get(svi):
                cfg.vlan_names[svi] = t.split(None, 1)[1].strip().strip('"')
            elif low.startswith("ip address ") and svi not in cfg.vlan_subnets:
                subnet = _subnet(t.split()[2:])
                if subnet:
                    cfg.vlan_subnets[svi] = subnet
            continue
        for i in group:
            _interface_line(i, t, low)
    return cfg


def _vlan_line(cfg: ParsedConfig, rest: str) -> int | None:
    """`10,20,30-32` or Eltex `10 name Office`. Returns the VLAN when the line names exactly one."""
    words = rest.split()
    if len(words) >= 3 and words[1].lower() == "name" and words[0].isdigit():
        vid = int(words[0])
        cfg.vlan_names[vid] = rest.split(None, 2)[2].strip().strip('"')
        return vid
    ids = parse_vlan_ids(words[0]) if words else []
    for i in ids:
        cfg.vlan_names.setdefault(i, "")
    return ids[0] if len(ids) == 1 else None


def _interface_line(i: ParsedInterface, t: str, low: str) -> None:
    if low.startswith("description "):
        i.description = t.split(None, 1)[1].strip()
    elif low == "shutdown":
        i.shutdown = True
    elif low == "no shutdown":
        i.shutdown = False
    elif low == "no switchport" or low.startswith(("vrf forwarding", "ip vrf forwarding")):
        i.routed = True
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
