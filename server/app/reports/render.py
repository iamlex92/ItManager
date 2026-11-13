# ruff: noqa: E501  -- page markup and Russian text: lines are kept whole so each section reads as one piece.
"""The report as one HTML page: cover, contents, numbered sections, tables, pictures. Nothing outside the page is
loaded (no fonts, scripts or images from the network), so it opens offline and prints the same everywhere."""

from datetime import date, datetime
from html import escape
from typing import Any

from . import labels, svg
from .style import CSS


class Raw(str):
    """Markup that is already safe to put on the page."""


def e(x: Any) -> str:
    return x if isinstance(x, Raw) else escape("" if x is None else str(x))


def fdate(d: date | datetime | None) -> str:
    if not d:
        return ""
    return d.astimezone().strftime("%d.%m.%Y") if isinstance(d, datetime) else d.strftime("%d.%m.%Y")


def fdt(d: datetime | None) -> str:
    return d.astimezone().strftime("%d.%m.%Y %H:%M") if d else ""


def badge(text: str, kind: str = "info") -> Raw:
    return Raw(f'<span class="b {kind}">{escape(text)}</span>')


STATUS = {"ok": "ok", "down": "bad", "off": "off", "na": "off"}


def status_badge(x: dict[str, Any]) -> Raw:
    return badge(x["status_label"], STATUS.get(x["status"], "off")) if x["status"] != "na" else Raw("")


def table(head: list[str], rows: list[list[Any]], num: set[int] | None = None, nowrap: set[int] | None = None) -> str:
    num, nowrap = num or set(), nowrap or set()
    th = "".join(f'<th class="{"n" if i in num else ""}">{escape(h)}</th>' for i, h in enumerate(head))
    body = []
    for r in rows:
        tds = "".join(
            f'<td class="{"n" if i in num else "nw" if i in nowrap else ""}">{e(c)}</td>' for i, c in enumerate(r)
        )
        body.append(f"<tr>{tds}</tr>")
    return f'<table class="t"><thead><tr>{th}</tr></thead><tbody>{"".join(body)}</tbody></table>'


def _plural(n: int, one: str, few: str, many: str) -> str:
    m, h = n % 10, n % 100
    return one if m == 1 and h != 11 else few if 2 <= m <= 4 and not 12 <= h <= 14 else many


def _days(days: int | None) -> str:
    if days is None:
        return ""
    if days < 0:
        return f"просрочено на {-days} дн."
    return "сегодня" if days == 0 else f"через {days} дн."


def _vlan_text(p: dict[str, Any]) -> str:
    return f"{p['vlan']}" if p["mode"] == "доступ" else f"транк: {p['tagged'] or '—'}"


def port_rows(ports: list[dict[str, Any]]) -> list[list[Any]]:
    """One row per port, except that a run of idle ports set up the same way is one row: «Gi1/0/5 … Gi1/0/48»."""
    rows: list[list[Any]] = []
    run: list[dict[str, Any]] = []

    def same(a: dict[str, Any], b: dict[str, Any]) -> bool:
        return all(a[k] == b[k] for k in ("admin_up", "mode", "vlan", "tagged", "poe"))

    def flush() -> None:
        if not run:
            return
        a, z = run[0], run[-1]
        state = badge("выключен", "off") if not a["admin_up"] else badge("свободен", "info")
        name = a["name"] if len(run) == 1 else f"{a['name']} … {z['name']}"
        note = "" if len(run) == 1 else f"{len(run)} портов подряд"
        rows.append([name, state, a["mode"], _vlan_text(a), "", "да" if a["poe"] else "", "", note, "", ""])
        run.clear()

    for p in ports:
        idle = not p["up"] and not p["peer"] and not p["label"] and not p["uplink"]
        if idle:
            if run and not same(run[0], p):
                flush()
            run.append(p)
            continue
        flush()
        if p["up"]:
            state = badge("подключён", "ok")
        else:
            state = badge("выключен", "off") if not p["admin_up"] else badge("свободен", "info")
        speed = f"{p['speed']} Мбит/с" if p["up"] else ""
        rows.append(
            [
                p["name"],
                state,
                p["mode"],
                _vlan_text(p),
                speed,
                "да" if p["poe"] else "",
                "uplink" if p["uplink"] else "",
                p["peer"],
                p["label"],
                p["mac"],
            ]
        )
    flush()
    return rows


