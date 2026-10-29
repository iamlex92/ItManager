"""Group Policy straight from the domain: LDAP for the GPOs, their state, links and security filter, SMB to SYSVOL
for their registry settings (Registry.pol). Works the same with a Samba AD controller (Astra Linux, ALT) and with
Windows — no Windows host, no PowerShell. What GPMC stores, this reads and writes:

- a GPO is a groupPolicyContainer under CN=Policies,CN=System: displayName, flags (state), versionNumber,
  gPCFileSysPath (its SYSVOL folder), gPC*ExtensionNames (which client extensions process it);
- links are the gPLink attribute of the domain and OUs: [LDAP://<GPO DN>;N], N: 1 disabled, 2 enforced;
  gPOptions=1 blocks inheritance;
- Administrative Templates settings are Machine/Registry.pol (HKLM) and User/Registry.pol (HKCU) in the PReg
  format; after a change the version goes up in GPT.INI and in versionNumber, so computers fetch it again;
- who it applies to is the «Apply Group Policy» right in the GPO's nTSecurityDescriptor.

Creating and deleting GPOs needs the SYSVOL permissions only samba-tool or GPMC set up right, so those two are
left to them.
"""

import asyncio
import html
import re
import struct
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol
from urllib.parse import urlparse

from ..config import Settings
from ..runbooks.real import LdapDirectory, Login, RunbookConfigError
from .backend import GpoError, check_id, check_registry, check_status, check_target

# The registry client-side extension and the Administrative Templates tool, as GPMC writes them.
CSE_REGISTRY = "{35378EAC-683F-11D2-A89A-00C04FBBCFA2}"
TOOL_ADMIN_TEMPLATES = "{D02B1F72-3407-48AE-BA88-E8213C6761F1}"
APPLY_GROUP_POLICY = uuid.UUID("edacfd8f-ffb3-11d1-b41d-00a0c968f939")
STATUS_BY_FLAGS = {
    0: "AllSettingsEnabled",
    1: "UserSettingsDisabled",
    2: "ComputerSettingsDisabled",
    3: "AllSettingsDisabled",
}
FLAGS_BY_STATUS = {v: k for k, v in STATUS_BY_FLAGS.items()}
WELL_KNOWN = {
    "S-1-5-11": "Authenticated Users",
    "S-1-1-0": "Everyone",
    "S-1-5-9": "Enterprise Domain Controllers",
    "S-1-5-18": "SYSTEM",
}
EVERYONE_SIDS = {"S-1-5-11", "S-1-1-0"}
DISABLED_ACCOUNT = 0x2

# --- PReg (Registry.pol) -------------------------------------------------------------------------------------

REG_TYPES = {"String": 1, "ExpandString": 2, "Binary": 3, "DWord": 4, "MultiString": 7, "QWord": 11}
REG_NAMES = {v: k for k, v in REG_TYPES.items()}
PREG_HEADER = b"PReg" + struct.pack("<I", 1)


@dataclass
class PolEntry:
    key: str
    name: str
    type: int
    data: bytes


def _u16(text: str) -> bytes:
    return (text + "\0").encode("utf-16-le")


def parse_pol(raw: bytes | None) -> list[PolEntry]:
    """[key;name;type;size;data] entries; the strings are UTF-16LE and end with a NUL."""
    if not raw:
        return []
    if not raw.startswith(b"PReg"):
        raise GpoError("Registry.pol повреждён: нет заголовка PReg")
    out: list[PolEntry] = []
    i = 8
    sep, open_, close = ";".encode("utf-16-le"), "[".encode("utf-16-le"), "]".encode("utf-16-le")

    def read_str(pos: int) -> tuple[str, int]:
        end = pos
        while end + 1 < len(raw) and raw[end : end + 2] != b"\0\0":
            end += 2
        return raw[pos:end].decode("utf-16-le"), end + 2

    while i + 2 <= len(raw):
        if raw[i : i + 2] != open_:
            break
        key, i = read_str(i + 2)
        i += len(sep)
        name, i = read_str(i)
        i += len(sep)
        (kind,) = struct.unpack_from("<I", raw, i)
        i += 4 + len(sep)
        (size,) = struct.unpack_from("<I", raw, i)
        i += 4 + len(sep)
        data = raw[i : i + size]
        i += size
        if raw[i : i + 2] != close:
            raise GpoError("Registry.pol повреждён: запись не закрыта")
        i += 2
        out.append(PolEntry(key, name, kind, data))
    return out


