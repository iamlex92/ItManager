"""Group Policy: list, scope, report and edit GPOs, through the GroupPolicy module on one Windows host.

Real mode (PC_MODE / RUNBOOK_MODE real) runs scripts.py over WinRM on GPO_HOST (or the domain controller of
AD_LDAP_URL) with the RUNBOOK_CREDENTIAL login; changes also need ALLOW_RUNBOOK_WRITES. The emulator keeps a
few sample GPOs in memory and says so («simulated»). Everything from the API is checked here before it reaches
a script.
"""

import asyncio
import copy
import hashlib
import html
import re
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlparse

from ..config import Settings
from ..pcs.backend import as_list, parse_output
from ..runbooks.real import Login, Shell, winrm_shell
from . import scripts

GUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
DN = re.compile(r"^((OU|CN)=[^,=\\]{1,64},)*DC=[A-Za-z0-9-]{1,63}(,DC=[A-Za-z0-9-]{1,63})*$", re.IGNORECASE)
KEY = re.compile(r"^(HKLM|HKCU)(\\[^\\\x00-\x1f]{1,255}){1,32}$", re.IGNORECASE)
VALUE_NAME = re.compile(r"^[^\x00-\x1f]{1,255}$")
TYPES = ("String", "ExpandString", "DWord", "QWord", "MultiString")
STATUSES = ("AllSettingsEnabled", "UserSettingsDisabled", "ComputerSettingsDisabled", "AllSettingsDisabled")


class GpoError(Exception):
    """Something to show the administrator as it is: a refused value, no login, the host failed."""


def check_id(gpo_id: str) -> str:
    g = gpo_id.strip().strip("{}").lower()
    if not GUID.match(g):
        raise GpoError("Неверный идентификатор групповой политики")
    return g


def check_target(target: str) -> str:
    t = target.strip()
    if not DN.match(t):
        raise GpoError("Куда связать: подразделение (OU=...,DC=...) или домен (DC=...,DC=...)")
    return t


def check_status(status: str) -> str:
    if status not in STATUSES:
        raise GpoError("Состояние: " + ", ".join(STATUSES))
    return status


def check_name(name: str) -> str:
    n = name.strip()
    if not n or len(n) > 200 or any(ord(c) < 32 for c in n):
        raise GpoError("Имя политики: от 1 до 200 символов")
    return n


def check_registry(key: str, name: str, kind: str, value: Any) -> tuple[str, str, str, Any]:
    k = key.strip().replace("/", "\\")
    k = re.sub(r"^HKEY_LOCAL_MACHINE", "HKLM", k, flags=re.IGNORECASE)
    k = re.sub(r"^HKEY_CURRENT_USER", "HKCU", k, flags=re.IGNORECASE)
    if not KEY.match(k):
        raise GpoError("Раздел реестра: HKLM\\... или HKCU\\..., например HKLM\\Software\\Policies\\Microsoft\\Windows")
    k = k[:4].upper() + k[4:]
    n = name.strip()
    if not VALUE_NAME.match(n):
        raise GpoError("Укажите имя параметра")
    if kind not in TYPES:
        raise GpoError("Тип: " + ", ".join(TYPES))
    if kind in ("DWord", "QWord"):
        try:
            number = int(str(value).strip(), 0)
        except ValueError as e:
            raise GpoError("Для DWord и QWord нужно целое число (можно 0x...)") from e
        if not 0 <= number < (2**32 if kind == "DWord" else 2**64):
            raise GpoError("Число вне диапазона типа")
        return k, n, kind, number
    text = "" if value is None else str(value)
    if len(text) > 4000:
        raise GpoError("Значение длиннее 4000 символов")
    return k, n, kind, text


def gpo_host(settings: Settings) -> str:
    """GPO_HOST, or the domain controller named in AD_LDAP_URL."""
    if settings.gpo_host.strip():
        return settings.gpo_host.strip()
    if settings.ad_ldap_url:
        return urlparse(settings.ad_ldap_url).hostname or ""
    return ""


def _links(d: Any) -> list[dict]:
    return [
        {
            "target": link.get("target"),
            "target_name": link.get("target_name") or link.get("target"),
            "enabled": bool(link.get("enabled")),
            "enforced": bool(link.get("enforced")),
            "order": int(link.get("order") or 1),
        }
        for link in as_list(d)
    ]