class Builder:
    def __init__(self, data: dict[str, Any]) -> None:
        self.d = data
        self.mask = data["options"]["mask"]
        self.n = 0
        self.first = True

    def h2(self, sid: str, title: str) -> str:
        self.n += 1
        cls = ' class="first"' if self.first else ""
        self.first = False
        return f'<h2 id="{sid}"{cls}>{self.n}. {escape(title)}</h2>'

    # ------------------------------------------------------------------ summary
    def summary(self) -> str:
        c = self.d["summary"]["counts"]
        tiles = [
            (c["devices"], "устройств на сети"), (c["online"], "из них в сети"), (c["switches"], "коммутаторов"),
            (c["ports"], "портов коммутаторов"), (c["servers"], "серверов и виртуальных машин"),
            (c["workstations"], "рабочих мест"), (c["racks"], "стоек"), (c["vlans"], "VLAN"), (c["licenses"], "лицензий"),
        ]  # fmt: skip
        out = [self.h2("summary", "Резюме"), '<div class="tiles">']
        out += [f'<div class="tile"><b>{n}</b><span>{escape(t)}</span></div>' for n, t in tiles]
        out.append("</div><h3>Что требует внимания</h3>")
        attn = self.d["summary"]["attention"]
        if not attn:
            out.append('<div class="ok-box">Замечаний нет: всё, что программа может проверить, в порядке.</div>')
        else:
            lis = []
            for a in attn:
                word = "Срочно" if a["level"] == "bad" else "Внимание"
                lis.append(
                    f'<li class="{a["level"]}"><b>{word}.</b> {escape(a["text"])} <a class="small muted" href="#{a["section"]}">→ раздел</a></li>'
                )
            out.append(f'<ul class="attn">{"".join(lis)}</ul>')
        return "".join(out)

    # ------------------------------------------------------------------ equipment
    def equipment(self) -> str:
        d = self.d
        out = [self.h2("equipment", "Оборудование"), "<h3>Сколько каких устройств</h3>"]
        rows = [
            [t["label"], t["count"], t["ok"] or "", t["down"] or "", t["off"] or ""]
            for t in d["types"]
        ]  # fmt: skip
        tot = [sum(t[k] for t in d["types"]) for k in ("count", "ok", "down", "off")]
        rows.append([Raw("<b>Всего</b>"), Raw(f"<b>{tot[0]}</b>"), tot[1], tot[2], tot[3]])
        out.append(table(["Тип", "Всего", "В сети", "Не отвечают", "Не в сети"], rows, num={1, 2, 3, 4}))
        out.append(
            '<p class="small muted">«Не отвечают» — устройство, которое должно быть в сети всегда (сервер, коммутатор). «Не в сети» — выключенный компьютер или устройство на паузе: это не авария.</p>'
        )
        out.append("<h3>Где что стоит</h3>")
        out.append(
            '<p class="small muted">В этой таблице только устройства на сети; патч-панели и розетки (пассивное оборудование) не считаются.</p>'
        )
        out.append(table(["Этаж или место", "Устройств"], [[k, v] for k, v in d["places"]], num={1}))
        if d["devices"]:
            out.append("<h3>Реестр устройств</h3>")
            by: dict[str, list[dict]] = {}
            for x in d["devices"]:
                by.setdefault(x["type"], []).append(x)
            for t in labels.TYPE_ORDER:
                items = by.get(t)
                if not items:
                    continue
                if t == "SOCKET":
                    out.append(
                        f'<p class="small muted">Розеток в учёте: {len(items)}. Подробности по ним есть в Excel-версии отчёта.</p>'
                    )
                    continue
                out.append(f"<h4>{escape(labels.TYPES[t])} · {len(items)}</h4>")
                rows = []
                for x in items:
                    ips = ", ".join(i for i in [x["ip"], *x["extra_ips"]] if i)
                    rows.append([x["name"], " ".join(p for p in [x["vendor"], x["model"]] if p), ips, x["mac"], x["place"],
                                 x["serial"], fdate(x["warranty"]), x["assigned"], status_badge(x)])  # fmt: skip
                out.append(
                    table(
                        [
                            "Имя",
                            "Модель",
                            "IP",
                            "MAC",
                            "Место",
                            "Серийный №",
                            "Гарантия до",
                            "Ответственный",
                            "Состояние",
                        ],
                        rows,
                        nowrap={2, 3, 6},
                    )
                )
        return "".join(out)

    # ------------------------------------------------------------------ network
    def network(self) -> str:
        d = self.d
        out = [self.h2("network", "Сеть")]
        pic = svg.topology(
            [
                {"id": x["id"], "name": x["name"], "type": x["type"], "ip": x["ip"], "status": x["status"]}
                for x in d["devices_all"]
            ],
            d["links"],
        )
        out.append("<h3>Схема сети</h3>")
        if pic:
            out.append(pic)
            out.append(
                '<div class="legend"><span>Зелёная линия — связь работает, красная пунктирная — порт не поднят, серая — состояние неизвестно.</span><span>Рамка: зелёная — в сети, красная — не отвечает, серая — не в сети.</span><span>Синим под названием — сколько устройств подключено к коммутатору.</span></div>'
            )
        else:
            out.append('<p class="muted">Связей между устройствами в учёте нет.</p>')
        if d["links"] and not d["options"]["brief"]:
            out.append("<h3>Связи между устройствами</h3>")
            rows = []
            for ln in d["links"]:
                state = (
                    badge("работает", "ok")
                    if ln["up"]
                    else badge("порт не поднят", "bad")
                    if ln["up"] is False
                    else badge("не известно", "off")
                )
                rows.append(
                    [
                        f"{ln['a']} · {ln['a_port']}",
                        f"{ln['b']} · {ln['b_port']}",
                        ", ".join(ln["via"]),
                        ln["cable"],
                        f"{ln['speed']} Мбит/с" if ln["speed"] else "",
                        state,
                    ]
                )
            out.append(table(["Откуда", "Куда", "Через", "Кабель", "Скорость", "Состояние"], rows))
        out.append("<h3>VLAN</h3>")
        if d["vlans"]:
            unused = sum(1 for v in d["vlans"] if v["unused"])
            vl = d["vlans"] if not d["options"]["brief"] else [v for v in d["vlans"] if not v["unused"]]
            rows = [
                [v["id"], Raw(f'<svg class="chip" viewBox="0 0 12 12" width="12" height="12"><rect width="12" height="12" rx="3" fill="{escape(v["color"])}"/></svg>'), v["name"], v["subnet"], v["access_ports"] or "", v["trunk_ports"] or "", v["description"], badge("не используется", "warn") if v["unused"] else ""]
                for v in vl
            ]  # fmt: skip
            out.append(
                table(
                    ["№", "", "Название", "Подсеть", "Портов (доступ)", "Портов (транк)", "Описание", ""],
                    rows,
                    num={0, 4, 5},
                )
            )
            if unused:
                note = "в таблице только используемые" if d["options"]["brief"] else "они отмечены в таблице"
                out.append(
                    f'<p class="small muted">VLAN, на которые не ссылается ни порт, ни подсеть, ни описание: {unused} ({note}). Их можно убрать в разделе «VLAN и подсети» → «Убрать неиспользуемые».</p>'
                )
        else:
            out.append('<p class="muted">VLAN в учёте нет.</p>')
        if d["subnets"]:
            out.append("<h3>Подсети и заполнение адресов</h3>")
            rows = []
            for s in d["subnets"]:
                kind = "bad" if s["percent"] >= 90 else "warn" if s["percent"] >= 75 else "ok"
                color = {"bad": "#b3261e", "warn": "#e0a100", "ok": "#1b7f3b"}[kind]
                w = min(100, s["percent"]) * 1.3
                bar = Raw(
                    f'<svg class="bar" viewBox="0 0 130 9"><rect width="130" height="9" rx="4" fill="#e3e7ec"/><rect width="{w:.0f}" height="9" rx="4" fill="{color}"/></svg>'
                )
                rows.append(
                    [
                        s["vlan"],
                        s["cidr"],
                        s["name"],
                        s["total"],
                        s["used"],
                        s["reserved"] or "",
                        s["dhcp"] or "",
                        s["free"],
                        bar,
                        f"{s['percent']} %",
                    ]
                )
            out.append(
                table(
                    [
                        "VLAN",
                        "Подсеть",
                        "Название",
                        "Адресов",
                        "Занято",
                        "В резерве",
                        "В пуле DHCP",
                        "Свободно",
                        "",
                        "",
                    ],
                    rows,
                    num={0, 3, 4, 5, 6, 7, 9},
                )
            )
        if d["conflicts"]:
            out.append("<h3>Конфликты адресов</h3>")
            kinds = {
                "duplicate": "Один адрес у нескольких",
                "outside_subnets": "Адрес вне подсетей",
                "network_address": "Адрес сети",
                "broadcast_address": "Широковещательный",
                "reserved_by_other": "Занят чужим резервом",
                "in_pool": "Внутри пула DHCP",
            }
            out.append(
                table(
                    ["Адрес", "Что не так", "Устройства", "Пояснение"],
                    [
                        [c["ip"], kinds.get(c["kind"], c["kind"]), ", ".join(c["devices"]), c["detail"]]
                        for c in d["conflicts"]
                    ],
                )
            )
        if d["ranges"]:
            out.append("<h3>Диапазоны адресов</h3>")
            out.append(
                table(
                    ["Название", "Вид", "С", "По", "Заметка"],
                    [[r["name"], r["kind"], r["start"], r["end"], r["note"]] for r in d["ranges"]],
                    nowrap={2, 3},
                )
            )
        if d["reservations"]:
            out.append("<h3>Зарезервированные адреса</h3>")
            out.append(
                table(
                    ["Адрес", "Кому", "MAC", "Устройство", "Заметка"],
                    [[r["ip"], r["name"], r["mac"], r["device"], r["note"]] for r in d["reservations"]],
                    nowrap={0, 2},
                )
            )
        return "".join(out)

    # ------------------------------------------------------------------ switches
    def switches(self) -> str:
        d = self.d
        out = [self.h2("switches", "Коммутаторы")]
        if not d["switches"]:
            return "".join(out) + '<p class="muted">Коммутаторов в учёте нет.</p>'
        out.append(table(
            ["Коммутатор", "Модель", "IP", "Место", "Портов", "Подключено", "Свободно", "Выключено", "PoE", "Состояние"],
            [[Raw(f'<a href="#sw-{s["id"]}">{escape(s["name"])}</a>'), s["model"], s["ip"], s["place"], s["total"], s["up"], s["down"], s["off"] or "", s["poe"] or "", s["status"]] for s in d["switches"]],
            num={4, 5, 6, 7, 8}, nowrap={0, 2},
        ))  # fmt: skip
        out.append(
            '<p class="small muted">«Подключено» — порт включён и поднят. «Свободно» — порт включён, но кабель не подключён или устройство молчит. «Выключено» — порт выключен администратором.</p>'
        )
        for s in d["switches"]:
            out.append(f'<div class="swblock"><h3 id="sw-{s["id"]}">{escape(s["name"])}</h3>')
            out.append(
                f'<div class="swhead"><span><b>{escape(s["model"] or "модель не указана")}</b></span><span>IP {escape(s["ip"] or "не задан")}</span><span>{escape(s["place"])}</span><span>{s["up"]} из {s["total"]} портов подключено, uplink: {s["uplinks"]}</span></div>'
            )
            rows = port_rows(s["ports"])
            out.append(
                table(
                    ["Порт", "Состояние", "Режим", "VLAN", "Скорость", "PoE", "", "Подключено к", "Подпись", "MAC"],
                    rows,
                    nowrap={0, 4},
                )
            )
            out.append("</div>")
        return "".join(out)

    # ------------------------------------------------------------------ racks
    def racks(self) -> str:
        d = self.d
        out = [self.h2("racks", "Стойки")]
        if not d["racks"]:
            return "".join(out) + '<p class="muted">Стоек в учёте нет.</p>'
        out.append(table(["Стойка", "Место", "Высота", "Занято", "Свободно", "Устройств"],
                         [[r["name"], r["place"], f'{r["units"]} U', f'{r["used"]} U', f'{r["free"]} U', len(r["items"])] for r in d["racks"]], num={2, 3, 4, 5}))  # fmt: skip
        for r in d["racks"]:
            out.append(
                f'<h3>{escape(r["name"])}</h3><div class="racks"><div class="rackbox">{svg.rack(r)}</div><div class="rackside">'
            )
            if r["items"]:
                rows = [
                    [
                        f"{i['u']}" + (f"–{i['u'] + i['h'] - 1}" if i["h"] > 1 else ""),
                        i["name"],
                        labels.TYPES.get(i["type"], ""),
                        i["model"],
                    ]
                    for i in sorted(r["items"], key=lambda i: -i["u"])
                ]
                out.append(table(["Юнит", "Устройство", "Тип", "Модель"], rows, nowrap={0}))
            else:
                out.append('<p class="muted">Стойка пуста.</p>')
            out.append("</div></div>")
            for pp in r["patch"]:
                named = [p for p in pp["ports"] if p["label"] or p["peer"]]
                out.append(
                    f"<h4>Патч-панель {escape(pp['name'])}: подписано {len(named)} из {len(pp['ports'])} портов</h4>"
                )
                if named:
                    out.append(
                        table(
                            ["Порт", "Куда идёт кабель", "Подключено"],
                            [[p["number"], p["label"], p["peer"]] for p in named],
                            num={0},
                        )
                    )
        return "".join(out)

    # ------------------------------------------------------------------ servers and computers
    def servers(self) -> str:
        d = self.d
        out = [self.h2("servers", "Серверы и ПК"), f"<h3>Серверы и виртуальные машины · {len(d['servers'])}</h3>"]
        if d["servers"]:
            rows = [[x["name"], x["type_label"], x["host"], " ".join(p for p in [x["vendor"], x["model"]] if p), ", ".join(i for i in [x["ip"], *x["extra_ips"]] if i), x["place"], x["comment"], fdate(x["warranty"]), status_badge(x)] for x in d["servers"]]  # fmt: skip
            out.append(
                table(
                    ["Имя", "Тип", "Работает на", "Модель", "IP", "Место", "Назначение", "Гарантия до", "Состояние"],
                    rows,
                    nowrap={4, 7},
                )
            )
        else:
            out.append('<p class="muted">Серверов нет.</p>')
        ws = d["workstations"]
        out.append(f"<h3>Рабочие места · {len(ws)}</h3>")
        if ws and not d["options"]["brief"]:
            by: dict[str, list[dict]] = {}
            for x in ws:
                by.setdefault(x["room"] or x["floor"] or "Место не указано", []).append(x)
            for place in sorted(by, key=lambda p: (p.startswith("Место не"), p)):
                items = by[place]
                out.append(f"<h4>{escape(place)} · {len(items)}</h4>")
                out.append(
                    table(
                        ["Имя", "Модель", "IP", "Ответственный", "Серийный №", "Гарантия до", "Состояние"],
                        [
                            [
                                x["name"],
                                " ".join(p for p in [x["vendor"], x["model"]] if p),
                                x["ip"],
                                x["assigned"],
                                x["serial"],
                                fdate(x["warranty"]),
                                status_badge(x),
                            ]
                            for x in items
                        ],
                        nowrap={2, 5},
                    )
                )
        elif ws:
            by2: dict[str, int] = {}
            for x in ws:
                key = x["room"] or x["floor"] or "Место не указано"
                by2[key] = by2.get(key, 0) + 1
            out.append(table(["Место", "Рабочих мест"], [[k, v] for k, v in sorted(by2.items())], num={1}))
        return "".join(out)

    # ------------------------------------------------------------------ licenses
    def licenses(self) -> str:
        d = self.d
        out = [self.h2("licenses", "Программы и лицензии")]
        if not d["licenses"]:
            return "".join(out) + '<p class="muted">Лицензий в учёте нет.</p>'
        keys = d["options"]["with_keys"]
        if keys:
            out.append(
                '<div class="note"><b>В отчёте полные ключи лицензий.</b> Храните и передавайте его как пароль.</div>'
            )
        else:
            out.append(
                '<p class="small muted">Ключи в отчёте скрыты: показаны только последние четыре знака. Полные ключи есть в веб-консоли, раздел «Сроки и лицензии».</p>'
            )
        rows = []
        for x in d["licenses"]:
            seats = f"{x['used']} из {x['seats']}" if x["seats"] is not None else f"{x['used']}"
            rows.append([x["software"], x["vendor"], x["kind"], Raw(f"{escape(seats)}") if not x["over"] else Raw(f'{escape(seats)} {badge("больше, чем куплено", "bad")}'), fdate(x["valid_until"]) or "бессрочная", x["supplier"], x["contract"], Raw(f'<span class="keys">{escape(x["key"])}</span>') if x["has_key"] else ""])  # fmt: skip
        out.append(
            table(
                ["Программа", "Производитель", "Тип", "Мест занято", "Действует до", "Поставщик", "Договор", "Ключ"],
                rows,
                nowrap={4},
            )
        )
        out.append("<h3>На каких компьютерах стоит</h3>")
        inst = [x for x in d["licenses"] if x["devices"]]
        if inst:
            out.append(table(["Программа", "Компьютеры"], [[x["software"], ", ".join(x["devices"])] for x in inst]))
        else:
            out.append('<p class="muted">К компьютерам лицензии не привязаны.</p>')
        return "".join(out)

    # ------------------------------------------------------------------ deadlines
    def deadlines(self) -> str:
        d = self.d
        out = [self.h2("deadlines", "Сроки")]
        if not d["deadlines"]:
            return (
                "".join(out)
                + '<p class="muted">Сроков в учёте нет: гарантии берутся из карточки устройства, лицензии из раздела лицензий.</p>'
            )
        kind = {"expired": "bad", "week": "bad", "month": "warn", "soon": "warn", "ok": "ok"}
        rows = [
            [x["title"], fdate(x["due"]), _days(x["days"]), badge(x["level_label"], kind.get(x["level"], "info"))]
            for x in d["deadlines"]
        ]
        out.append(table(["Что", "Срок", "Осталось", "Состояние"], rows, nowrap={1}))
        return "".join(out)

    # ------------------------------------------------------------------ peripherals
    def peripherals(self) -> str:
        d = self.d
        out = [self.h2("peripherals", "Периферия и склад")]
        if not d["peripherals"]:
            return "".join(out) + '<p class="muted">Периферии в учёте нет.</p>'
        stock = [p for p in d["peripherals"] if not p["device"]]
        out.append(f"<p>Всего {len(d['peripherals'])}, из них на складе {len(stock)}.</p>")
        counts: dict[str, int] = {}
        for p in d["peripherals"]:
            counts[p["kind"]] = counts.get(p["kind"], 0) + 1
        out.append(table(["Вид", "Штук"], [[k, v] for k, v in sorted(counts.items())], num={1}))
        if not d["options"]["brief"]:
            out.append(
                table(
                    ["Вид", "Производитель", "Модель", "Характеристики", "Серийный №", "Инв. №", "Где"],
                    [
                        [
                            p["kind"],
                            p["vendor"],
                            p["model"],
                            p["specs"],
                            p["serial"],
                            p["inventory"],
                            p["device"] or "на складе",
                        ]
                        for p in d["peripherals"]
                    ],
                )
            )
        return "".join(out)

    # ------------------------------------------------------------------ tasks and documents
    def work(self) -> str:
        d = self.d
        out = [self.h2("work", "Задачи и документация"), f"<h3>Открытые задачи · {len(d['tasks'])}</h3>"]
        if d["tasks"]:
            rep = {"daily": "каждый день", "weekly": "каждую неделю", "monthly": "каждый месяц", "yearly": "каждый год"}
            rows = [[t["title"], fdate(t["due"]), Raw(escape(_days(t["days"])) if t["days"] is None or t["days"] >= 0 else badge(_days(t["days"]), "bad")), rep.get(t["repeat"], "") if t["every"] == 1 else (f'каждые {t["every"]}' if t["repeat"] != "none" else ""), t["device"], t["notes"]] for t in d["tasks"]]  # fmt: skip
            out.append(table(["Задача", "Срок", "Осталось", "Повтор", "Устройство", "Заметка"], rows, nowrap={1}))
        else:
            out.append('<p class="muted">Открытых задач нет.</p>')
        out.append(f"<h3>Документы · {len(d['documents'])}</h3>")
        if d["documents"]:
            out.append(
                table(
                    ["Название", "Раздел", "Устройство", "Изменён"],
                    [[x["title"], x["category"], x["device"], fdate(x["updated"])] for x in d["documents"]],
                    nowrap={3},
                )
            )
            if d["options"]["docs_full"]:
                for x in d["documents"]:
                    if x["body"].strip():
                        out.append(f'<h4>{escape(x["title"])}</h4><pre class="doc">{escape(x["body"])}</pre>')
        else:
            out.append('<p class="muted">Документов нет.</p>')
        return "".join(out)

    # ------------------------------------------------------------------ events
    def events(self) -> str:
        d = self.d
        n = d["period_days"]
        out = [
            self.h2("events", "События и изменения"),
            f'<p class="muted">За последние {n} {_plural(n, "день", "дня", "дней")}.</p>',
            f"<h3>События · {len(d['alerts'])}</h3>",
        ]
        sev = {"CRITICAL": "bad", "critical": "bad", "WARNING": "warn", "warning": "warn"}
        if d["alerts"]:
            out.append(
                table(
                    ["Когда", "Устройство", "Важность", "Что случилось", "Состояние"],
                    [
                        [
                            fdt(a["at"]),
                            a["device"],
                            badge(a["severity"], sev.get(a["severity"], "info")),
                            a["message"],
                            badge("устранено", "ok") if a["resolved"] else badge("открыто", "warn"),
                        ]
                        for a in d["alerts"]
                    ],
                    nowrap={0},
                )
            )
        else:
            out.append('<p class="muted">Событий за период нет.</p>')
        out.append(f"<h3>Что делали в программе · {len(d['audit'])}</h3>")
        if d["audit"]:
            out.append(
                table(
                    ["Когда", "Кто", "Действие", "Результат"],
                    [
                        [
                            fdt(a["at"]),
                            a["who"],
                            a["title"],
                            badge("выполнено", "ok") if a["ok"] else badge("ошибка", "bad"),
                        ]
                        for a in d["audit"]
                    ],
                    nowrap={0},
                )
            )
        else:
            out.append('<p class="muted">Действий за период нет.</p>')
        return "".join(out)

    # ------------------------------------------------------------------ access
    def access(self) -> str:
        d = self.d
        out = [self.h2("access", "Учётные записи доступа")]
        if not d["credentials"]:
            return "".join(out) + '<p class="muted">Сохранённых учётных записей нет или раздел скрыт.</p>'
        out.append(
            '<p class="small muted">Только названия и логины. Пароли и ключи хранятся в программе в зашифрованном виде и в отчёт не попадают.</p>'
        )
        kinds = {"ssh": "SSH", "winrm": "WinRM", "snmpv3": "SNMPv3", "snmp": "SNMP"}
        out.append(
            table(
                ["Название", "Вид", "Логин", "Заметка"],
                [[c["name"], kinds.get(c["kind"], c["kind"]), c["username"], c["notes"]] for c in d["credentials"]],
            )
        )
        return "".join(out)

    # ------------------------------------------------------------------ appendix
    def appendix(self) -> str:
        m, o = self.d["meta"], self.d["options"]
        out = [self.h2("appendix", "О самом отчёте")]
        emu = m["hal_mode"] != "real"
        rows = [
            ["Сформирован", fdt(m["generated_at"])], ["Версия сервера", m["version"]],
            ["Кто сформировал", m["operator"] or "не указано"],
            ["Состояние коммутаторов", "настоящие (читаются по SSH и SNMP)" if not emu else "эмуляция: состояние портов примерное"],
            ["Домен и ПК", "настоящие" if m["pcs_real"] else "эмуляция"],
            ["Ключи лицензий", "полные" if o["with_keys"] else "скрыты"],
            ["Адреса и MAC", "сокращены" if o["mask"] else "полные"],
        ]  # fmt: skip
        out.append(table(["Параметр", "Значение"], rows))
        if emu or not m["pcs_real"]:
            out.append(
                '<div class="note"><b>Часть данных получена в режиме эмуляции.</b> Состояние (в сети, порты) отражает не настоящее оборудование. Учётные данные (названия, адреса, лицензии) введены вручную и настоящие.</div>'
            )
        out.append(
            "<h3>Чего в отчёте нет</h3><ul><li>Паролей, ключей SSH и SNMP-строк сообщества.</li><li>Конфигураций коммутаторов: они хранятся в программе отдельно (раздел «Конфигурации»).</li><li>Групповых политик: они читаются из домена по запросу и в программе не хранятся.</li><li>Данных DHCP, DNS и PoE с серверов и коммутаторов: они читаются в момент просмотра, в отчёт не входят.</li></ul>"
        )
        out.append(
            "<h3>Как читать</h3><ul><li><b>В сети</b> — устройство ответило на проверку. <b>Не отвечает</b> — должно быть в сети всегда (сервер, коммутатор), но молчит: это тревога. <b>Не в сети</b> — выключенный компьютер или устройство на паузе: это не авария.</li><li>Срок «через N дн.» считается от даты формирования отчёта.</li></ul>"
        )
        return "".join(out)


