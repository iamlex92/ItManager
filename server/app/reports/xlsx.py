# ruff: noqa: E501  -- one line per sheet: the columns and the rows stay side by side so a change of one is a change of the other.
"""The same data as an Excel workbook: one sheet per register, with a frozen header row and filters, for sorting and
for pivot tables. Text is always written as text, so a device called «=SUM(...)» stays a name."""

import io
from datetime import date, datetime
from typing import Any

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

from . import labels

HEAD_FILL = PatternFill("solid", fgColor="1D4F91")
HEAD_FONT = Font(bold=True, color="FFFFFF")


def _cell(v: Any) -> Any:
    if isinstance(v, datetime):
        return v.astimezone().replace(tzinfo=None)
    if isinstance(v, bool):
        return "да" if v else "нет"
    if isinstance(v, list):
        return ", ".join(str(x) for x in v)
    return v


def _sheet(wb: Workbook, title: str, head: list[str], rows: list[list[Any]], widths: list[int] | None = None) -> None:
    ws = wb.create_sheet(title[:31])
    ws.append(head)
    for r in rows:
        ws.append([_cell(v) for v in r])
    for c in ws[1]:
        c.fill, c.font = HEAD_FILL, HEAD_FONT
        c.alignment = Alignment(vertical="center", wrap_text=True)
    for row in ws.iter_rows(min_row=2):
        for c in row:
            if isinstance(c.value, str):
                c.data_type = "s"  # never a formula
            elif isinstance(c.value, datetime):
                c.number_format = "DD.MM.YYYY HH:MM"
            elif isinstance(c.value, date):
                c.number_format = "DD.MM.YYYY"
    ws.freeze_panes = "A2"
    if rows:
        ws.auto_filter.ref = f"A1:{get_column_letter(len(head))}{len(rows) + 1}"
    for i, h in enumerate(head, 1):
        w = (widths[i - 1] if widths and i <= len(widths) else None) or min(
            48, max(len(h) + 2, *(len(str(_cell(r[i - 1]) or "")) + 2 for r in rows[:300])) if rows else len(h) + 2
        )
        ws.column_dimensions[get_column_letter(i)].width = w


