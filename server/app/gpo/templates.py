"""Ready-made settings («Шаблоны»): the Administrative Templates policies an administrator sets most often, as the
registry values gpedit writes for them, so nobody has to look up keys.

A template has three states, like in gpedit: «Включено» writes its values, «Отключено» writes the «off» value
(when the policy has one), «Не задано» removes every value it owns from the GPO. Changing a template also removes
the values it owns that the new choice does not write (an emptied WSUS address, a helper taken off the list), so the
GPO holds exactly what the form shows. Values the template does not own are never touched.
"""

import ipaddress
import re
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from .backend import GpoError

TS = "HKLM\\Software\\Policies\\Microsoft\\Windows NT\\Terminal Services"
RA_HELPERS = f"{TS}\\RAUnsolicit"
FIREWALL = "HKLM\\Software\\Policies\\Microsoft\\WindowsFirewall\\FirewallRules"
WU = "HKLM\\Software\\Policies\\Microsoft\\Windows\\WindowsUpdate"
AU = f"{WU}\\AU"
DESKTOP = "HKCU\\Software\\Policies\\Microsoft\\Windows\\Control Panel\\Desktop"
STORAGE = "HKLM\\Software\\Policies\\Microsoft\\Windows\\RemovableStorageDevices"
STORAGE_DISKS = f"{STORAGE}\\{{53f5630d-b6bf-11d0-94f2-00a0c91efb8b}}"
EXPLORER = "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\Explorer"
SYSTEM = "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System"

STATES = ("enabled", "disabled", "not_configured")

# ("set", key, name, type, value) or ("remove", key, name)
Op = tuple


@dataclass(frozen=True)
class Field:
    id: str
    label: str
    # bool, int, choice, list (one per line), text
    kind: str
    default: Any = None
    help: str = ""
    choices: tuple[tuple[str, str], ...] = ()
    min: int = 0
    max: int = 0


class Reg:
    """A GPO's registry settings, looked up without regard to case."""

    def __init__(self, rows: list[dict]) -> None:
        self.rows = [r for r in rows if (r.get("state") or "Set") == "Set"]

    def get(self, key: str, name: str) -> str | None:
        k, n = key.lower(), name.lower()
        return next(
            (str(r.get("value") or "") for r in self.rows if r["key"].lower() == k and r["name"].lower() == n), None
        )

    def names(self, key: str) -> list[str]:
        return [r["name"] for r in self.rows if r["key"].lower() == key.lower()]


@dataclass(frozen=True)
class Template:
    id: str
    title: str
    # Where gpedit shows it.
    path: str
    help: str
    fields: tuple[Field, ...]
    # Single values the template owns, and keys every value of which it owns (lists).
    owns: tuple[tuple[str, str], ...]
    enable: Callable[[dict], list[Op]]
    disable: Callable[[dict], list[Op]] | None
    # (state, values) from what the GPO holds
    read: Callable[[Reg], tuple[str, dict]]
    lists: tuple[str, ...] = ()
    # Firewall rules and other values whose names it owns under a key shared with others.
    owns_names: tuple[tuple[str, tuple[str, ...]], ...] = field(default_factory=tuple)

    @property
    def scope(self) -> str:
        keys = [k for k, _ in self.owns] + list(self.lists) + [k for k, _ in self.owns_names]
        return "user" if keys and all(k.upper().startswith("HKCU") for k in keys) else "computer"


def _dword(key: str, name: str, value: int | bool) -> Op:
    return ("set", key, name, "DWord", int(value))


def _sz(key: str, name: str, value: str) -> Op:
    return ("set", key, name, "String", value)


def _on(reg: Reg, key: str, name: str, on: str = "1", off: str = "0") -> str:
    v = reg.get(key, name)
    return "enabled" if v == on else "disabled" if v == off else "not_configured"


def _int(v: str | None, default: int) -> int:
    try:
        return int(v or "", 0)
    except ValueError:
        return default


