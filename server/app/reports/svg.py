# ruff: noqa: E501  -- SVG markup: its lines are kept whole so the drawing code stays readable.
"""The two pictures of the report: the network (switches, routers, servers and what hangs on them) and the racks.
Plain SVG with attributes only (no style attributes), so it prints sharply and survives any viewer."""

from html import escape
from typing import Any

from . import labels

INFRA = {"ROUTER", "FIREWALL", "SWITCH", "SERVER", "ACCESS_POINT", "UPS"}
COLORS = {"ok": "#1b7f3b", "down": "#c62828", "off": "#7a8594", "na": "#7a8594"}
FILL = {
    "ROUTER": "#e3f2fd", "FIREWALL": "#fdecea", "SWITCH": "#e8f0fe", "SERVER": "#f3e8ff", "ACCESS_POINT": "#fff8e1",
    "UPS": "#f1f8e9",
}  # fmt: skip
SHORT = {
    "WORKSTATION": "ПК", "PRINTER": "МФУ", "CAMERA": "Камеры", "PHONE": "Телефоны", "VIRTUAL": "ВМ",
    "UNKNOWN": "Прочее", "PATCH_PANEL": "Панели",
}  # fmt: skip
NODE_W, NODE_H, GAP_X, GAP_Y = 158, 58, 22, 70


def _t(
    x: float, y: float, text: str, size: int = 12, weight: str = "400", fill: str = "#1d2733", anchor: str = "middle"
) -> str:
    return f'<text x="{x:.0f}" y="{y:.0f}" font-size="{size}" font-weight="{weight}" fill="{fill}" text-anchor="{anchor}">{escape(text)}</text>'


def _short(text: str, n: int) -> str:
    return text if len(text) <= n else text[: n - 1] + "…"


def topology(devices: list[dict[str, Any]], links: list[dict[str, Any]]) -> str:
    """Tiers from the top: the router or firewall, the core, the access switches, the rest. End devices (computers,
    printers, cameras) are not drawn one by one: each switch shows how many of them hang on it."""
    by_id = {d["id"]: d for d in devices}
    nodes = {i: d for i, d in by_id.items() if d["type"] in INFRA}
    if not nodes:
        return ""
    adj: dict[str, set[str]] = {i: set() for i in nodes}
    attached: dict[str, dict[str, int]] = {i: {} for i in nodes}
    edges = []
    for ln in links:
        a, b = ln["a_id"], ln["b_id"]
        if a in nodes and b in nodes:
            adj[a].add(b)
            adj[b].add(a)
            edges.append(ln)
        else:
            for here, other in ((a, b), (b, a)):
                if here in nodes and other in by_id and by_id[other]["type"] not in ("RACK",):
                    t = by_id[other]["type"]
                    attached[here][t] = attached[here].get(t, 0) + 1
    # Root: a router or a firewall, else the busiest switch.
    roots = [i for i, d in nodes.items() if d["type"] in ("ROUTER", "FIREWALL")] or sorted(
        (i for i, d in nodes.items() if d["type"] == "SWITCH"), key=lambda i: -len(adj[i])
    )[:1]
    tier: dict[str, int] = {}
    frontier = list(roots)
    for r in frontier:
        tier[r] = 0
    while frontier:
        nxt = []
        for i in frontier:
            for j in sorted(adj[i]):
                if j not in tier:
                    tier[j] = tier[i] + 1
                    nxt.append(j)
        frontier = nxt
    last = max(tier.values(), default=0) + 1
    for i in nodes:
        tier.setdefault(i, last)  # not connected to anything drawn: the bottom row
    rows: dict[int, list[str]] = {}
    for i, t in tier.items():
        rows.setdefault(t, []).append(i)
    for t in rows:
        rows[t].sort(key=lambda i: (nodes[i]["type"], nodes[i]["name"].lower()))
    per_row = 8
    # A long tier is broken into several lines so the picture stays readable on a page.
    lines: list[list[str]] = []
    line_tier: list[int] = []
    for t in sorted(rows):
        r = rows[t]
        for k in range(0, len(r), per_row):
            lines.append(r[k : k + per_row])
            line_tier.append(t)
    width = max(len(line) for line in lines) * (NODE_W + GAP_X) + GAP_X
    pos: dict[str, tuple[float, float]] = {}
    for n, line in enumerate(lines):
        total = len(line) * (NODE_W + GAP_X) - GAP_X
        x0 = (width - total) / 2
        for k, i in enumerate(line):
            pos[i] = (x0 + k * (NODE_W + GAP_X), 20 + n * (NODE_H + GAP_Y))
    height = 20 + len(lines) * (NODE_H + GAP_Y)
    out = [
        f'<svg class="topo" viewBox="0 0 {width} {height}" role="img" aria-label="Схема сети" xmlns="http://www.w3.org/2000/svg">'
    ]
    drawn = set()
    for ln in edges:
        a, b = ln["a_id"], ln["b_id"]
        key = (min(a, b), max(a, b))
        n_same = sum(1 for e in edges if (min(e["a_id"], e["b_id"]), max(e["a_id"], e["b_id"])) == key)
        if key in drawn:
            continue
        drawn.add(key)
        (ax, ay), (bx, by) = pos[a], pos[b]
        if ay > by:
            (ax, ay), (bx, by) = (bx, by), (ax, ay)
        x1, y1 = ax + NODE_W / 2, ay + NODE_H
        x2, y2 = bx + NODE_W / 2, by
        if abs(ay - by) < 1:  # the same line: an arc under the nodes
            y1 = ay + NODE_H
            y2 = by + NODE_H
            mid = y1 + 26
            d = f"M{x1:.0f},{y1:.0f} C{x1:.0f},{mid:.0f} {x2:.0f},{mid:.0f} {x2:.0f},{y2:.0f}"
        else:
            my = (y1 + y2) / 2
            d = f"M{x1:.0f},{y1:.0f} C{x1:.0f},{my:.0f} {x2:.0f},{my:.0f} {x2:.0f},{y2:.0f}"
        color = "#9aa5b1" if ln.get("up") is None else ("#1b7f3b" if ln["up"] else "#c62828")
        dash = "" if ln.get("up") is not False else ' stroke-dasharray="5 4"'
        out.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{1.5 + min(n_same, 4) * 0.5}"{dash}/>')
    for i, d in nodes.items():
        x, y = pos[i]
        c = COLORS.get(d["status"], "#7a8594")
        out.append(
            f'<g><rect x="{x:.0f}" y="{y:.0f}" width="{NODE_W}" height="{NODE_H}" rx="7" fill="{FILL.get(d["type"], "#f4f6f8")}" stroke="{c}" stroke-width="2"/>'
        )
        out.append(f'<circle cx="{x + 12:.0f}" cy="{y + 14:.0f}" r="4.5" fill="{c}"/>')
        out.append(_t(x + NODE_W / 2 + 6, y + 18, _short(d["name"], 19), 12, "700"))
        sub = _short(" ".join(p for p in [labels.TYPES.get(d["type"], ""), d["ip"]] if p), 28)
        out.append(_t(x + NODE_W / 2, y + 33, sub, 10, "400", "#4a5766"))
        att = attached[i]
        if att:
            txt = " · ".join(
                f"{labels.TYPES.get(t, t).split()[0]} {n}" for t, n in sorted(att.items(), key=lambda kv: -kv[1])[:3]
            )
            out.append(_t(x + NODE_W / 2, y + 49, _short(txt, 30), 10, "600", "#1d4f91"))
        out.append("</g>")
    out.append("</svg>")
    return "".join(out)