def build_pol(entries: list[PolEntry]) -> bytes:
    sep, open_, close = ";".encode("utf-16-le"), "[".encode("utf-16-le"), "]".encode("utf-16-le")
    body = b"".join(
        open_
        + _u16(e.key)
        + sep
        + _u16(e.name)
        + sep
        + struct.pack("<I", e.type)
        + sep
        + struct.pack("<I", len(e.data))
        + sep
        + e.data
        + close
        for e in entries
    )
    return PREG_HEADER + body


def encode_value(kind: str, value: Any) -> tuple[int, bytes]:
    t = REG_TYPES[kind]
    if kind == "DWord":
        return t, struct.pack("<I", int(value))
    if kind == "QWord":
        return t, struct.pack("<Q", int(value))
    if kind == "MultiString":
        parts = [p for p in str(value).split("\n")]
        return t, "".join(p + "\0" for p in parts).encode("utf-16-le") + b"\0\0"
    return t, _u16(str(value))


def decode_value(e: PolEntry) -> str:
    if e.type == 4 and len(e.data) >= 4:
        return str(struct.unpack_from("<I", e.data)[0])
    if e.type == 11 and len(e.data) >= 8:
        return str(struct.unpack_from("<Q", e.data)[0])
    if e.type in (1, 2):
        return e.data.decode("utf-16-le", errors="replace").rstrip("\0")
    if e.type == 7:
        return "\n".join(p for p in e.data.decode("utf-16-le", errors="replace").split("\0") if p)
    return e.data.hex("-").upper()


def split_key(full: str) -> tuple[str, str]:
    """HKLM\\Software\\X → (Machine, Software\\X); HKCU → User."""
    hive, _, rest = full.partition("\\")
    return ("Machine" if hive.upper() == "HKLM" else "User"), rest


# --- gPLink, extension names, security descriptor -------------------------------------------------------------

_LINK = re.compile(r"\[LDAP://([^;\]]+);(\d+)\]", re.IGNORECASE)


@dataclass
class Link:
    gpo_dn: str
    options: int

    @property
    def enabled(self) -> bool:
        return not self.options & 1

    @property
    def enforced(self) -> bool:
        return bool(self.options & 2)


def parse_gplink(value: str | None) -> list[Link]:
    return [Link(m.group(1), int(m.group(2))) for m in _LINK.finditer(value or "")]


def build_gplink(links: list[Link]) -> str:
    return "".join(f"[LDAP://{lk.gpo_dn};{lk.options}]" for lk in links)


def guid_of(dn: str) -> str:
    m = re.search(r"\{([0-9a-fA-F-]{36})\}", dn)
    return m.group(1).lower() if m else ""


def add_extension(names: str | None, cse: str, tool: str) -> str:
    """gPC*ExtensionNames with [{CSE}{tool}] present; groups stay sorted by CSE GUID, as Windows expects."""
    groups = re.findall(r"\[([^\]]*)\]", names or "")
    parsed = [re.findall(r"\{[0-9A-Fa-f-]{36}\}", g) for g in groups]
    for g in parsed:
        if g and g[0].upper() == cse.upper():
            if tool.upper() not in (x.upper() for x in g[1:]):
                g.append(tool)
                g[1:] = sorted(g[1:], key=str.upper)
            break
    else:
        parsed.append([cse, tool])
    parsed.sort(key=lambda g: g[0].upper() if g else "")
    return "".join("[" + "".join(g) + "]" for g in parsed if g)