# --- Remote Assistance --------------------------------------------------------------------------------------------

RA_UNITS = (("0", "минут"), ("1", "часов"), ("2", "дней"))

RA_SOLICIT = Template(
    id="ra_solicit",
    title="Запрос удалённой помощи",
    path="Компьютер → Административные шаблоны → Система → Удалённый помощник",
    help="Пользователь может сам пригласить помощника (msra). Без этого не работает и «Предлагать удалённую помощь».",
    fields=(
        Field(
            "control",
            "Разрешить помощникам управлять компьютером",
            "bool",
            True,
            "Выключено — помощник только видит экран.",
        ),
        Field("expiry", "Приглашение действует", "int", 1, min=1, max=99),
        Field("units", "Единицы", "choice", "1", choices=RA_UNITS),
        Field(
            "mailto", "Отправка приглашения", "choice", "1", choices=(("1", "Через mailto"), ("0", "Через Simple MAPI"))
        ),
    ),
    owns=(
        (TS, "fAllowToGetHelp"),
        (TS, "fAllowFullControl"),
        (TS, "MaxTicketExpiry"),
        (TS, "MaxTicketExpiryUnits"),
        (TS, "fUseMailto"),
    ),
    enable=lambda v: [
        _dword(TS, "fAllowToGetHelp", 1),
        _dword(TS, "fAllowFullControl", v["control"]),
        _dword(TS, "MaxTicketExpiry", v["expiry"]),
        _dword(TS, "MaxTicketExpiryUnits", int(v["units"])),
        _dword(TS, "fUseMailto", int(v["mailto"])),
    ],
    disable=lambda v: [_dword(TS, "fAllowToGetHelp", 0)],
    read=lambda r: (
        _on(r, TS, "fAllowToGetHelp"),
        {
            "control": r.get(TS, "fAllowFullControl") != "0",
            "expiry": _int(r.get(TS, "MaxTicketExpiry"), 1),
            "units": r.get(TS, "MaxTicketExpiryUnits") or "1",
            "mailto": r.get(TS, "fUseMailto") or "1",
        },
    ),
)


def _offer(v: dict) -> list[Op]:
    if not v["helpers"]:
        raise GpoError("Добавьте хотя бы одного помощника: ДОМЕН\\пользователь или ДОМЕН\\группа")
    return [
        _dword(TS, "fAllowUnsolicited", 1),
        _dword(TS, "fAllowUnsolicitedFullControl", v["control"]),
        *(_sz(RA_HELPERS, h, h) for h in v["helpers"]),
    ]


RA_OFFER = Template(
    id="ra_offer",
    title="Предлагать удалённую помощь",
    path="Компьютер → Административные шаблоны → Система → Удалённый помощник",
    help="Помощник подключается сам: msra /offerra ИМЯ-ПК. Нужны ещё «Запрос удалённой помощи» и правила "
    "брандмауэра «Удалённый помощник»; чтобы видеть запросы UAC — «Запросы UAC во время удалённой помощи».",
    fields=(
        Field(
            "control",
            "Разрешить помощникам управлять компьютером",
            "bool",
            True,
            "Выключено — помощник только видит экран.",
        ),
        Field(
            "helpers",
            "Помощники",
            "list",
            [],
            "По одному в строке: ДОМЕН\\пользователь или ДОМЕН\\группа, например ECOTEHS\\Domain Admins",
        ),
    ),
    owns=((TS, "fAllowUnsolicited"), (TS, "fAllowUnsolicitedFullControl")),
    lists=(RA_HELPERS,),
    enable=_offer,
    disable=lambda v: [_dword(TS, "fAllowUnsolicited", 0)],
    read=lambda r: (
        _on(r, TS, "fAllowUnsolicited"),
        {
            "control": r.get(TS, "fAllowUnsolicitedFullControl") != "0",
            "helpers": r.names(RA_HELPERS),
        },
    ),
)