RACK_FILL = {
    "SWITCH": "#cfe0ff", "ROUTER": "#cdeefd", "FIREWALL": "#fcd3cf", "SERVER": "#e2d1fb", "UPS": "#e3efbf",
    "PATCH_PANEL": "#dfe3e8", "ACCESS_POINT": "#fbeeb7", "PRINTER": "#cdf0e3",
}  # fmt: skip


def rack(r: dict[str, Any]) -> str:
    """A rack from the front: units numbered, devices as blocks, free units left empty."""
    u_h, left, w = 17, 30, 270
    units = r["units"]
    height = units * u_h + 12
    out = [
        f'<svg class="rack" viewBox="0 0 {w + 20} {height}" role="img" aria-label="{escape(r["name"])}" xmlns="http://www.w3.org/2000/svg">'
    ]

    def top_of(unit: int, h: int) -> float:
        row = (unit - 1) if r["top_first"] else (units - (unit + h - 1))
        return 6 + row * u_h

    out.append(
        f'<rect x="{left - 2}" y="2" width="{w - left + 6}" height="{height - 4}" rx="3" fill="#fbfcfd" stroke="#59636f" stroke-width="2"/>'
    )
    for u in range(1, units + 1):
        y = top_of(u, 1)
        out.append(
            f'<line x1="{left}" x2="{w}" y1="{y + u_h:.0f}" y2="{y + u_h:.0f}" stroke="#e3e7ec" stroke-width="1"/>'
        )
        if u == 1 or u % 5 == 0 or u == units:
            out.append(_t(left - 6, y + 12, str(u), 9, "400", "#7a8594", "end"))
    for it in r["items"]:
        y = top_of(it["u"], it["h"])
        hh = it["h"] * u_h - 2
        c = COLORS.get(it["status"], "#7a8594")
        out.append(
            f'<rect x="{left + 2}" y="{y + 1:.0f}" width="{w - left - 4}" height="{hh}" rx="2" fill="{RACK_FILL.get(it["type"], "#eceff3")}" stroke="{c}" stroke-width="1.2"/>'
        )
        out.append(_t(left + 8, y + min(hh / 2 + 4, 13), _short(it["name"], 28), 10.5, "700", "#1d2733", "start"))
        if it["h"] > 1 and it["model"]:
            out.append(_t(left + 8, y + 26, _short(it["model"], 34), 9, "400", "#4a5766", "start"))
        if it["h"] == 1 and it["model"]:
            out.append(_t(w - 6, y + 12, _short(it["model"], 18), 9, "400", "#4a5766", "end"))
    out.append("</svg>")
    return "".join(out)