def sid_to_str(raw: bytes) -> str:
    revision, count = raw[0], raw[1]
    authority = int.from_bytes(raw[2:8], "big")
    subs = struct.unpack_from("<" + "I" * count, raw, 8)
    return f"S-{revision}-{authority}" + "".join(f"-{s}" for s in subs)


def apply_trustees(sd: bytes) -> list[str]:
    """SIDs with the «Apply Group Policy» extended right allowed in the DACL of a GPO."""
    if not sd or len(sd) < 20:
        return []
    (dacl_off,) = struct.unpack_from("<I", sd, 16)
    if not dacl_off:
        return []
    count = struct.unpack_from("<H", sd, dacl_off + 4)[0]
    pos = dacl_off + 8
    out: list[str] = []
    for _ in range(count):
        ace_type, _flags, size = sd[pos], sd[pos + 1], struct.unpack_from("<H", sd, pos + 2)[0]
        if ace_type == 0x05:  # ACCESS_ALLOWED_OBJECT
            mask, oflags = struct.unpack_from("<II", sd, pos + 4)
            p = pos + 12
            object_type = None
            if oflags & 1:
                object_type = uuid.UUID(bytes_le=bytes(sd[p : p + 16]))
                p += 16
            if oflags & 2:
                p += 16
            if object_type == APPLY_GROUP_POLICY and mask & 0x100:
                out.append(sid_to_str(bytes(sd[p : pos + size])))
        pos += size
    return out


def canonical(dn: str) -> str:
    """OU=Бух,OU=ПК,DC=ecotehs,DC=local → ecotehs.local/ПК/Бух."""
    parts = [p.split("=", 1) for p in re.split(r"(?<!\\),", dn) if "=" in p]
    domain = ".".join(v for k, v in parts if k.upper() == "DC")
    rest = [v for k, v in parts if k.upper() != "DC"]
    return "/".join([domain, *reversed(rest)])


def simple_report(gpo: dict, registry: list[dict], trustees: list[dict], source: str) -> str:
    rows = "".join(
        f"<tr><td>{html.escape(r['key'])}</td><td>{html.escape(r['name'])}</td><td>{html.escape(r['type'])}</td>"
        f"<td>{html.escape(str(r.get('value') or ''))}</td></tr>"
        for r in registry
    )
    links = "".join(
        f"<li>{html.escape(lk['target_name'])}{' (принудительно)' if lk['enforced'] else ''}"
        f"{'' if lk['enabled'] else ' — выключена'}</li>"
        for lk in gpo["links"]
    )
    who = ", ".join(html.escape(t.get("name") or "") for t in trustees) or "никому"
    return (
        f"<html><head><meta charset='utf-8'><title>{html.escape(gpo['name'])}</title></head><body>"
        f"<h1>{html.escape(gpo['name'])}</h1><p>{html.escape(source)}</p><p>Состояние: {gpo['status']}</p>"
        f"<p>Применяется к: {who}</p><h2>Связи</h2><ul>{links or '<li>нет</li>'}</ul>"
        f"<h2>Параметры реестра</h2><table border='1' cellpadding='4'><tr><th>Раздел</th><th>Параметр</th>"
        f"<th>Тип</th><th>Значение</th></tr>{rows}</table></body></html>"
    )


# --- access to the domain ------------------------------------------------------------------------------------


class Directory(Protocol):
    base_dn: str

    def search(self, base: str, flt: str, attrs: list[str], sd: bool = False, scope: str = "SUBTREE") -> list[dict]:
        """[{dn, attrs: {name: [str values]}, raw: {name: [bytes]}}]"""

    def replace(self, dn: str, attr: str, values: list[Any]) -> None: ...


class Sysvol(Protocol):
    def read(self, path: str) -> bytes | None: ...

    def write(self, path: str, data: bytes) -> None: ...