# --- UAC prompts in a Remote Assistance session ------------------------------------------------------------------
# Security options («Параметры безопасности» → «Контроль учётных записей») are values under ...\Policies\System;
# Registry.pol sets them the same way, and Group Policy removes them with the GPO like any other policy value.


def _uac(v: dict) -> list[Op]:
    if not v["uia_toggle"] and not v["no_secure_desktop"]:
        raise GpoError("Отметьте хотя бы один параметр")
    ops = []
    if v["uia_toggle"]:
        ops.append(_dword(SYSTEM, "EnableUIADesktopToggle", 1))
    if v["no_secure_desktop"]:
        ops.append(_dword(SYSTEM, "PromptOnSecureDesktop", 0))
    return ops


def _read_uac(r: Reg) -> tuple[str, dict]:
    uia, secure = r.get(SYSTEM, "EnableUIADesktopToggle"), r.get(SYSTEM, "PromptOnSecureDesktop")
    values = {"uia_toggle": uia == "1", "no_secure_desktop": secure == "0"}
    if uia == "1" or secure == "0":
        return "enabled", values
    if uia == "0" or secure == "1":
        return "disabled", {"uia_toggle": True, "no_secure_desktop": False}
    return "not_configured", {"uia_toggle": True, "no_secure_desktop": False}


RA_UAC = Template(
    id="ra_uac",
    title="Запросы UAC во время удалённой помощи",
    path="Компьютер → Конфигурация Windows → Параметры безопасности → Локальные политики → Параметры безопасности",
    help="Без этого галочка «Позволить отвечать на запросы UAC» в msra просит у сотрудника пароль администратора: "
    "окно UAC открывается на безопасном рабочем столе, которого помощник не видит. С политикой помощник видит "
    "запрос и сам вводит учётные данные администратора.",
    fields=(
        Field(
            "uia_toggle",
            "Удалённому помощнику — без безопасного рабочего стола",
            "bool",
            True,
            "«Контроль учётных записей: разрешать UIAccess-приложениям запрашивать повышение прав, не используя "
            "безопасный рабочий стол». Затрагивает только сеансы удалённой помощи — рекомендуется.",
        ),
        Field(
            "no_secure_desktop",
            "Всем запросам — без безопасного рабочего стола",
            "bool",
            False,
            "«Контроль учётных записей: переключение к безопасному рабочему столу при выдаче запроса на повышение "
            "прав» — Отключен. Экран не затемняется ни при каком запросе UAC; слабее защищает от подмены окна.",
        ),
    ),
    owns=((SYSTEM, "EnableUIADesktopToggle"), (SYSTEM, "PromptOnSecureDesktop")),
    enable=_uac,
    disable=lambda v: [_dword(SYSTEM, "EnableUIADesktopToggle", 0), _dword(SYSTEM, "PromptOnSecureDesktop", 1)],
    read=_read_uac,
)


# --- Windows Firewall rules ---------------------------------------------------------------------------------------

# Rules as GPMC stores them in Registry.pol (Windows Firewall with Advanced Security, domain profile):
# (value name, protocol, local port, program, service, rule name).
FW_RULES = {
    "ra": (
        (
            "NetAdmin-RA-DCOM-In",
            6,
            "135",
            "%SystemRoot%\\system32\\svchost.exe",
            "RpcSs",
            "Удалённый помощник (DCOM, входящие)",
        ),
        (
            "NetAdmin-RA-Server-In",
            6,
            "",
            "%SystemRoot%\\system32\\raserver.exe",
            "",
            "Удалённый помощник (RA Server, входящие)",
        ),
        ("NetAdmin-RA-TCP-In", 6, "", "%SystemRoot%\\system32\\msra.exe", "", "Удалённый помощник (TCP, входящие)"),
    ),
    "rdp": (
        ("NetAdmin-RDP-TCP-In", 6, "3389", "", "", "Удалённый рабочий стол (TCP, входящие)"),
        ("NetAdmin-RDP-UDP-In", 17, "3389", "", "", "Удалённый рабочий стол (UDP, входящие)"),
    ),
    "winrm": (("NetAdmin-WinRM-HTTP-In", 6, "5985", "", "", "WinRM (HTTP, входящие)"),),
}
FW_NAMES = tuple(rule[0] for rules in FW_RULES.values() for rule in rules)