def _gpo(d: dict) -> dict:
    out = {k: d.get(k) for k in ("id", "name", "status", "description", "owner", "created", "modified", "wmi_filter")}
    out["links"] = _links(d.get("links"))
    return out


@dataclass
class RealGpo:
    """Runs the GroupPolicy cmdlets on the GPO host over WinRM; pywinrm blocks, so every call goes to a thread."""

    settings: Settings
    shell_factory: Callable[[Login], Shell] | None = None
    simulated: bool = False

    def __post_init__(self) -> None:
        self.shell_factory = self.shell_factory or (lambda login: winrm_shell(self.settings, login))

    @property
    def writes_enabled(self) -> bool:
        return self.settings.allow_runbook_writes

    @property
    def host(self) -> str:
        return gpo_host(self.settings)

    async def _run(self, script: str, login: Login | None) -> Any:
        if login is None:
            raise GpoError("Не выбрана учётная запись домена: «Настройки сервера» → «Учётная запись домена».")
        host = self.host
        if not host:
            raise GpoError(
                "Не задан сервер групповых политик: «Настройки сервера» → «Сервер групповых политик» "
                "или «Контроллер домена»."
            )
        assert self.shell_factory is not None
        code, out, err = await asyncio.to_thread(self.shell_factory(login), host, script)
        if code != 0 and not out.strip():
            raise GpoError((err.strip() or f"{host}: код {code}")[:2000])
        try:
            return parse_output(out)
        except Exception as e:
            raise GpoError(str(e)) from e

    async def gpos(self, login: Login | None) -> list[dict]:
        data = await self._run(scripts.LIST, login) or {}
        return [_gpo(g) for g in as_list(data.get("gpos"))]

    async def ous(self, login: Login | None) -> list[dict]:
        data = await self._run(scripts.OUS, login) or {}
        return [{"dn": o.get("dn"), "name": o.get("name")} for o in as_list(data.get("ous"))]

    async def detail(self, gpo_id: str, login: Login | None) -> dict:
        data = await self._run(scripts.detail(check_id(gpo_id)), login) or {}
        return {
            "gpo": _gpo(data.get("gpo") or {}),
            "registry": [
                {k: r.get(k) for k in ("key", "name", "type", "value", "state")} for r in as_list(data.get("registry"))
            ],
            "security_filter": [
                {"name": f.get("name"), "type": f.get("type")} for f in as_list(data.get("security_filter"))
            ],
        }

    async def report(self, gpo_id: str, login: Login | None) -> str:
        data = await self._run(scripts.report(check_id(gpo_id)), login) or {}
        return str(data.get("html") or "")

    async def scope(self, gpo_id: str, login: Login | None, inventory: list[tuple[str, str]]) -> dict:
        data = await self._run(scripts.scope(check_id(gpo_id)), login) or {}
        computers = [{k: c.get(k) for k in ("name", "dns", "ou", "enabled")} for c in as_list(data.get("computers"))]
        return {"computers": computers, "filtered": bool(data.get("filtered"))}

    async def change(self, script: str, login: Login | None) -> dict:
        return await self._run(script, login) or {}

    async def set_status(self, gpo_id: str, status: str, login: Login | None) -> None:
        await self.change(
            scripts.set_status(check_id(gpo_id), check_status(status), self.settings.gpo_backup_path), login
        )

    async def link_add(self, gpo_id: str, target: str, enforced: bool, login: Login | None) -> None:
        await self.change(scripts.link_add(check_id(gpo_id), check_target(target), enforced), login)

    async def link_set(self, gpo_id: str, target: str, enabled: bool | None, enforced: bool | None, login) -> None:
        await self.change(scripts.link_set(check_id(gpo_id), check_target(target), enabled, enforced), login)

    async def link_remove(self, gpo_id: str, target: str, login: Login | None) -> None:
        await self.change(scripts.link_remove(check_id(gpo_id), check_target(target)), login)

    async def registry_set(self, gpo_id: str, key: str, name: str, kind: str, value: Any, login: Login | None) -> None:
        k, n, t, v = check_registry(key, name, kind, value)
        await self.change(scripts.registry_set(check_id(gpo_id), k, n, t, v, self.settings.gpo_backup_path), login)

    async def registry_remove(self, gpo_id: str, key: str, name: str, login: Login | None) -> None:
        k, n, _, _ = check_registry(key, name, "String", "")
        await self.change(scripts.registry_remove(check_id(gpo_id), k, n, self.settings.gpo_backup_path), login)

    async def create(self, name: str, comment: str, login: Login | None) -> str:
        data = await self.change(scripts.create(check_name(name), comment.strip()[:1000]), login)
        return str(data.get("id") or "")

    async def delete(self, gpo_id: str, login: Login | None) -> None:
        await self.change(scripts.delete(check_id(gpo_id), self.settings.gpo_backup_path), login)