class LdapDomain:
    """The directory over ldap3 (LDAPS or StartTLS, like the runbooks), with paged searches."""

    def __init__(self, settings: Settings, login: Login) -> None:
        try:
            d = LdapDirectory(settings, login)
        except RunbookConfigError as e:
            raise GpoError(str(e)) from e
        self.conn = d.conn
        self.base_dn = d.base_dn
        import ldap3

        self._ldap3 = ldap3

    def search(self, base: str, flt: str, attrs: list[str], sd: bool = False, scope: str = "SUBTREE") -> list[dict]:
        from ldap3.protocol.microsoft import security_descriptor_control

        controls = security_descriptor_control(sdflags=0x04) if sd else None
        rows = self.conn.extend.standard.paged_search(
            base, flt, search_scope=scope, attributes=attrs, controls=controls, paged_size=500, generator=False
        )
        out = []
        for r in rows or []:
            if r.get("type") != "searchResEntry":
                continue
            raw = r.get("raw_attributes") or {}
            out.append(
                {
                    "dn": r["dn"],
                    "attrs": {k: [v.decode("utf-8", errors="replace") for v in vs] for k, vs in raw.items()},
                    "raw": raw,
                }
            )
        return out

    def replace(self, dn: str, attr: str, values: list[Any]) -> None:
        ok = self.conn.modify(dn, {attr: [(self._ldap3.MODIFY_REPLACE, values)]})
        if not ok:
            raise GpoError(
                f"Контроллер не принял изменение {attr}: {self.conn.result.get('description')} "
                f"{self.conn.result.get('message', '')}".strip()
            )


class SmbSysvol:
    """SYSVOL over SMB (smbprotocol), signed, with the domain login."""

    def __init__(self, host: str, login: Login) -> None:
        import smbclient

        self._smb = smbclient
        try:
            smbclient.register_session(host, username=login.username, password=login.password, connection_timeout=10)
        except Exception as e:
            raise GpoError(f"SYSVOL на {host} недоступен по SMB: {e}") from e

    def read(self, path: str) -> bytes | None:
        try:
            with self._smb.open_file(path, mode="rb") as f:
                return f.read()
        except FileNotFoundError:
            return None
        except OSError as e:
            if "STATUS_OBJECT_NAME_NOT_FOUND" in str(e) or "STATUS_OBJECT_PATH_NOT_FOUND" in str(e):
                return None
            raise GpoError(f"Не удалось прочитать {path}: {e}") from e

    def write(self, path: str, data: bytes) -> None:
        try:
            folder = path.rsplit("\\", 1)[0]
            self._smb.makedirs(folder, exist_ok=True)
            with self._smb.open_file(path, mode="wb") as f:
                f.write(data)
        except OSError as e:
            raise GpoError(f"Не удалось записать {path}: {e}") from e


# --- the backend -----------------------------------------------------------------------------------------------

GPO_ATTRS = [
    "cn",
    "displayName",
    "flags",
    "whenCreated",
    "whenChanged",
    "versionNumber",
    "gPCFileSysPath",
    "gPCMachineExtensionNames",
    "gPCUserExtensionNames",
    "gPCWQLFilter",
    "distinguishedName",
]


def _one(attrs: dict, name: str, default: str = "") -> str:
    v = attrs.get(name) or []
    return v[0] if v else default


def _when(value: str) -> str | None:
    """20261029083000.0Z → 2026-10-29T08:30:00Z."""
    m = re.match(r"^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})", value or "")
    return f"{m[1]}-{m[2]}-{m[3]}T{m[4]}:{m[5]}:{m[6]}Z" if m else None