def _sources(text: str) -> list[str]:
    """«192.168.82.0/24, 10.0.0.5» -> RA4 values; empty: from anywhere."""
    out = []
    for part in re.split(r"[\s,;]+", text.strip()):
        if not part:
            continue
        if part.lower() == "localsubnet":
            out.append("LocalSubnet")
            continue
        try:
            net = ipaddress.IPv4Network(part, strict=False)
        except ValueError as e:
            raise GpoError(f"Откуда разрешить: {part} — не адрес и не подсеть IPv4") from e
        out.append(str(net.network_address) if net.prefixlen == 32 else f"{net.network_address}/{net.netmask}")
    return out


def _firewall(v: dict) -> list[Op]:
    wanted = [k for k in FW_RULES if v.get(k)]
    if not wanted:
        raise GpoError("Отметьте хотя бы одно правило")
    ra4 = [f"RA4={a}" for a in _sources(v.get("sources") or "")]
    ops = []
    for k in wanted:
        for name, protocol, port, app, svc, title in FW_RULES[k]:
            parts = ["v2.10", "Action=Allow", "Active=TRUE", "Dir=In", f"Protocol={protocol}", "Profile=Domain"]
            parts += [f"LPort={port}"] if port else []
            parts += ra4
            parts += [f"App={app}"] if app else []
            parts += [f"Svc={svc}"] if svc else []
            ops.append(_sz(FIREWALL, name, "|".join([*parts, f"Name={title}"]) + "|"))
    return ops


def _read_firewall(r: Reg) -> tuple[str, dict]:
    have = {n.lower() for n in r.names(FIREWALL)}
    values: dict[str, Any] = {k: any(rule[0].lower() in have for rule in rules) for k, rules in FW_RULES.items()}
    first = next((r.get(FIREWALL, n) for n in FW_NAMES if n.lower() in have), None)
    sources = []
    for a in re.findall(r"\|RA4=([^|]+)", first or ""):
        if "/" in a:
            addr, mask = a.split("/", 1)
            a = str(ipaddress.IPv4Network(f"{addr}/{mask}", strict=False))
        sources.append(a)
    values["sources"] = ", ".join(sources)
    return ("enabled" if first is not None else "not_configured"), values


FIREWALL_IN = Template(
    id="firewall_in",
    title="Брандмауэр: входящие подключения",
    path="Компьютер → Параметры Windows → Параметры безопасности → Брандмауэр Защитника Windows → Правила для "
    "входящих подключений",
    help="Правила для профиля «Домен». Нужны для «Предлагать удалённую помощь», удалённого рабочего стола и WinRM.",
    fields=(
        Field("ra", "Удалённый помощник", "bool", True, "DCOM (порт 135), raserver.exe, msra.exe"),
        Field("rdp", "Удалённый рабочий стол", "bool", False, "TCP и UDP 3389"),
        Field("winrm", "WinRM (управление ПК с этого сервера)", "bool", False, "TCP 5985"),
        Field(
            "sources",
            "Откуда разрешить",
            "text",
            "",
            "Пусто — отовсюду. Через запятую: 192.168.82.0/24, 10.0.0.5 или LocalSubnet.",
        ),
    ),
    owns=(),
    owns_names=((FIREWALL, FW_NAMES),),
    enable=_firewall,
    disable=None,
    read=_read_firewall,
)


# --- Remote Desktop -----------------------------------------------------------------------------------------------