# --- emulator ------------------------------------------------------------------------------------

DOMAIN = "DC=factory,DC=local"
OU_COMPUTERS = f"OU=Компьютеры,{DOMAIN}"
OU_BUH = f"OU=Бухгалтерия,{OU_COMPUTERS}"
OU_SHOP = f"OU=Цех,{OU_COMPUTERS}"
OU_SERVERS = f"OU=Серверы,{DOMAIN}"
OU_DC = f"OU=Domain Controllers,{DOMAIN}"
EMULATED_OUS = [
    {"dn": DOMAIN, "name": "factory.local"},
    {"dn": OU_DC, "name": "factory.local/Domain Controllers"},
    {"dn": OU_COMPUTERS, "name": "factory.local/Компьютеры"},
    {"dn": OU_BUH, "name": "factory.local/Компьютеры/Бухгалтерия"},
    {"dn": OU_SHOP, "name": "factory.local/Компьютеры/Цех"},
    {"dn": OU_SERVERS, "name": "factory.local/Серверы"},
]
_STAMP = "2026-10-01T09:00:00+03:00"


def _sample() -> dict[str, dict]:
    def gpo(
        gid: str, name: str, status: str, links: list[tuple[str, bool, bool]], registry: list[tuple], desc=""
    ) -> dict:
        return {
            "id": gid,
            "name": name,
            "status": status,
            "description": desc,
            "owner": "FACTORY\\Domain Admins",
            "created": _STAMP,
            "modified": _STAMP,
            "wmi_filter": None,
            "links": [{"target": t, "enabled": e, "enforced": f} for t, e, f in links],
            "registry": [{"key": k, "name": n, "type": ty, "value": v, "state": "Set"} for k, n, ty, v in registry],
            "security_filter": [{"name": "Authenticated Users", "type": "WellKnownGroup"}],
        }

    items = [
        gpo(
            "31b2f340-016d-11d2-945f-00c04fb984f9",
            "Default Domain Policy",
            "AllSettingsEnabled",
            [(DOMAIN, True, False)],
            [
                (
                    "HKLM\\Software\\Policies\\Microsoft\\Windows\\WindowsUpdate\\AU",
                    "NoAutoRebootWithLoggedOnUsers",
                    "DWord",
                    "1",
                )
            ],
        ),
        gpo(
            "6ac1786c-016f-11d2-945f-00c04fb984f9",
            "Default Domain Controllers Policy",
            "AllSettingsEnabled",
            [(OU_DC, True, False)],
            [],
        ),
        gpo(
            "5c3e0f0a-8a3b-4b8e-9d1e-0b6f4a1e2c01",
            "Бухгалтерия: подключение дисков и принтеров",
            "UserSettingsDisabled",
            [(OU_BUH, True, False)],
            [
                (
                    "HKLM\\Software\\Policies\\Microsoft\\Windows NT\\Printers\\PointAndPrint",
                    "Restricted",
                    "DWord",
                    "1",
                ),
                (
                    "HKLM\\Software\\Policies\\Microsoft\\Windows NT\\Printers\\PointAndPrint",
                    "TrustedServers",
                    "DWord",
                    "1",
                ),
                (
                    "HKLM\\Software\\Policies\\Microsoft\\Windows NT\\Printers\\PointAndPrint",
                    "ServerList",
                    "String",
                    "print01.factory.local",
                ),
            ],
            "Принтеры бухгалтерии и диск Z:",
        ),
        gpo(
            "8f2d6b41-3c7e-4a55-b1d2-6e9a0c4f7d02",
            "Запрет USB-накопителей",
            "AllSettingsEnabled",
            [(OU_SHOP, True, True)],
            [("HKLM\\Software\\Policies\\Microsoft\\Windows\\RemovableStorageDevices", "Deny_All", "DWord", "1")],
        ),
        gpo(
            "a1b2c3d4-0e0f-4a1b-9c2d-3e4f5a6b7c03",
            "Обои рабочего стола",
            "ComputerSettingsDisabled",
            [(OU_COMPUTERS, False, False)],
            [
                (
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System",
                    "Wallpaper",
                    "String",
                    "\\\\fs01\\public\\wallpaper.jpg",
                )
            ],
        ),
        gpo("d4e5f6a7-1b2c-4d3e-8f9a-0b1c2d3e4f04", "Тест: новая политика", "AllSettingsEnabled", [], []),
    ]
    return {g["id"]: g for g in items}


