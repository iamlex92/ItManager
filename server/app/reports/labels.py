"""Words the report uses: device types, sections, presets. One place, so the page and the workbook say the same."""

TYPES = {
    "SWITCH": "Коммутатор", "ROUTER": "Маршрутизатор", "FIREWALL": "Межсетевой экран", "ACCESS_POINT": "Точка доступа",
    "SERVER": "Сервер", "VIRTUAL": "Виртуальная машина", "WORKSTATION": "Рабочее место", "PRINTER": "МФУ и принтер",
    "CAMERA": "Камера", "PHONE": "IP-телефон", "UPS": "ИБП", "PATCH_PANEL": "Патч-панель", "SOCKET": "Розетка",
    "RACK": "Стойка", "UNKNOWN": "Прочее",
}  # fmt: skip

# Types that are listed in the order a reader expects: network first, then servers, then the rest.
TYPE_ORDER = list(TYPES)

# id, title, one line of what it holds (shown in the dialog).
SECTIONS = [
    ("summary", "Резюме", "Цифры и то, что требует внимания"),
    ("equipment", "Оборудование", "Счётчики по типам и местам, реестр устройств"),
    ("network", "Сеть", "Схема связей, VLAN, подсети, диапазоны и резервы адресов"),
    ("switches", "Коммутаторы", "Каждый коммутатор с таблицей портов"),
    ("racks", "Стойки", "Схемы стоек и подписи патч-панелей"),
    ("servers", "Серверы и ПК", "Серверы, виртуальные машины, рабочие места"),
    ("licenses", "Программы и лицензии", "Места, сроки, на каких ПК установлено"),
    ("deadlines", "Сроки", "Гарантии, лицензии, сертификаты, прочие сроки"),
    ("peripherals", "Периферия и склад", "Мониторы, клавиатуры и прочее"),
    ("work", "Задачи и документация", "Открытые задачи и список документов"),
    ("events", "События и изменения", "Тревоги и журнал действий за период"),
    ("access", "Учётные записи доступа", "Названия и логины сохранённых учётных записей (без паролей)"),
]  # fmt: skip

SECTION_IDS = [s[0] for s in SECTIONS]

PRESETS = {
    "full": {"title": "Полный", "sections": SECTION_IDS, "mask": False, "brief": False},
    "brief": {
        "title": "Для руководства",
        "sections": ["summary", "equipment", "network", "licenses", "deadlines"],
        "mask": False,
        "brief": True,
    },
    "external": {
        "title": "Для обмена вовне",
        "sections": [s for s in SECTION_IDS if s not in ("access", "events")],
        "mask": True,
        "brief": False,
    },
}

LICENSE_KINDS = {
    "per_device": "на компьютер", "per_user": "на пользователя", "organization": "на организацию",
    "subscription": "подписка", "other": "другое",
}  # fmt: skip
DEADLINE_KINDS = {
    "certificate": "Сертификат", "battery": "Батарея ИБП", "domain": "Домен", "contract": "Договор", "other": "Прочее",
}  # fmt: skip
PERIPHERAL_KINDS = {
    "MONITOR": "Монитор", "KEYBOARD": "Клавиатура", "MOUSE": "Мышь", "DOCK": "Док-станция", "HEADSET": "Гарнитура",
    "WEBCAM": "Веб-камера", "SPEAKERS": "Колонки", "SCANNER": "Сканер", "PRINTER": "Принтер",
    "PROJECTOR": "Проектор", "UPS": "ИБП", "OTHER": "Другое",
}  # fmt: skip
LEVELS = {
    "expired": "просрочено", "week": "менее недели", "month": "менее месяца", "soon": "менее двух месяцев",
    "ok": "в порядке",
}  # fmt: skip