RDP = Template(
    id="rdp",
    title="Разрешить удалённый рабочий стол",
    path="Компьютер → Административные шаблоны → Компоненты Windows → Службы удалённых рабочих столов → Узел "
    "сеансов удалённых рабочих столов → Подключения",
    help="Входить могут администраторы ПК и «Пользователи удалённого рабочего стола». Порт 3389 — шаблон «Брандмауэр».",
    fields=(Field("nla", "Только с проверкой подлинности на уровне сети (NLA)", "bool", True),),
    owns=((TS, "fDenyTSConnections"), (TS, "UserAuthentication")),
    enable=lambda v: [_dword(TS, "fDenyTSConnections", 0), _dword(TS, "UserAuthentication", v["nla"])],
    disable=lambda v: [_dword(TS, "fDenyTSConnections", 1)],
    read=lambda r: (_on(r, TS, "fDenyTSConnections", on="0", off="1"), {"nla": r.get(TS, "UserAuthentication") != "0"}),
)


# --- Windows Update -----------------------------------------------------------------------------------------------

WU_MODES = (
    ("2", "Уведомлять о загрузке и установке"),
    ("3", "Загружать автоматически, уведомлять об установке"),
    ("4", "Загружать и устанавливать по расписанию"),
    ("5", "Разрешить локальному администратору выбирать"),
)
DAYS = (
    ("0", "Каждый день"),
    ("1", "Воскресенье"),
    ("2", "Понедельник"),
    ("3", "Вторник"),
    ("4", "Среда"),
    ("5", "Четверг"),
    ("6", "Пятница"),
    ("7", "Суббота"),
)


def _wu(v: dict) -> list[Op]:
    ops = [
        _dword(AU, "NoAutoUpdate", 0),
        _dword(AU, "AUOptions", int(v["mode"])),
        _dword(AU, "ScheduledInstallDay", int(v["day"])),
        _dword(AU, "ScheduledInstallTime", v["hour"]),
    ]
    wsus = (v.get("wsus") or "").strip().rstrip("/")
    if wsus:
        if not re.match(r"^https?://[A-Za-z0-9.-]+(:\d+)?$", wsus):
            raise GpoError("Сервер WSUS: адрес вида http://wsus01.ecotehs.local:8530")
        ops += [_sz(WU, "WUServer", wsus), _sz(WU, "WUStatusServer", wsus), _dword(AU, "UseWUServer", 1)]
    return ops


WINDOWS_UPDATE = Template(
    id="windows_update",
    title="Автоматическое обновление Windows",
    path="Компьютер → Административные шаблоны → Компоненты Windows → Центр обновления Windows",
    help="«Настройка автоматического обновления» и, если указан, «Указать расположение службы обновлений» (WSUS).",
    fields=(
        Field("mode", "Как обновлять", "choice", "4", choices=WU_MODES),
        Field("day", "День установки", "choice", "0", choices=DAYS),
        Field("hour", "Час установки (0–23)", "int", 3, min=0, max=23),
        Field("wsus", "Сервер WSUS", "text", "", "Пусто — обновления из интернета (Microsoft Update)."),
    ),
    owns=(
        (AU, "NoAutoUpdate"),
        (AU, "AUOptions"),
        (AU, "ScheduledInstallDay"),
        (AU, "ScheduledInstallTime"),
        (AU, "UseWUServer"),
        (WU, "WUServer"),
        (WU, "WUStatusServer"),
    ),
    enable=_wu,
    disable=lambda v: [_dword(AU, "NoAutoUpdate", 1)],
    read=lambda r: (
        _on(r, AU, "NoAutoUpdate", on="0", off="1"),
        {
            "mode": r.get(AU, "AUOptions") or "4",
            "day": r.get(AU, "ScheduledInstallDay") or "0",
            "hour": _int(r.get(AU, "ScheduledInstallTime"), 3),
            "wsus": r.get(WU, "WUServer") or "",
        },
    ),
)