@dataclass
class NativeGpo:
    """GPOs over LDAP and SYSVOL. Blocking libraries, so every call runs in a worker thread."""

    settings: Settings
    directory_factory: Callable[[Login], Directory] | None = None
    sysvol_factory: Callable[[str, Login], Sysvol] | None = None
    simulated: bool = False

    def __post_init__(self) -> None:
        self.directory_factory = self.directory_factory or (lambda login: LdapDomain(self.settings, login))
        self.sysvol_factory = self.sysvol_factory or (lambda host, login: SmbSysvol(host, login))

    @property
    def writes_enabled(self) -> bool:
        return self.settings.allow_runbook_writes

    @property
    def host(self) -> str:
        return urlparse(self.settings.ad_ldap_url.strip()).hostname or ""

    # plumbing

    def _dir(self, login: Login | None) -> Directory:
        if login is None:
            raise GpoError("Не выбрана учётная запись домена: «Настройки сервера» → «Учётная запись домена».")
        if not self.host:
            raise GpoError(
                "Не задан контроллер домена: «Настройки сервера» → «Контроллер домена», "
                "например ldaps://dc01.ecotehs.local"
            )
        assert self.directory_factory is not None
        return self.directory_factory(login)

    async def _call(self, fn, *args):
        try:
            return await asyncio.to_thread(fn, *args)
        except GpoError:
            raise
        except Exception as e:  # ldap3 and smbprotocol errors, shown as they are
            raise GpoError(f"{type(e).__name__}: {e}"[:1000]) from e

    def _policies(self, d: Directory) -> str:
        return f"CN=Policies,CN=System,{d.base_dn}"

    def _gpo_rows(self, d: Directory, gpo_id: str | None = None) -> list[dict]:
        flt = "(objectClass=groupPolicyContainer)"
        if gpo_id:
            flt = f"(&(objectClass=groupPolicyContainer)(cn={{{check_id(gpo_id)}}}))"
        return d.search(self._policies(d), flt, GPO_ATTRS, scope="LEVEL")

    def _all_links(self, d: Directory) -> list[dict]:
        out = []
        for row in d.search(d.base_dn, "(gPLink=*)", ["gPLink", "gPOptions"]):
            links = parse_gplink(_one(row["attrs"], "gPLink"))
            n = len(links)
            for i, lk in enumerate(links):
                out.append(
                    {
                        "gpo": guid_of(lk.gpo_dn),
                        "target": row["dn"],
                        "target_name": canonical(row["dn"]),
                        "enabled": lk.enabled,
                        "enforced": lk.enforced,
                        "order": n - i,
                        "blocks_inheritance": _one(row["attrs"], "gPOptions", "0") == "1",
                    }
                )
        return out

    def _gpo(self, row: dict, links: list[dict]) -> dict:
        a = row["attrs"]
        gid = guid_of(_one(a, "cn"))
        return {
            "id": gid,
            "name": _one(a, "displayName", gid),
            "status": STATUS_BY_FLAGS.get(int(_one(a, "flags", "0") or 0) & 3, "AllSettingsEnabled"),
            "description": None,
            "owner": None,
            "created": _when(_one(a, "whenCreated")),
            "modified": _when(_one(a, "whenChanged")),
            "wmi_filter": _one(a, "gPCWQLFilter") or None,
            "links": [
                {k: lk[k] for k in ("target", "target_name", "enabled", "enforced", "order")}
                for lk in links
                if lk["gpo"] == gid
            ],
        }

    def _row(self, d: Directory, gpo_id: str) -> dict:
        rows = self._gpo_rows(d, gpo_id)
        if not rows:
            raise GpoError("Такой групповой политики нет")
        return rows[0]

    def _folder(self, row: dict) -> str:
        """gPCFileSysPath points at the domain (\\\\ecotehs.local\\SysVol\\...): the controller itself is asked."""
        path = _one(row["attrs"], "gPCFileSysPath")
        m = re.match(r"^\\\\[^\\]+\\(.+)$", path)
        if not m:
            raise GpoError(f"У политики нет пути в SYSVOL: {path!r}")
        return f"\\\\{self.host}\\{m.group(1)}"

    def _trustees(self, d: Directory, gpo_id: str) -> list[dict]:
        rows = d.search(
            self._policies(d), f"(cn={{{check_id(gpo_id)}}})", ["nTSecurityDescriptor"], sd=True, scope="LEVEL"
        )
        raw = (rows[0]["raw"].get("nTSecurityDescriptor") or [b""])[0] if rows else b""
        out = []
        for sid in apply_trustees(raw):
            name = WELL_KNOWN.get(sid)
            dn = None
            kind = "WellKnownGroup" if name else "Unknown"
            if not name:
                found = d.search(d.base_dn, f"(objectSid={sid})", ["sAMAccountName", "objectClass"])
                if found:
                    a = found[0]["attrs"]
                    name, dn = _one(a, "sAMAccountName", sid), found[0]["dn"]
                    kind = "Computer" if "computer" in [c.lower() for c in a.get("objectClass", [])] else "Group"
            out.append({"name": name or sid, "type": kind, "sid": sid, "dn": dn})
        return out

    # reads

    async def gpos(self, login: Login | None) -> list[dict]:
        def work() -> list[dict]:
            d = self._dir(login)
            links = self._all_links(d)
            return sorted((self._gpo(r, links) for r in self._gpo_rows(d)), key=lambda g: g["name"].lower())

        return await self._call(work)

    async def ous(self, login: Login | None) -> list[dict]:
        def work() -> list[dict]:
            d = self._dir(login)
            ous = d.search(d.base_dn, "(objectClass=organizationalUnit)", ["distinguishedName"])
            items = [{"dn": d.base_dn, "name": canonical(d.base_dn)}]
            items += sorted(({"dn": o["dn"], "name": canonical(o["dn"])} for o in ous), key=lambda o: o["name"].lower())
            return items

        return await self._call(work)

    def _registry(self, row: dict, sysvol: Sysvol) -> list[dict]:
        folder = self._folder(row)
        out = []
        for part, hive in (("Machine", "HKLM"), ("User", "HKCU")):
            for e in parse_pol(sysvol.read(f"{folder}\\{part}\\Registry.pol")):
                deleting = e.name.lower().startswith("**del.") or e.name.startswith("**Delete")
                out.append(
                    {
                        "key": f"{hive}\\{e.key}",
                        "name": e.name[6:] if e.name.lower().startswith("**del.") else e.name,
                        "type": REG_NAMES.get(e.type, f"Type{e.type}"),
                        "value": "" if deleting else decode_value(e),
                        "state": "Delete" if deleting else "Set",
                    }
                )
        return sorted(out, key=lambda r: (r["key"].lower(), r["name"].lower()))

    async def detail(self, gpo_id: str, login: Login | None) -> dict:
        def work() -> dict:
            d = self._dir(login)
            row = self._row(d, gpo_id)
            assert self.sysvol_factory is not None
            sysvol = self.sysvol_factory(self.host, login)
            trustees = self._trustees(d, gpo_id)
            return {
                "gpo": self._gpo(row, self._all_links(d)),
                "registry": self._registry(row, sysvol),
                "security_filter": [{"name": t["name"], "type": t["type"]} for t in trustees],
            }

        return await self._call(work)

    async def report(self, gpo_id: str, login: Login | None) -> str:
        det = await self.detail(gpo_id, login)
        return simple_report(
            det["gpo"],
            det["registry"],
            det["security_filter"],
            f"Собрано сервером NetAdmin из LDAP и SYSVOL контроллера {self.host}.",
        )

    async def scope(self, gpo_id: str, login: Login | None, inventory: list[tuple[str, str]]) -> dict:
        def work() -> dict:
            d = self._dir(login)
            gid = check_id(gpo_id)
            links = self._all_links(d)
            blockers = {lk["target"].lower() for lk in links if lk["blocks_inheritance"]}
            found: dict[str, dict] = {}
            for lk in (x for x in links if x["gpo"] == gid and x["enabled"]):
                target = lk["target"].lower()
                for c in d.search(
                    lk["target"], "(objectClass=computer)", ["name", "dNSHostName", "userAccountControl", "objectSid"]
                ):
                    dn = c["dn"].lower()
                    blocked = not lk["enforced"] and any(
                        b != target and dn.endswith("," + b) and b.endswith("," + target) for b in blockers
                    )
                    if not blocked:
                        found[dn] = c
            trustees = self._trustees(d, gid)
            everyone = any(t["sid"] in EVERYONE_SIDS or t["sid"].endswith("-515") for t in trustees)
            allowed: set[str] | None = None
            if not everyone:
                allowed = {t["sid"] for t in trustees if t["type"] == "Computer"}
                for t in (x for x in trustees if x["type"] == "Group" and x["dn"]):
                    for m in d.search(
                        d.base_dn,
                        f"(&(objectClass=computer)(memberOf:1.2.840.113556.1.4.1941:={t['dn']}))",
                        ["objectSid"],
                    ):
                        sid = (m["raw"].get("objectSid") or [b""])[0]
                        if sid:
                            allowed.add(sid_to_str(sid))
            computers = []
            for c in found.values():
                a = c["attrs"]
                sid = (c["raw"].get("objectSid") or [b""])[0]
                if allowed is not None and (not sid or sid_to_str(sid) not in allowed):
                    continue
                uac = int(_one(a, "userAccountControl", "0") or 0)
                computers.append(
                    {
                        "name": _one(a, "name"),
                        "dns": _one(a, "dNSHostName") or None,
                        "ou": c["dn"].split(",", 1)[1] if "," in c["dn"] else "",
                        "enabled": not uac & DISABLED_ACCOUNT,
                    }
                )
            return {"computers": sorted(computers, key=lambda x: x["name"].lower()), "filtered": not everyone}

        return await self._call(work)

    # changes

    def _bump(self, d: Directory, sysvol: Sysvol, row: dict, part: str) -> None:
        """A new version for the computer (lower 16 bits) or user part, in GPT.INI and in versionNumber."""
        version = int(_one(row["attrs"], "versionNumber", "0") or 0)
        user, machine = version >> 16, version & 0xFFFF
        if part == "Machine":
            machine = (machine + 1) & 0xFFFF
        else:
            user = (user + 1) & 0xFFFF
        new = (user << 16) | machine
        path = f"{self._folder(row)}\\GPT.INI"
        raw = sysvol.read(path) or b"[General]\r\nVersion=0\r\n"
        utf16 = raw.startswith(b"\xff\xfe")
        text = raw.decode("utf-16") if utf16 else raw.decode("latin-1")
        if re.search(r"(?im)^Version=\d+", text):
            text = re.sub(r"(?im)^Version=\d+", f"Version={new}", text, count=1)
        else:
            text = text.rstrip("\r\n") + f"\r\nVersion={new}\r\n"
        sysvol.write(path, text.encode("utf-16") if utf16 else text.encode("latin-1"))
        d.replace(row["dn"], "versionNumber", [str(new)])

    def _edit_pol(self, gpo_id: str, login: Login | None, key: str, change: Callable[[list[PolEntry], str, str], None]):
        d = self._dir(login)
        row = self._row(d, gpo_id)
        part, rel = split_key(key)
        assert self.sysvol_factory is not None
        sysvol = self.sysvol_factory(self.host, login)
        path = f"{self._folder(row)}\\{part}\\Registry.pol"
        entries = parse_pol(sysvol.read(path))
        change(entries, part, rel)
        sysvol.write(path, build_pol(entries))
        attr = "gPCMachineExtensionNames" if part == "Machine" else "gPCUserExtensionNames"
        names = _one(row["attrs"], attr)
        merged = add_extension(names, CSE_REGISTRY, TOOL_ADMIN_TEMPLATES)
        if merged != names:
            d.replace(row["dn"], attr, [merged])
        self._bump(d, sysvol, row, part)

    async def registry_set(self, gpo_id: str, key: str, name: str, kind: str, value: Any, login: Login | None) -> None:
        k, n, t, v = check_registry(key, name, kind, value)
        kind_id, data = encode_value(t, v)

        def change(entries: list[PolEntry], part: str, rel: str) -> None:
            same = {n.lower(), f"**del.{n}".lower()}
            entries[:] = [e for e in entries if not (e.key.lower() == rel.lower() and e.name.lower() in same)]
            entries.append(PolEntry(rel, n, kind_id, data))

        await self._call(self._edit_pol, gpo_id, login, k, change)

    async def registry_remove(self, gpo_id: str, key: str, name: str, login: Login | None) -> None:
        k, n, _, _ = check_registry(key, name, "String", "")

        def change(entries: list[PolEntry], part: str, rel: str) -> None:
            same = {n.lower(), f"**del.{n}".lower()}
            before = len(entries)
            entries[:] = [e for e in entries if not (e.key.lower() == rel.lower() and e.name.lower() in same)]
            if len(entries) == before:
                raise GpoError("Такого параметра в политике нет")

        await self._call(self._edit_pol, gpo_id, login, k, change)

    async def set_status(self, gpo_id: str, status: str, login: Login | None) -> None:
        flags = FLAGS_BY_STATUS[check_status(status)]

        def work() -> None:
            d = self._dir(login)
            d.replace(self._row(d, gpo_id)["dn"], "flags", [str(flags)])

        await self._call(work)

    def _relink(self, login: Login | None, gpo_id: str, target: str, edit: Callable[[list[Link], str], list[Link]]):
        d = self._dir(login)
        gpo_dn = self._row(d, gpo_id)["dn"]
        t = check_target(target)
        rows = d.search(t, "(objectClass=*)", ["gPLink"], scope="BASE")
        if not rows:
            raise GpoError(f"Подразделения {t} нет в домене")
        links = parse_gplink(_one(rows[0]["attrs"], "gPLink"))
        new = edit(links, gpo_dn)
        d.replace(rows[0]["dn"], "gPLink", [build_gplink(new)] if new else [])

    async def link_add(self, gpo_id: str, target: str, enforced: bool, login: Login | None) -> None:
        def edit(links: list[Link], gpo_dn: str) -> list[Link]:
            if any(guid_of(lk.gpo_dn) == guid_of(gpo_dn) for lk in links):
                raise GpoError("Политика уже связана с этим подразделением")
            # GPMC gives a new link the last place in link order: the front of gPLink.
            return [Link(gpo_dn, 2 if enforced else 0), *links]

        await self._call(self._relink, login, gpo_id, target, edit)

    async def link_set(self, gpo_id: str, target: str, enabled: bool | None, enforced: bool | None, login) -> None:
        def edit(links: list[Link], gpo_dn: str) -> list[Link]:
            mine = [lk for lk in links if guid_of(lk.gpo_dn) == guid_of(gpo_dn)]
            if not mine:
                raise GpoError("Связи с этим подразделением нет")
            for lk in mine:
                if enabled is not None:
                    lk.options = (lk.options & ~1) | (0 if enabled else 1)
                if enforced is not None:
                    lk.options = (lk.options & ~2) | (2 if enforced else 0)
            return links

        await self._call(self._relink, login, gpo_id, target, edit)

    async def link_remove(self, gpo_id: str, target: str, login: Login | None) -> None:
        def edit(links: list[Link], gpo_dn: str) -> list[Link]:
            return [lk for lk in links if guid_of(lk.gpo_dn) != guid_of(gpo_dn)]

        await self._call(self._relink, login, gpo_id, target, edit)

    async def create(self, name: str, comment: str, login: Login | None) -> str:
        raise GpoError(
            'Новую политику создайте в GPMC или на контроллере: samba-tool gpo create "Имя" -U administrator. '
            "Здесь её сразу можно будет связать и настроить."
        )

    async def delete(self, gpo_id: str, login: Login | None) -> None:
        raise GpoError(
            "Политику удаляют в GPMC или на контроллере: samba-tool gpo del {GUID} -U administrator. "
            "Здесь можно убрать её связи, и она перестанет действовать."
        )
