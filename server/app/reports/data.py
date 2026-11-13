"""Everything the program holds, gathered into one plain structure that the page and the workbook are both made from.

Read only. Nothing here talks to a device or a server: the report is what the inventory says right now, and it says
which sources are real and which are emulated. Passwords never get here; license keys are hidden unless asked for.
"""

from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import selectinload

from .. import FULL_VERSION
from ..api.vlans import unused_vlans
from ..config import Settings
from ..ipam.analyze import Host, IpRange, Reservation, analyze
from ..models import (
    Credential,
    Device,
    DeviceAlert,
    Document,
    Floor,
    IpReservation,
    License,
    LicenseDevice,
    Peripheral,
    ScriptAuditLog,
    Task,
    Vlan,
)
from ..models import (
    IpRange as IpRangeRow,
)
from ..services import lifecycle
from ..services.topology import load_graph, network_links
from ..services.watch import is_watched
from . import labels

DOC_CATEGORIES = {"instruction": "Инструкция", "passport": "Паспорт", "scheme": "Схема", "note": "Заметка"}
PASSIVE = {"SOCKET", "PATCH_PANEL", "RACK"}
INFRA = {"ROUTER", "FIREWALL", "SWITCH", "SERVER", "ACCESS_POINT", "UPS"}


@dataclass
class Options:
    sections: list[str] = field(default_factory=lambda: list(labels.SECTION_IDS))
    period_days: int = 30
    # Full license keys: only with the user's confirmation, and then the journal records it.
    with_keys: bool = False
    # For handing outside: addresses shortened, no MAC, no stored logins.
    mask: bool = False
    # A short version: counts and what needs attention, no registers.
    brief: bool = False
    docs_full: bool = False
    operator: str = ""


def hide_ip(ip: str | None, mask: bool) -> str:
    if not ip:
        return ""
    if not mask:
        return ip
    parts = ip.split(".")
    return ".".join(parts[:2] + ["•", "•"]) if len(parts) == 4 else "•••"


def hide_net(cidr: str | None, mask: bool) -> str:
    """10.10.20.0/24 -> 10.10.•.•/24 when the report goes outside."""
    if not cidr or not mask:
        return cidr or ""
    addr, _, bits = cidr.partition("/")
    return f"{hide_ip(addr, True)}/{bits}" if bits else hide_ip(addr, True)


def key_hint(key: str | None) -> str:
    if not key:
        return ""
    k = key.strip()
    return f"••••{k[-4:]}" if len(k) > 4 else "••••"


def vlan_ranges(ids: list[int]) -> str:
    """[10, 11, 12, 20] -> «10–12, 20»."""
    out: list[str] = []
    xs = sorted(set(ids))
    i = 0
    while i < len(xs):
        j = i
        while j + 1 < len(xs) and xs[j + 1] == xs[j] + 1:
            j += 1
        out.append(str(xs[i]) if i == j else f"{xs[i]}–{xs[j]}")
        i = j + 1
    return ", ".join(out)