def build(d: dict[str, Any]) -> bytes:
    wb = Workbook()
    ws = wb.active
    ws.title = "Сводка"
    m, c = d["meta"], d["summary"]["counts"]
    lines: list[tuple[str, Any]] = [
        ("Отчёт по IT-инфраструктуре", ""),
        ("Сформирован", _cell(m["generated_at"])), ("Версия сервера", m["version"]), ("Кто сформировал", m["operator"]),
        ("", ""), ("Устройств на сети", c["devices"]), ("Из них в сети", c["online"]), ("Коммутаторов", c["switches"]),
        ("Портов коммутаторов", c["ports"]), ("Серверов и ВМ", c["servers"]), ("Рабочих мест", c["workstations"]),
        ("Стоек", c["racks"]), ("VLAN", c["vlans"]), ("Лицензий", c["licenses"]), ("", ""),
        ("Что требует внимания", ""),
    ]  # fmt: skip
    for k, v in lines:
        ws.append([k, v])
    for a in d["summary"]["attention"]:
        ws.append([("Срочно" if a["level"] == "bad" else "Внимание"), a["text"]])
    if not d["summary"]["attention"]:
        ws.append(["Замечаний нет", ""])
    ws["A1"].font = Font(bold=True, size=16, color="1D4F91")
    ws["A16"].font = Font(bold=True)
    ws.column_dimensions["A"].width = 30
    ws.column_dimensions["B"].width = 90
    for row in ws.iter_rows():
        for cell in row:
            if isinstance(cell.value, str):
                cell.data_type = "s"

    if d["devices"]:
        _sheet(wb, "Устройства", ["Имя", "Тип", "Производитель", "Модель", "IP", "Другие IP", "MAC", "Этаж", "Помещение", "Стойка", "Юнит", "Серийный №", "Инв. №", "Куплено", "Гарантия до", "Поставщик", "Ответственный", "Состояние", "Последний отклик", "Комментарий"],
               [[x["name"], x["type_label"], x["vendor"], x["model"], x["ip"], x["extra_ips"], x["mac"], x["floor"], x["room"], x["rack"], x["rack_u"], x["serial"], x["inventory"], x["purchase"], x["warranty"], x["supplier"], x["assigned"], x["status_label"], x["last_seen"], x["comment"]] for x in d["devices"]])  # fmt: skip
    ports = [[s["name"], p["name"], "подключён" if p["up"] else "выключен" if not p["admin_up"] else "свободен", p["mode"], p["vlan"], p["tagged"], p["speed"], p["poe"], p["uplink"], p["peer"], p["label"], p["mac"]] for s in d["switches"] for p in s["ports"]]  # fmt: skip
    if ports:
        _sheet(
            wb,
            "Порты",
            [
                "Коммутатор",
                "Порт",
                "Состояние",
                "Режим",
                "VLAN",
                "Транк: VLAN",
                "Скорость, Мбит/с",
                "PoE",
                "Uplink",
                "Подключено к",
                "Подпись",
                "MAC",
            ],
            ports,
        )
    if d["switches"]:
        _sheet(wb, "Коммутаторы", ["Коммутатор", "Модель", "IP", "Место", "Портов", "Подключено", "Свободно", "Выключено", "Uplink", "PoE", "Состояние"],
               [[s["name"], s["model"], s["ip"], s["place"], s["total"], s["up"], s["down"], s["off"], s["uplinks"], s["poe"], s["status"]] for s in d["switches"]])  # fmt: skip
    if d["links"]:
        _sheet(wb, "Связи", ["Откуда", "Порт", "Куда", "Порт", "Через", "Кабель", "Скорость, Мбит/с", "Состояние"],
               [[x["a"], x["a_port"], x["b"], x["b_port"], x["via"], x["cable"], x["speed"], "работает" if x["up"] else "не поднят" if x["up"] is False else "не известно"] for x in d["links"]])  # fmt: skip
    if d["vlans"]:
        _sheet(wb, "VLAN", ["№", "Название", "Подсеть", "Портов (доступ)", "Портов (транк)", "Описание", "Не используется"],
               [[v["id"], v["name"], v["subnet"], v["access_ports"], v["trunk_ports"], v["description"], v["unused"]] for v in d["vlans"]])  # fmt: skip
    if d["subnets"]:
        _sheet(wb, "Подсети", ["VLAN", "Подсеть", "Название", "Адресов", "Занято", "Зарезервировано", "В пуле DHCP", "Свободно", "Заполнение, %"],
               [[s["vlan"], s["cidr"], s["name"], s["total"], s["used"], s["reserved"], s["dhcp"], s["free"], s["percent"]] for s in d["subnets"]])  # fmt: skip
    if d["ranges"]:
        _sheet(
            wb,
            "Диапазоны",
            ["Название", "Вид", "С", "По", "Заметка"],
            [[r["name"], r["kind"], r["start"], r["end"], r["note"]] for r in d["ranges"]],
        )
    if d["reservations"]:
        _sheet(
            wb,
            "Резервы IP",
            ["Адрес", "Кому", "MAC", "Устройство", "Заметка"],
            [[r["ip"], r["name"], r["mac"], r["device"], r["note"]] for r in d["reservations"]],
        )
    if d["racks"]:
        _sheet(wb, "Стойки", ["Стойка", "Юнит с", "Высота, U", "Устройство", "Тип", "Модель"],
               [[r["name"], i["u"], i["h"], i["name"], labels.TYPES.get(i["type"], ""), i["model"]] for r in d["racks"] for i in sorted(r["items"], key=lambda i: -i["u"])])  # fmt: skip
        pp = [
            [r["name"], p["name"], q["number"], q["label"], q["peer"]]
            for r in d["racks"]
            for p in r["patch"]
            for q in p["ports"]
        ]
        if pp:
            _sheet(wb, "Патч-панели", ["Стойка", "Патч-панель", "Порт", "Куда идёт кабель", "Подключено"], pp)
    for key, title in (("servers", "Серверы"), ("workstations", "Рабочие места")):
        if d[key]:
            _sheet(wb, title, ["Имя", "Тип", "Работает на", "Модель", "IP", "Место", "Серийный №", "Гарантия до", "Ответственный", "Назначение", "Состояние"],
                   [[x["name"], x["type_label"], x["host"], " ".join(p for p in [x["vendor"], x["model"]] if p), x["ip"], x["place"], x["serial"], x["warranty"], x["assigned"], x["comment"], x["status_label"]] for x in d[key]])  # fmt: skip
    if d["licenses"]:
        _sheet(wb, "Лицензии", ["Программа", "Производитель", "Тип", "Мест куплено", "Мест занято", "Больше, чем куплено", "Действует до", "Куплена", "Поставщик", "Договор", "Ключ", "Компьютеры", "Заметка"],
               [[x["software"], x["vendor"], x["kind"], x["seats"], x["used"], x["over"], x["valid_until"], x["purchased"], x["supplier"], x["contract"], x["key"], x["devices"], x["notes"]] for x in d["licenses"]])  # fmt: skip
    if d["deadlines"]:
        _sheet(
            wb,
            "Сроки",
            ["Что", "Срок", "Осталось дней", "Состояние"],
            [[x["title"], x["due"], x["days"], x["level_label"]] for x in d["deadlines"]],
        )
    if d["peripherals"]:
        _sheet(wb, "Периферия", ["Вид", "Производитель", "Модель", "Характеристики", "Серийный №", "Инв. №", "Где", "Комментарий"],
               [[p["kind"], p["vendor"], p["model"], p["specs"], p["serial"], p["inventory"], p["device"] or "на складе", p["comment"]] for p in d["peripherals"]])  # fmt: skip
    if d["tasks"]:
        _sheet(wb, "Задачи", ["Задача", "Срок", "Осталось дней", "Повтор", "Каждые", "Устройство", "Заметка"],
               [[t["title"], t["due"], t["days"], t["repeat"], t["every"], t["device"], t["notes"]] for t in d["tasks"]])  # fmt: skip
    if d["documents"]:
        _sheet(
            wb,
            "Документы",
            ["Название", "Раздел", "Устройство", "Изменён", "Текст"],
            [[x["title"], x["category"], x["device"], x["updated"], x["body"]] for x in d["documents"]],
        )
    if d["alerts"]:
        _sheet(
            wb,
            "События",
            ["Когда", "Устройство", "Важность", "Что случилось", "Устранено"],
            [[a["at"], a["device"], a["severity"], a["message"], a["resolved"]] for a in d["alerts"]],
        )
    if d["audit"]:
        _sheet(
            wb,
            "Журнал",
            ["Когда", "Кто", "Действие", "Выполнено"],
            [[a["at"], a["who"], a["title"], a["ok"]] for a in d["audit"]],
        )
    if d["credentials"]:
        _sheet(
            wb,
            "Учётные записи",
            ["Название", "Вид", "Логин", "Заметка"],
            [[x["name"], x["kind"], x["username"], x["notes"]] for x in d["credentials"]],
        )
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()