def emulated_ou(name: str, kind: str) -> str:
    """Where an inventory computer sits in the emulated domain."""
    n = name.upper()
    if kind == "SERVER" or "SRV" in n or n.startswith("DC"):
        return OU_DC if n.startswith("DC") else OU_SERVERS
    if "BUH" in n or "БУХ" in n:
        return OU_BUH
    return OU_SHOP


def _under(dn: str, target: str) -> bool:
    return dn.lower() == target.lower() or dn.lower().endswith("," + target.lower())


@dataclass
class EmulatedGpo:
    store: dict[str, dict] = field(default_factory=_sample)
    simulated: bool = True
    writes_enabled: bool = True
    host: str = "dc01.factory.local (эмуляция)"

    def _get(self, gpo_id: str) -> dict:
        g = self.store.get(check_id(gpo_id))
        if g is None:
            raise GpoError("Такой групповой политики нет")
        return g

    def _out(self, g: dict) -> dict:
        names = {o["dn"].lower(): o["name"] for o in EMULATED_OUS}
        links = [
            {**link, "target_name": names.get(link["target"].lower(), link["target"]), "order": 1}
            for link in g["links"]
        ]
        return {
            **{k: g[k] for k in ("id", "name", "status", "description", "owner", "created", "modified", "wmi_filter")},
            "links": links,
        }

    def _touch(self, g: dict) -> None:
        from datetime import UTC, datetime

        g["modified"] = datetime.now(UTC).isoformat()

    async def gpos(self, login: Login | None) -> list[dict]:
        return [self._out(g) for g in sorted(self.store.values(), key=lambda x: x["name"].lower())]

    async def ous(self, login: Login | None) -> list[dict]:
        return list(EMULATED_OUS)

    async def detail(self, gpo_id: str, login: Login | None) -> dict:
        g = self._get(gpo_id)
        return {
            "gpo": self._out(g),
            "registry": copy.deepcopy(g["registry"]),
            "security_filter": copy.deepcopy(g["security_filter"]),
        }

    async def report(self, gpo_id: str, login: Login | None) -> str:
        g = self._get(gpo_id)
        rows = "".join(
            f"<tr><td>{html.escape(r['key'])}</td><td>{html.escape(r['name'])}</td><td>{r['type']}</td>"
            f"<td>{html.escape(str(r['value']))}</td></tr>"
            for r in g["registry"]
        )
        links = "".join(
            f"<li>{html.escape(link['target'])}{' (принудительно)' if link['enforced'] else ''}"
            f"{'' if link['enabled'] else ' — выключена'}</li>"
            for link in g["links"]
        )
        return (
            f"<html><head><meta charset='utf-8'><title>{html.escape(g['name'])}</title></head><body>"
            f"<h1>{html.escape(g['name'])}</h1><p>Эмуляция: отчёт собран сервером NetAdmin, не GPMC.</p>"
            f"<p>Состояние: {g['status']}</p><h2>Связи</h2><ul>{links or '<li>нет</li>'}</ul>"
            f"<h2>Параметры реестра</h2><table border='1' cellpadding='4'><tr><th>Раздел</th><th>Параметр</th>"
            f"<th>Тип</th><th>Значение</th></tr>{rows}</table></body></html>"
        )

    def _reaches(self, g: dict, ou: str) -> bool:
        return any(link["enabled"] and _under(ou, link["target"]) for link in g["links"])

    async def scope(self, gpo_id: str, login: Login | None, inventory: list[tuple[str, str]]) -> dict:
        g = self._get(gpo_id)
        computers = []
        for name, kind in inventory:
            ou = emulated_ou(name, kind)
            if self._reaches(g, ou):
                computers.append({"name": name, "dns": f"{name.lower()}.factory.local", "ou": ou, "enabled": True})
        return {"computers": sorted(computers, key=lambda c: c["name"]), "filtered": False}

    def applied(self, host: str, kind: str = "WORKSTATION") -> dict:
        """gpresult of an emulated computer: the GPOs whose enabled links reach its OU."""
        name = host.split(".")[0].upper()
        ou = emulated_ou(name, kind)
        out = []
        for g in self.store.values():
            link = next((lk for lk in g["links"] if lk["enabled"] and _under(ou, lk["target"])), None)
            if link is None:
                continue
            computer_on = g["status"] in ("AllSettingsEnabled", "UserSettingsDisabled")
            out.append(
                {
                    "id": g["id"],
                    "name": g["name"],
                    "enabled": computer_on,
                    "applied": computer_on,
                    "filtered": False,
                    "denied": False,
                    "link": link["target"],
                }
            )
        seed = int(hashlib.sha256(name.encode()).hexdigest()[:6], 16)
        return {"read_at": f"2026-10-29T0{seed % 9}:1{seed % 6}:00", "site": "Default-First-Site-Name", "gpos": out}

    async def set_status(self, gpo_id: str, status: str, login: Login | None) -> None:
        g = self._get(gpo_id)
        g["status"] = check_status(status)
        self._touch(g)

    async def link_add(self, gpo_id: str, target: str, enforced: bool, login: Login | None) -> None:
        g = self._get(gpo_id)
        t = check_target(target)
        if any(link["target"].lower() == t.lower() for link in g["links"]):
            raise GpoError("Политика уже связана с этим подразделением")
        g["links"].append({"target": t, "enabled": True, "enforced": enforced})
        self._touch(g)

    async def link_set(self, gpo_id: str, target: str, enabled: bool | None, enforced: bool | None, login) -> None:
        g = self._get(gpo_id)
        t = check_target(target)
        link = next((lk for lk in g["links"] if lk["target"].lower() == t.lower()), None)
        if link is None:
            raise GpoError("Связи с этим подразделением нет")
        if enabled is not None:
            link["enabled"] = enabled
        if enforced is not None:
            link["enforced"] = enforced
        self._touch(g)

    async def link_remove(self, gpo_id: str, target: str, login: Login | None) -> None:
        g = self._get(gpo_id)
        t = check_target(target)
        g["links"] = [lk for lk in g["links"] if lk["target"].lower() != t.lower()]
        self._touch(g)

    async def registry_set(self, gpo_id: str, key: str, name: str, kind: str, value: Any, login: Login | None) -> None:
        g = self._get(gpo_id)
        k, n, t, v = check_registry(key, name, kind, value)
        g["registry"] = [
            r for r in g["registry"] if not (r["key"].lower() == k.lower() and r["name"].lower() == n.lower())
        ]
        g["registry"].append({"key": k, "name": n, "type": t, "value": str(v), "state": "Set"})
        g["registry"].sort(key=lambda r: (r["key"].lower(), r["name"].lower()))
        self._touch(g)

    async def registry_remove(self, gpo_id: str, key: str, name: str, login: Login | None) -> None:
        g = self._get(gpo_id)
        k, n, _, _ = check_registry(key, name, "String", "")
        before = len(g["registry"])
        g["registry"] = [
            r for r in g["registry"] if not (r["key"].lower() == k.lower() and r["name"].lower() == n.lower())
        ]
        if len(g["registry"]) == before:
            raise GpoError("Такого параметра в политике нет")
        self._touch(g)

    async def create(self, name: str, comment: str, login: Login | None) -> str:
        n = check_name(name)
        if any(g["name"].lower() == n.lower() for g in self.store.values()):
            raise GpoError("Политика с таким именем уже есть")
        gid = str(uuid.uuid4())
        self.store[gid] = {
            "id": gid,
            "name": n,
            "status": "AllSettingsEnabled",
            "description": comment.strip()[:1000],
            "owner": "FACTORY\\Domain Admins",
            "created": _STAMP,
            "modified": _STAMP,
            "wmi_filter": None,
            "links": [],
            "registry": [],
            "security_filter": [{"name": "Authenticated Users", "type": "WellKnownGroup"}],
        }
        self._touch(self.store[gid])
        return gid

    async def delete(self, gpo_id: str, login: Login | None) -> None:
        g = self._get(gpo_id)
        del self.store[g["id"]]