# --- Screen lock (user) -------------------------------------------------------------------------------------------

SCREEN_LOCK = Template(
    id="screen_lock",
    title="Блокировка экрана при бездействии",
    path="Пользователь → Административные шаблоны → Панель управления → Персонализация",
    help="Заставка с паролем через заданное время. Действует на пользователей в подразделениях, где связана политика.",
    fields=(Field("minutes", "Через, минут", "int", 10, min=1, max=600),),
    owns=((DESKTOP, "ScreenSaveActive"), (DESKTOP, "ScreenSaverIsSecure"), (DESKTOP, "ScreenSaveTimeOut")),
    enable=lambda v: [
        _sz(DESKTOP, "ScreenSaveActive", "1"),
        _sz(DESKTOP, "ScreenSaverIsSecure", "1"),
        _sz(DESKTOP, "ScreenSaveTimeOut", str(v["minutes"] * 60)),
    ],
    disable=lambda v: [_sz(DESKTOP, "ScreenSaveActive", "0")],
    read=lambda r: (
        _on(r, DESKTOP, "ScreenSaveActive"),
        {"minutes": max(1, _int(r.get(DESKTOP, "ScreenSaveTimeOut"), 600) // 60)},
    ),
)


# --- Removable storage --------------------------------------------------------------------------------------------


def _read_storage(r: Reg) -> tuple[str, dict]:
    if r.get(STORAGE, "Deny_All") == "1":
        return "enabled", {"mode": "all"}
    if r.get(STORAGE_DISKS, "Deny_Write") == "1":
        return "enabled", {"mode": "write"}
    return ("disabled" if r.get(STORAGE, "Deny_All") == "0" else "not_configured"), {"mode": "all"}


REMOVABLE_STORAGE = Template(
    id="removable_storage",
    title="Запретить флешки и съёмные диски",
    path="Компьютер → Административные шаблоны → Система → Доступ к съёмным запоминающим устройствам",
    help="Действует после перезагрузки или gpupdate. Администраторов ПК тоже касается.",
    fields=(
        Field(
            "mode",
            "Что запретить",
            "choice",
            "all",
            choices=(("all", "Любой доступ ко всем съёмным устройствам"), ("write", "Только запись на съёмные диски")),
        ),
    ),
    owns=((STORAGE, "Deny_All"), (STORAGE_DISKS, "Deny_Write")),
    enable=lambda v: (
        [_dword(STORAGE, "Deny_All", 1)] if v["mode"] == "all" else [_dword(STORAGE_DISKS, "Deny_Write", 1)]
    ),
    disable=lambda v: [_dword(STORAGE, "Deny_All", 0)],
    read=_read_storage,
)


# --- AutoPlay -----------------------------------------------------------------------------------------------------

AUTOPLAY = Template(
    id="autoplay_off",
    title="Отключить автозапуск",
    path="Компьютер → Административные шаблоны → Компоненты Windows → Политики автозапуска",
    help="«Отключить автозапуск» и «Поведение по умолчанию для AutoRun: не выполнять команды» — защита от вирусов "
    "на флешках.",
    fields=(
        Field(
            "drives",
            "На каких дисках",
            "choice",
            "255",
            choices=(("255", "На всех дисках"), ("181", "На CD/DVD и съёмных дисках")),
        ),
    ),
    owns=((EXPLORER, "NoDriveTypeAutoRun"), (EXPLORER, "NoAutorun")),
    enable=lambda v: [_dword(EXPLORER, "NoDriveTypeAutoRun", int(v["drives"])), _dword(EXPLORER, "NoAutorun", 1)],
    disable=None,
    read=lambda r: (
        "enabled" if r.get(EXPLORER, "NoDriveTypeAutoRun") is not None else "not_configured",
        {"drives": "181" if r.get(EXPLORER, "NoDriveTypeAutoRun") == "181" else "255"},
    ),
)


TEMPLATES: dict[str, Template] = {
    t.id: t
    for t in (RA_SOLICIT, RA_OFFER, RA_UAC, FIREWALL_IN, RDP, WINDOWS_UPDATE, SCREEN_LOCK, REMOVABLE_STORAGE, AUTOPLAY)
}


def template(tid: str) -> Template:
    t = TEMPLATES.get(tid)
    if t is None:
        raise GpoError("Такого шаблона нет")
    return t


def coerce(t: Template, raw: dict[str, Any]) -> dict[str, Any]:
    """The form's values as the template's types, defaults for what is missing; GpoError when one does not fit."""
    out: dict[str, Any] = {}
    for f in t.fields:
        v = raw.get(f.id, f.default)
        if f.kind == "bool":
            out[f.id] = v in (True, 1, "1", "true", "True", "on")
        elif f.kind == "int":
            try:
                n = int(str(v).strip())
            except ValueError as e:
                raise GpoError(f"{f.label}: нужно целое число") from e
            if not f.min <= n <= f.max:
                raise GpoError(f"{f.label}: от {f.min} до {f.max}")
            out[f.id] = n
        elif f.kind == "choice":
            if str(v) not in {c for c, _ in f.choices}:
                raise GpoError(f"{f.label}: выберите из списка")
            out[f.id] = str(v)
        elif f.kind == "list":
            items = v.splitlines() if isinstance(v, str) else list(v or [])
            seen: dict[str, str] = {}
            for item in (str(i).strip() for i in items):
                if item and item.lower() not in seen:
                    seen[item.lower()] = item
            if len(seen) > 100:
                raise GpoError(f"{f.label}: не больше 100")
            out[f.id] = list(seen.values())
        else:
            out[f.id] = str(v or "").strip()[:500]
    return out


def owned(t: Template, reg: Reg) -> list[tuple[str, str]]:
    """(key, name) of every value of the template the GPO holds now."""
    out = [(k, n) for k, n in t.owns if reg.get(k, n) is not None]
    out += [(k, n) for k in t.lists for n in reg.names(k)]
    for k, names in t.owns_names:
        wanted = {n.lower() for n in names}
        out += [(k, n) for n in reg.names(k) if n.lower() in wanted]
    return out


def plan(t: Template, state: str, raw: dict[str, Any], rows: list[dict]) -> list[Op]:
    """The changes that bring the GPO to the chosen state and values."""
    if state not in STATES:
        raise GpoError("Состояние: enabled, disabled или not_configured")
    if state == "disabled" and t.disable is None:
        raise GpoError("У этого шаблона нет состояния «Отключено»: выберите «Не задано»")
    reg = Reg(rows)
    values = coerce(t, raw)
    sets = [] if state == "not_configured" else (t.enable if state == "enabled" else t.disable)(values)  # type: ignore[misc]
    written = {(op[1].lower(), op[2].lower()) for op in sets}
    removes = [("remove", k, n) for k, n in owned(t, reg) if (k.lower(), n.lower()) not in written]
    return removes + sets


def describe(t: Template, rows: list[dict] | None) -> dict:
    """The template for the form; with the GPO's settings, its current state and values."""
    out: dict[str, Any] = {
        "id": t.id,
        "title": t.title,
        "path": t.path,
        "help": t.help,
        "scope": t.scope,
        "can_disable": t.disable is not None,
        "fields": [
            {
                "id": f.id,
                "label": f.label,
                "kind": f.kind,
                "default": f.default,
                "help": f.help,
                "choices": [{"value": c, "label": label} for c, label in f.choices],
                "min": f.min if f.kind == "int" else None,
                "max": f.max if f.kind == "int" else None,
            }
            for f in t.fields
        ],
        "state": None,
        "values": None,
    }
    if rows is not None:
        state, values = t.read(Reg(rows))
        out["state"] = state
        out["values"] = {f.id: values.get(f.id, f.default) for f in t.fields}
    return out