def _inside(poly: list[float], x: float, y: float) -> bool:
    pts = list(zip(poly[0::2], poly[1::2], strict=False))
    inside = False
    j = len(pts) - 1
    for i, (xi, yi) in enumerate(pts):
        xj, yj = pts[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / ((yj - yi) or 1e-9) + xi:
            inside = not inside
        j = i
    return inside


def floor_title(f: Floor) -> str:
    base = f.name or f"Этаж {f.floor_number}"
    return f"{f.building_name}, {base}" if f.building_name else base


def _room_of(d: Device, floors: dict[Any, Floor]) -> str:
    f = floors.get(d.floor_id)
    if f is None or d.pos_x is None or d.pos_y is None:
        return ""
    x, y = float(d.pos_x), float(d.pos_y)
    for z in f.zones or []:
        if z.get("is_room", True) and len(z.get("points") or []) >= 6 and _inside(z["points"], x, y):
            return str(z.get("name") or "")
    return ""


def _status(d: Device) -> tuple[str, str]:
    """(code, words): ok, down (an alarm), off (not in the network, but nobody's worried), na (nothing to ping)."""
    if d.device_type in PASSIVE:
        return "na", "—"
    if d.is_online:
        return "ok", "в сети"
    if is_watched(d.device_type, d.metadata_):
        return "down", "не отвечает"
    return "off", "не в сети"


async def collect(session, settings: Settings, opts: Options, now: datetime | None = None) -> dict[str, Any]:
    now = now or datetime.now(UTC)
    today = now.astimezone().date()
    want = set(opts.sections)
    mask = opts.mask

    devices = list(
        (
            await session.scalars(select(Device).where(Device.is_active.is_(True)).options(selectinload(Device.ports)))
        ).all()
    )
    floors = {f.id: f for f in (await session.scalars(select(Floor))).all()}
    by_id = {d.id: d for d in devices}
    graph = await load_graph(session, cached=False)
    links = network_links(graph)
    vlans = list((await session.scalars(select(Vlan).order_by(Vlan.vlan_id))).all())
    lics = list((await session.scalars(select(License).order_by(License.software))).all())
    lic_dev: dict[Any, list[Any]] = {}
    for lid, did in (await session.execute(select(LicenseDevice.license_id, LicenseDevice.device_id))).all():
        lic_dev.setdefault(lid, []).append(did)
    reservations = list((await session.scalars(select(IpReservation).order_by(IpReservation.ip))).all())
    ranges = list((await session.scalars(select(IpRangeRow).order_by(IpRangeRow.start_ip))).all())

    # -------------------------------------------------------------- devices
    placed: list[dict[str, Any]] = []
    for d in sorted(
        devices,
        key=lambda x: (
            labels.TYPE_ORDER.index(x.device_type) if x.device_type in labels.TYPE_ORDER else 99,
            x.name.lower(),
        ),
    ):
        rack = by_id.get(d.rack_id) if d.rack_id else None
        code, words = _status(d)
        f = floors.get(d.floor_id)
        room = _room_of(d, floors)
        place = ", ".join(x for x in [floor_title(f) if f else "", room] if x)
        if rack:
            place = ", ".join(x for x in [place, f"{rack.name}, юнит {d.rack_u}" if d.rack_u else rack.name] if x)
        placed.append({
            "id": str(d.id), "name": d.name, "type": d.device_type,
            "type_label": labels.TYPES.get(d.device_type, "Прочее"),
            "vendor": d.vendor or "", "model": d.model or "",
            "ip": hide_ip(str(d.ip_address) if d.ip_address else None, mask),
            "extra_ips": [hide_ip(str(i), mask) for i in d.extra_ips or []],
            "mac": "" if mask else (str(d.mac_address) if d.mac_address else ""),
            "floor": floor_title(f) if f else "", "room": room, "place": place or "не указано",
            "rack": rack.name if rack else "", "rack_u": d.rack_u, "rack_h": d.rack_h,
            "serial": d.serial_number or "", "inventory": d.inventory_number or "",
            "purchase": d.purchase_date, "warranty": d.warranty_until, "supplier": d.supplier or "",
            "assigned": d.assigned_to or "", "status": code, "status_label": words,
            "last_seen": d.last_seen_at, "comment": "" if mask else (d.comment or ""),
            "host": by_id[d.host_id].name if d.host_id in by_id else "",
            "ports": len(d.ports),
        })  # fmt: skip
    real = [x for x in placed if x["type"] not in PASSIVE]
    out: dict[str, Any] = {
        "options": asdict(opts),
        "meta": {
            "generated_at": now,
            "version": FULL_VERSION,
            "operator": opts.operator,
            "hal_mode": settings.hal_mode.value,
            "pcs_real": settings.pcs_real,
        },  # fmt: skip
        "sections": [s for s in labels.SECTION_IDS if s in want],
    }

    types: dict[str, dict[str, int]] = {}
    for x in placed:
        t = types.setdefault(x["type"], {"count": 0, "ok": 0, "down": 0, "off": 0})
        t["count"] += 1
        if x["status"] in t:
            t[x["status"]] += 1
    out["types"] = [
        {"type": k, "label": labels.TYPES.get(k, k), **v}
        for k, v in sorted(
            types.items(), key=lambda kv: labels.TYPE_ORDER.index(kv[0]) if kv[0] in labels.TYPE_ORDER else 99
        )
    ]
    places: dict[str, int] = {}
    for x in real:
        key = x["floor"] or "Не размещены на плане"
        places[key] = places.get(key, 0) + 1
    out["places"] = sorted(places.items(), key=lambda kv: (kv[0].startswith("Не размещ"), kv[0]))
    out["devices"] = placed if "equipment" in want and not opts.brief else []
    # The picture of the network needs every device, whatever sections are on.
    out["devices_all"] = [
        {"id": x["id"], "name": x["name"], "type": x["type"], "ip": x["ip"], "status": x["status"]} for x in placed
    ]

    # -------------------------------------------------------------- network
    usage, conflicts = analyze(
        [(v.vlan_id, v.name, str(v.subnet_cidr) if v.subnet_cidr else None) for v in vlans],
        [
            Host(str(d.id), d.name, str(ip))
            for d in devices
            for ip in [d.ip_address, *(d.extra_ips or [])]
            if ip is not None
        ],
        [Reservation(str(r.ip), r.name, str(r.device_id) if r.device_id else None) for r in reservations],
        [IpRange(r.name, r.kind, str(r.start_ip), str(r.end_ip)) for r in ranges],
    )
    unused = {v.vlan_id for v in await unused_vlans(session)}
    access_use: dict[int, int] = {}
    trunk_use: dict[int, int] = {}
    for d in devices:
        for p in d.ports:
            if p.access_vlan:
                access_use[p.access_vlan] = access_use.get(p.access_vlan, 0) + 1
            for t in p.tagged_vlans or []:
                trunk_use[t] = trunk_use.get(t, 0) + 1
    out["vlans"] = [
        {"id": v.vlan_id, "name": v.name, "color": v.color_hex,
         "subnet": hide_net(str(v.subnet_cidr) if v.subnet_cidr else "", mask),
         "description": "" if mask else (v.description or ""), "access_ports": access_use.get(v.vlan_id, 0),
         "trunk_ports": trunk_use.get(v.vlan_id, 0), "unused": v.vlan_id in unused}
        for v in vlans
    ]  # fmt: skip
    out["subnets"] = [
        {"vlan": u.vlan_id, "name": u.name, "cidr": hide_net(u.cidr, mask), "total": u.total, "used": u.used,
         "free": u.free, "reserved": u.reserved, "dhcp": u.dhcp,
         "percent": round(u.used / u.total * 100) if u.total else 0}
        for u in usage
    ]  # fmt: skip
    out["conflicts"] = [{**asdict(c), "ip": hide_ip(c.ip, mask), "detail": "" if mask else c.detail} for c in conflicts]
    out["ranges"] = [
        {"name": r.name, "kind": {"dhcp": "DHCP", "static": "Статические", "reserved": "Резерв"}.get(r.kind, r.kind),
         "start": hide_ip(str(r.start_ip), mask), "end": hide_ip(str(r.end_ip), mask),
         "note": "" if mask else (r.note or "")}
        for r in ranges
    ]  # fmt: skip
    out["reservations"] = [
        {"ip": hide_ip(str(r.ip), mask), "name": r.name, "mac": "" if mask else (str(r.mac) if r.mac else ""),
         "device": by_id[r.device_id].name if r.device_id in by_id else "", "note": "" if mask else (r.note or "")}
        for r in reservations
    ]  # fmt: skip
    out["links"] = [
        {"a": graph.devices[ln["a_id"]].name, "a_id": str(ln["a_id"]), "a_port": ln["a_port"],
         "b": graph.devices[ln["b_id"]].name, "b_id": str(ln["b_id"]), "b_port": ln["b_port"], "via": ln["via"],
         "cable": ", ".join(ln["cable_types"]), "up": ln["is_up"], "speed": ln["speed_mbps"]}
        for ln in links
        if ln["a_id"] in graph.devices and ln["b_id"] in graph.devices
    ]  # fmt: skip

    # -------------------------------------------------------------- switches
    peer: dict[tuple[str, str], str] = {}
    for ln in out["links"]:
        via = f" (через {', '.join(ln['via'])})" if ln["via"] else ""
        peer[(ln["a_id"], ln["a_port"])] = f"{ln['b']} · {ln['b_port']}{via}"
        peer[(ln["b_id"], ln["b_port"])] = f"{ln['a']} · {ln['a_port']}{via}"
    switches = []
    for d in devices:
        if d.device_type != "SWITCH":
            continue
        rows = []
        for p in sorted(d.ports, key=lambda x: x.port_number):
            tagged = list(p.tagged_vlans or [])
            rows.append({
                "number": p.port_number, "name": p.port_name, "mode": "транк" if p.mode == "TRUNK" else "доступ",
                "vlan": p.access_vlan, "tagged": "все" if len(tagged) > 40 else vlan_ranges(tagged),
                "admin_up": p.is_admin_up, "up": p.is_oper_up and p.is_admin_up, "speed": p.speed_mbps,
                "poe": p.poe_enabled, "uplink": p.is_uplink, "label": p.label or "",
                "peer": peer.get((str(d.id), p.port_name), ""),
                "mac": "" if mask else (str(p.connected_mac) if p.connected_mac else ""),
            })  # fmt: skip
        used = sum(1 for r in rows if r["up"])
        switches.append({
            "id": str(d.id), "name": d.name, "model": " ".join(x for x in [d.vendor, d.model] if x),
            "ip": hide_ip(str(d.ip_address) if d.ip_address else None, mask),
            "status": _status(d)[1], "place": next((x["place"] for x in placed if x["id"] == str(d.id)), ""),
            "ports": rows, "total": len(rows), "up": used,
            "down": sum(1 for r in rows if r["admin_up"] and not r["up"]),
            "off": sum(1 for r in rows if not r["admin_up"]), "uplinks": sum(1 for r in rows if r["uplink"]),
            "poe": sum(1 for r in rows if r["poe"]),
        })  # fmt: skip
    out["switches"] = switches if "switches" in want else []

    # -------------------------------------------------------------- racks
    racks = []
    for r in devices:
        if r.device_type != "RACK":
            continue
        units = r.rack_units or 42
        members = sorted((d for d in devices if d.rack_id == r.id and d.rack_u), key=lambda d: d.rack_u)
        items = [{"u": d.rack_u, "h": d.rack_h or 1, "name": d.name, "type": d.device_type, "model": d.model or "",
                  "status": _status(d)[0], "id": str(d.id)} for d in members]  # fmt: skip
        patch = []
        for d in members:
            if d.device_type == "PATCH_PANEL":
                patch.append({
                    "name": d.name,
                    "ports": [{"number": p.port_number, "label": p.label or "",
                               "peer": peer.get((str(d.id), p.port_name), "")}
                              for p in sorted(d.ports, key=lambda x: x.port_number)],
                })  # fmt: skip
        taken = sum(i["h"] for i in items)
        racks.append({"id": str(r.id), "name": r.name, "units": units, "used": taken, "free": max(0, units - taken),
                      "top_first": (r.metadata_ or {}).get("rack_numbering") == "top", "items": items, "patch": patch,
                      "place": next((x["place"] for x in placed if x["id"] == str(r.id)), "")})  # fmt: skip
    out["racks"] = racks if "racks" in want else []

    # -------------------------------------------------------------- servers and computers
    out["servers"] = [x for x in placed if x["type"] in ("SERVER", "VIRTUAL")] if "servers" in want else []
    out["workstations"] = [x for x in placed if x["type"] == "WORKSTATION"] if "servers" in want else []

    # -------------------------------------------------------------- licenses
    lic_rows = []
    for lic in lics:
        ids = lic_dev.get(lic.id, [])
        lic_rows.append({
            "software": lic.software, "vendor": lic.vendor or "", "kind": labels.LICENSE_KINDS.get(lic.kind, lic.kind),
            "seats": lic.seats, "used": len(ids), "over": lic.seats is not None and len(ids) > lic.seats,
            "valid_until": lic.valid_until, "purchased": lic.purchased_on, "supplier": lic.supplier or "",
            "contract": lic.contract or "", "notes": lic.notes or "", "has_key": bool(lic.key),
            "key": (lic.key or "") if opts.with_keys else key_hint(lic.key),
            "devices": sorted(by_id[i].name for i in ids if i in by_id),
        })  # fmt: skip
    out["licenses"] = lic_rows if "licenses" in want else []

    # -------------------------------------------------------------- deadlines
    exp = await lifecycle.collect(session, settings, within_days=3650, today=today, include_expired=True)
    out["deadlines"] = [
        {"title": e["title"], "kind": e.get("kind", ""), "due": e["due"], "days": e["days_left"], "level": e["level"],
         "level_label": labels.LEVELS.get(e["level"], e["level"]), "source": e.get("source", "")}
        for e in exp
    ] if "deadlines" in want else []  # fmt: skip

    # -------------------------------------------------------------- peripherals
    per = (await session.scalars(select(Peripheral).order_by(Peripheral.kind, Peripheral.model))).all()
    out["peripherals"] = [
        {"kind": labels.PERIPHERAL_KINDS.get(p.kind, p.kind), "vendor": p.vendor or "", "model": p.model or "",
         "specs": p.specs or "", "serial": p.serial_number or "", "inventory": p.inventory_number or "",
         "device": by_id[p.device_id].name if p.device_id in by_id else "", "comment": p.comment or ""}
        for p in per
    ] if "peripherals" in want else []  # fmt: skip

    # -------------------------------------------------------------- tasks and documents
    tasks = (
        await session.scalars(
            select(Task).where(Task.is_done.is_(False)).order_by(Task.due_date.is_(None), Task.due_date)
        )
    ).all()
    out["tasks"] = [
        {"title": t.title, "due": t.due_date, "days": (t.due_date - today).days if t.due_date else None,
         "repeat": t.repeat, "every": t.repeat_every, "device": by_id[t.device_id].name if t.device_id in by_id else "",
         "notes": t.notes or ""}
        for t in tasks
    ] if "work" in want else []  # fmt: skip
    docs = (await session.scalars(select(Document).order_by(Document.pinned.desc(), Document.title))).all()
    out["documents"] = [
        {"title": d.title, "category": DOC_CATEGORIES.get(d.category, d.category),
         "device": by_id[d.device_id].name if d.device_id in by_id else "", "updated": d.updated_at,
         "body": d.body if opts.docs_full and not mask else ""}
        for d in docs
    ] if "work" in want else []  # fmt: skip

    # -------------------------------------------------------------- events
    since = now - timedelta(days=opts.period_days)
    alerts = (
        await session.scalars(
            select(DeviceAlert)
            .where(DeviceAlert.created_at >= since)
            .order_by(DeviceAlert.created_at.desc())
            .limit(300)
        )
    ).all()
    out["alerts"] = [
        {"at": a.created_at, "device": by_id[a.device_id].name if a.device_id in by_id else "", "severity": a.severity,
         "message": a.message, "resolved": a.is_resolved}
        for a in alerts
    ] if "events" in want else []  # fmt: skip
    from ..api.audit import audit_out

    audit = (
        await session.scalars(
            select(ScriptAuditLog)
            .where(ScriptAuditLog.executed_at >= since)
            .order_by(ScriptAuditLog.executed_at.desc())
            .limit(400)
        )
    ).all()
    out["audit"] = []
    if "events" in want:
        for row in audit:
            a = audit_out(row)
            out["audit"].append(
                {
                    "at": row.executed_at,
                    "who": row.triggered_by,
                    "title": a.title,
                    "kind": a.kind,
                    "ok": row.exit_code == 0,
                }
            )
    out["period_days"] = opts.period_days

    # -------------------------------------------------------------- stored logins (never the secrets)
    creds = (await session.scalars(select(Credential).order_by(Credential.name))).all()
    out["credentials"] = (
        [{"name": c.name, "kind": c.kind, "username": c.username or "", "notes": c.notes or ""} for c in creds]
        if "access" in want and not mask
        else []
    )

    out["summary"] = await _summary(session, out, devices, real, usage, unused, conflicts, exp, lic_rows, today)
    return out


async def _summary(session, out, devices, real, usage, unused, conflicts, exp, lic_rows, today) -> dict[str, Any]:
    counts = {
        "devices": len(real), "online": sum(1 for x in real if x["status"] == "ok"),
        "switches": sum(1 for x in real if x["type"] == "SWITCH"),
        "servers": sum(1 for x in real if x["type"] in ("SERVER", "VIRTUAL")),
        "workstations": sum(1 for x in real if x["type"] == "WORKSTATION"),
        "racks": sum(1 for d in devices if d.device_type == "RACK"),
        "vlans": len(out["vlans"]), "licenses": len(lic_rows),
        "ports": sum(len(d.ports) for d in devices if d.device_type == "SWITCH"),
    }  # fmt: skip
    attention: list[dict[str, str]] = []

    def add(level: str, text: str, section: str) -> None:
        attention.append({"level": level, "text": text, "section": section})

    down = [x for x in real if x["status"] == "down"]
    if down:
        names = ", ".join(x["name"] for x in down[:6]) + (f" и ещё {len(down) - 6}" if len(down) > 6 else "")
        add("bad", f"Не отвечают {len(down)}: {names}", "equipment")
    open_alerts = (
        await session.scalar(select(func.count()).select_from(DeviceAlert).where(DeviceAlert.is_resolved.is_(False)))
        or 0
    )
    if open_alerts:
        add("warn", f"Открытых событий: {open_alerts}", "events")
    exp_now = [e for e in exp if e["level"] == "expired"]
    soon = [e for e in exp if e["level"] in ("week", "month")]
    if exp_now:
        names = "; ".join(e["title"] for e in exp_now[:4]) + ("…" if len(exp_now) > 4 else "")
        add("bad", f"Просрочено: {len(exp_now)} ({names})", "deadlines")
    if soon:
        add("warn", f"Истекает в ближайшие 30 дней: {len(soon)}", "deadlines")
    over = [x for x in lic_rows if x["over"]]
    if over:
        add("bad", f"Лицензий установлено больше, чем куплено: {', '.join(x['software'] for x in over)}", "licenses")
    full = [u for u in usage if u.total and u.used / u.total >= 0.9]
    if full:
        add("warn", f"Подсети заполнены на 90 % и больше: {', '.join(u.cidr for u in full)}", "network")
    if conflicts:
        add("bad", f"Конфликтов адресов: {len(conflicts)}", "network")
    if unused:
        add("warn", f"VLAN, которыми никто не пользуется: {len(unused)}", "network")
    unplaced = [x for x in real if not x["floor"] and not x["rack"] and x["type"] != "VIRTUAL"]
    if unplaced:
        add("warn", f"Не размещены ни на плане, ни в стойке: {len(unplaced)}", "equipment")
    return {"counts": counts, "attention": attention}