def render(data: dict[str, Any]) -> str:
    data = {**data, "devices_all": data.get("devices_all", [])}
    b = Builder(data)
    secs = {"summary": b.summary, "equipment": b.equipment, "network": b.network, "switches": b.switches, "racks": b.racks,
            "servers": b.servers, "licenses": b.licenses, "deadlines": b.deadlines, "peripherals": b.peripherals,
            "work": b.work, "events": b.events, "access": b.access}  # fmt: skip
    wide = {"equipment", "switches", "servers", "network", "events"}
    m = data["meta"]
    body: list[str] = []
    toc: list[str] = []
    titles = {s[0]: (s[1], s[2]) for s in labels.SECTIONS}
    n = 0
    for sid in data["sections"]:
        if sid not in secs:
            continue
        n += 1
        toc.append(
            f'<li><a href="#{sid}">{n}. {escape(titles[sid][0])}</a><small>{escape(titles[sid][1])}</small></li>'
        )
    toc.append(
        f'<li><a href="#appendix">{n + 1}. О самом отчёте</a><small>Откуда данные, чего в отчёте нет, как читать</small></li>'
    )
    for sid in data["sections"]:
        if sid in secs:
            html = secs[sid]()
            body.append(f'<section class="{"wide" if sid in wide else ""}">{html}</section>')
    body.append(f"<section>{b.appendix()}</section>")
    nav = "".join(f'<a href="#{sid}">{escape(titles[sid][0])}</a>' for sid in data["sections"] if sid in secs)
    c = data["summary"]["counts"]
    cover = (
        f'<div class="cover"><h1>Отчёт по IT-инфраструктуре</h1><p class="sub">Состояние на {escape(fdt(m["generated_at"]))}</p>'
        f'<div class="meta"><span>Версия NetAdmin: <b>{escape(m["version"])}</b></span>'
        f"<span>Сформировал: <b>{escape(m['operator'] or 'не указано')}</b></span>"
        f"<span>Устройств: <b>{c['devices']}</b></span><span>Коммутаторов: <b>{c['switches']}</b></span></div>"
        f'<h3>Содержание</h3><ul class="toc">{"".join(toc)}</ul></div>'
    )
    return (
        '<!DOCTYPE html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>Отчёт по инфраструктуре, {escape(fdate(m['generated_at']))}</title><style>{CSS}</style></head><body>"
        f'<div class="topnav">{nav}<a href="#appendix">О отчёте</a><span class="print">Печать и PDF: Ctrl+P</span></div>'
        f'<div class="page">{cover}{"".join(body)}<footer class="end">Сформировано программой NetAdmin {escape(m["version"])}. Данные на {escape(fdt(m["generated_at"]))}.</footer></div></body></html>'
    )
