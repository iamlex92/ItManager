"""Runbooks against the real domain: Active Directory over LDAP(S) and NTFS rights over WinRM.

Off unless RUNBOOK_MODE=real. Reading is always allowed; anything that changes AD (password reset,
unlock, enable/disable) also needs ALLOW_RUNBOOK_WRITES=true and, as in the emulator, the app's
confirmation. Without the flag a write runbook only shows the account and what it would change.

Safety:
- LDAP only over TLS (ldaps:// or StartTLS): the bind sends the password, a reset sends a new one.
- WinRM only to hosts listed in RUNBOOK_FILE_SERVERS: the domain login is never sent to a host
  typed on the phone.
- Protected accounts (adminCount=1: domain admins and other privileged groups) are not changed from
  the phone.
- The login comes from the encrypted credential store (RUNBOOK_CREDENTIAL), not from the config file.

Infrastructure runbooks (database backups, VM snapshots, service restarts) have no real backend
yet and are not offered in this mode.
"""

import asyncio
import base64
import json
import logging
import re
import ssl
import struct
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from typing import Any, Protocol
from urllib.parse import urlparse

from ..config import Settings
from .catalog import Category
from .emulator import RIGHTS_RU, RunResult, _temporary_password

log = logging.getLogger(__name__)

REAL_CATEGORIES = (Category.AD, Category.FILES)

# userAccountControl and its computed twin
UF_ACCOUNTDISABLE = 0x2
UF_LOCKOUT = 0x10

# Well-known SIDs every domain user carries: Everyone, Authenticated Users, and the member
# server's local Users group (Domain Users is in it by default).
USER_WELL_KNOWN = ("S-1-1-0", "S-1-5-11", "S-1-5-32-545")

# NTFS levels as FileSystemRights masks, strongest first (same names as the emulator).
RIGHTS_MASKS = (
    ("FullControl", 0x1F01FF),
    ("Modify", 0x1301BF),
    ("ReadAndExecute", 0x1200A9),
    ("ListDirectory", 0x000001),
)
GENERIC = {
    0x10000000: 0x1F01FF,  # GENERIC_ALL
    0x20000000: 0x1200A0,  # GENERIC_EXECUTE
    0x40000000: 0x120116,  # GENERIC_WRITE
    0x80000000: 0x120089,  # GENERIC_READ
}
INHERIT_ONLY = 0x2  # PropagationFlags.InheritOnly: the ACE does not apply to the folder itself

USER_ATTRS = [
    "sAMAccountName",
    "displayName",
    "department",
    "userAccountControl",
    "msDS-User-Account-Control-Computed",
    "lockoutTime",
    "adminCount",
    "objectSid",
]

_NAME = r"[^\\/:*?\"<>|\x00-\x1f]+"
UNC = re.compile(rf"^\\\\(?P<host>[A-Za-z0-9][A-Za-z0-9.-]{{0,252}})\\{_NAME}(\\{_NAME})*\\?$")
SAM = re.compile(r"^[^\x00-\x1f\"/\\\[\]:;|=,+*?<>]{1,104}$")


class RunbookConfigError(RuntimeError):
    """The server is not set up for real runbooks; the message tells the admin what to set."""


@dataclass(frozen=True)
class Login:
    username: str
    password: str


@dataclass
class Principal:
    dn: str
    sam: str
    name: str
    department: str
    is_group: bool
    uac: int = 0
    computed_uac: int | None = None
    lockout_time: int = 0
    admin_count: int = 0
    sid: str = ""

    @property
    def enabled(self) -> bool:
        return not self.uac & UF_ACCOUNTDISABLE

    @property
    def locked(self) -> bool:
        if self.computed_uac is not None:
            return bool(self.computed_uac & UF_LOCKOUT)
        return self.lockout_time > 0

    @property
    def protected(self) -> bool:
        return self.admin_count == 1


class Directory(Protocol):
    def find(self, sam: str, groups_too: bool = False) -> Principal | None: ...
    def computer_exists(self, host: str) -> bool:
        """A computer account with this name or DNS name: the server sends the domain login only to those."""
        esc = self._ldap3.utils.conv.escape_filter_chars(host)
        short = self._ldap3.utils.conv.escape_filter_chars(host.split(".")[0])
        flt = f"(&(objectCategory=computer)(|(dNSHostName={esc})(name={short})))"
        return bool(self._search(self.base_dn, flt, ["name"]))

    def windows_computers(self, limit: int = 5000) -> list[str]:
        """Every Windows computer in the domain, by DNS name where it has one (as the script's ADSI search)."""
        flt = "(&(objectCategory=computer)(operatingSystem=*Windows*))"
        out: list[str] = []
        for row in self.conn.extend.standard.paged_search(
            self.base_dn, flt, attributes=["name", "dNSHostName"], paged_size=500, generator=True
        ):
            if row.get("type") != "searchResEntry":
                continue
            attrs = row.get("attributes", {})
            name = attrs.get("dNSHostName") or attrs.get("name")
            if isinstance(name, list):
                name = name[0] if name else None
            if name:
                out.append(str(name))
            if len(out) >= limit:
                break
        return out

    def token_sids(self, dn: str) -> set[str]: ...
    def modify(self, dn: str, changes: dict[str, Any]) -> None: ...


Shell = Callable[[str, str], tuple[int, str, str]]


# --- helpers -------------------------------------------------------------------------------


def sid_to_str(raw: bytes) -> str:
    if raw[:2] == b"S-":
        return raw.decode()
    revision, count = raw[0], raw[1]
    authority = int.from_bytes(raw[2:8], "big")
    subs = struct.unpack("<" + "I" * count, raw[8 : 8 + 4 * count])
    return "-".join(["S", str(revision), str(authority), *map(str, subs)])


def _int(values: list[bytes]) -> int:
    try:
        return int(values[0]) if values else 0
    except ValueError:
        return 0


def _text(values: list[bytes]) -> str:
    return values[0].decode("utf-8", "replace") if values else ""


def principal_from_raw(dn: str, raw: dict[str, list[bytes]], is_group: bool) -> Principal:
    computed = raw.get("msDS-User-Account-Control-Computed") or []
    return Principal(
        dn=dn,
        sam=_text(raw.get("sAMAccountName", [])),
        name=_text(raw.get("displayName", [])),
        department=_text(raw.get("department", [])),
        is_group=is_group,
        uac=_int(raw.get("userAccountControl", [])),
        computed_uac=_int(computed) if computed else None,
        lockout_time=_int(raw.get("lockoutTime", [])),
        admin_count=_int(raw.get("adminCount", [])),
        sid=sid_to_str(raw["objectSid"][0]) if raw.get("objectSid") else "",
    )


def bare_sam(text: str) -> str:
    """DOMAIN\\user, user@domain and plain user all name the same account."""
    return text.strip().split("\\")[-1].split("@")[0]


def expand_generic(mask: int) -> int:
    for bit, specific in GENERIC.items():
        if mask & bit:
            mask = (mask & ~bit) | specific
    return mask


def effective_mask(aces: Iterable[dict[str, Any]], sids: set[str]) -> int:
    """Windows evaluation order: the DACL is canonical (explicit deny, explicit allow, inherited
    deny, inherited allow), and for each right the first ACE that mentions it decides."""
    granted = decided = 0
    for ace in aces:
        if ace.get("sid") not in sids or int(ace.get("propagation", 0)) & INHERIT_ONLY:
            continue
        bits = expand_generic(int(ace["rights"])) & ~decided
        if ace.get("allow"):
            granted |= bits
        decided |= bits
    return granted


def level(mask: int) -> str | None:
    return next((name for name, need in RIGHTS_MASKS if mask & need == need), None)


def file_server_allowed(host: str, allowed: Iterable[str]) -> bool:
    host = host.lower()
    for a in allowed:
        a = a.strip().lower()
        if a and (host == a or host.split(".")[0] == a or a.split(".")[0] == host):
            return True
    return False


ACL_SCRIPT = """$ErrorActionPreference = 'Stop'
$p = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('{path}'))
$acl = Get-Acl -LiteralPath $p
$rows = @($acl.Access | ForEach-Object {{
  $sid = try {{ $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value }} catch {{ '' }}
  [pscustomobject]@{{ sid = $sid; name = $_.IdentityReference.Value; rights = [int64]$_.FileSystemRights;
    allow = ($_.AccessControlType -eq 'Allow'); inherited = $_.IsInherited; propagation = [int]$_.PropagationFlags }}
}})
ConvertTo-Json -InputObject $rows -Compress
"""


# --- the environment -----------------------------------------------------------------------


@dataclass
class RealRunbooks:
    settings: Settings
    # Built per run from the stored login; tests pass fakes.
    directory_factory: Callable[[Login], Directory] | None = None
    shell_factory: Callable[[Login], Shell] | None = None
    file_servers: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.file_servers = [h.strip() for h in self.settings.runbook_file_servers.split(",") if h.strip()]
        self.directory_factory = self.directory_factory or (lambda login: LdapDirectory(self.settings, login))
        self.shell_factory = self.shell_factory or (lambda login: winrm_shell(self.settings, login))

    @property
    def writes_enabled(self) -> bool:
        return self.settings.allow_runbook_writes

    async def run(self, runbook_id: str, params: dict[str, str], login: Login | None = None) -> RunResult:
        handler = getattr(self, f"_{runbook_id}", None)
        if handler is None:
            return RunResult(1, "", "Этот runbook работает только в эмуляторе: реальное исполнение ещё не подключено.")
        if login is None:
            return RunResult(
                1, "", "На сервере не задана учётная запись для runbooks: добавьте её в ⚙ → Учётные данные "
                "и укажите её имя в RUNBOOK_CREDENTIAL."
            )  # fmt: skip
        try:
            # ldap3 and pywinrm block, so each run goes to a worker thread.
            return await asyncio.to_thread(handler, login, **params)
        except RunbookConfigError as e:
            return RunResult(1, "", str(e))

    def _directory(self, login: Login) -> Directory:
        assert self.directory_factory is not None
        return self.directory_factory(login)

    def _blocked(self, out: list[str], change: str) -> RunResult:
        out.append(f"Нужно: {change}.")
        return RunResult(
            2,
            "\n".join(out),
            "Изменение не выполнено: на сервере запрещены изменения в AD (ALLOW_RUNBOOK_WRITES=false). "
            "Показано только текущее состояние.",
        )

    @staticmethod
    def _describe(acc: Principal) -> list[str]:
        state = ["включена" if acc.enabled else "отключена", "заблокирована" if acc.locked else "не заблокирована"]
        who = ", ".join(x for x in (acc.name, acc.department) if x)
        return [f"Get-ADUser {acc.sam}: {who or acc.dn}", f"Состояние: {', '.join(state)}"]

    def _find_user(self, d: Directory, account: str) -> Principal | RunResult:
        sam = bare_sam(account)
        if not SAM.match(sam):
            return RunResult(1, "", f"Недопустимое имя учётной записи: {account}")
        acc = d.find(sam)
        if acc is None or acc.is_group:
            return RunResult(1, "", f"Get-ADUser : Cannot find an object with identity: '{sam}'.")
        return acc

    def _guard_protected(self, acc: Principal, out: list[str]) -> RunResult | None:
        if acc.protected:
            return RunResult(
                1,
                "\n".join(out),
                "Учётная запись защищена (adminCount=1: администраторы и другие привилегированные группы). "
                "Её меняют только вручную, не с телефона.",
            )
        return None

    # --- Active Directory ---

    def _ad_reset_password(self, login: Login, account: str) -> RunResult:
        d = self._directory(login)
        acc = self._find_user(d, account)
        if isinstance(acc, RunResult):
            return acc
        out = self._describe(acc)
        if (refused := self._guard_protected(acc, out)) is not None:
            return refused
        if not self.writes_enabled:
            return self._blocked(out, "временный пароль и смена при входе (pwdLastSet = 0)")
        password = _temporary_password()
        d.modify(acc.dn, {"unicodePwd": f'"{password}"'.encode("utf-16-le"), "pwdLastSet": "0"})
        out += [
            f"Set-ADAccountPassword -Identity {acc.sam} -Reset: OK",
            f"Set-ADUser -Identity {acc.sam} -ChangePasswordAtLogon $true (pwdLastSet = 0): OK",
            "Временный пароль показан в приложении один раз и в журнал не записан.",
        ]
        if acc.locked:
            out.append("Внимание: учётная запись заблокирована, войти с новым паролем не получится до разблокировки.")
        if not acc.enabled:
            out.append("Внимание: учётная запись отключена.")
        return RunResult(0, "\n".join(out), secret=password)

    def _ad_unlock_account(self, login: Login, account: str) -> RunResult:
        d = self._directory(login)
        acc = self._find_user(d, account)
        if isinstance(acc, RunResult):
            return acc
        out = self._describe(acc)
        if not acc.locked:
            return RunResult(0, "\n".join([*out, "Учётная запись не заблокирована, изменений нет."]))
        if not self.writes_enabled:
            return self._blocked(out, "снять блокировку (lockoutTime = 0)")
        d.modify(acc.dn, {"lockoutTime": "0"})
        return RunResult(0, "\n".join([*out, f"Unlock-ADAccount -Identity {acc.sam} (lockoutTime = 0): OK"]))

    def _ad_set_enabled(self, login: Login, account: str, action: str) -> RunResult:
        d = self._directory(login)
        acc = self._find_user(d, account)
        if isinstance(acc, RunResult):
            return acc
        out = self._describe(acc)
        enable = action == "enable"
        if acc.enabled == enable:
            state = "включена" if enable else "отключена"
            return RunResult(0, "\n".join([*out, f"Учётная запись уже {state}, изменений нет."]))
        if (refused := self._guard_protected(acc, out)) is not None:
            return refused
        cmd = "Enable-ADAccount" if enable else "Disable-ADAccount"
        if not self.writes_enabled:
            return self._blocked(out, cmd)
        uac = acc.uac & ~UF_ACCOUNTDISABLE if enable else acc.uac | UF_ACCOUNTDISABLE
        d.modify(acc.dn, {"userAccountControl": str(uac)})
        return RunResult(0, "\n".join([*out, f"{cmd} -Identity {acc.sam}: OK"]))

    # --- file servers ---

    def _fs_effective_access(self, login: Login, principal: str, path: str) -> RunResult:
        m = UNC.match(path.strip())
        if not m:
            return RunResult(1, "", rf"Нужен сетевой путь вида \\сервер\папка, получено: {path}")
        host = m["host"]
        if not file_server_allowed(host, self.file_servers):
            allowed = ", ".join(self.file_servers) or "список пуст"
            return RunResult(
                1, "", f"Сервер {host} не в RUNBOOK_FILE_SERVERS ({allowed}): учётная запись отправляется только "
                "на перечисленные файловые серверы."
            )  # fmt: skip
        d = self._directory(login)
        sam = bare_sam(principal)
        if not SAM.match(sam):
            return RunResult(1, "", f"Недопустимое имя: {principal}")
        who = d.find(sam, groups_too=True)
        if who is None:
            return RunResult(1, "", f"Get-ADObject : Cannot find user or group '{sam}'.")
        sids = {who.sid, *d.token_sids(who.dn)}
        if not who.is_group:
            sids.update(USER_WELL_KNOWN)

        assert self.shell_factory is not None
        script = ACL_SCRIPT.format(path=base64.b64encode(path.strip().encode()).decode())
        code, stdout, stderr = self.shell_factory(login)(host, script)
        if code != 0:
            return RunResult(1, "", f"Get-Acl на {host}: {stderr.strip() or 'ошибка'}")
        try:
            aces = json.loads(stdout or "[]")
        except ValueError:
            return RunResult(1, "", f"Get-Acl на {host}: непонятный ответ")

        effective = level(effective_mask(aces, sids))
        lines = [
            f"Путь: {path.strip()}",
            f"Субъект: {who.sam} ({'группа' if who.is_group else who.name or 'пользователь'})",
            f"Групп в токене: {len(sids)}",
            "ACL:",
        ]
        for a in aces:
            mark = "*" if a.get("sid") in sids else " "
            kind = "ALLOW" if a.get("allow") else "DENY "
            inh = " (насл.)" if a.get("inherited") else ""
            lines.append(f" {mark}{kind}  {a.get('name', a.get('sid'))}  0x{int(a['rights']):X}{inh}")
        if effective is None:
            lines.append("Эффективные права: нет доступа")
        else:
            lines.append(f"Эффективные права: {effective} ({RIGHTS_RU[effective]})")
        lines.append(
            "* записи, которые относятся к субъекту. Права общего ресурса (Share) и локальные группы сервера, "
            "кроме Users, не учтены."
        )
        return RunResult(0, "\n".join(lines))


# --- real backends -------------------------------------------------------------------------


class LdapDirectory:
    """Active Directory over ldap3. Opened per run and closed by the garbage collector."""

    def __init__(self, settings: Settings, login: Login, connection: Any = None) -> None:
        import ldap3

        self._ldap3 = ldap3
        self.settings = settings
        if connection is None:
            connection = self._connect(login)
        self.conn = connection
        self.base_dn = settings.ad_base_dn.strip() or self._default_naming_context()

    def _connect(self, login: Login):
        ldap3 = self._ldap3
        s = self.settings
        url = urlparse(s.ad_ldap_url.strip())
        if url.scheme not in ("ldap", "ldaps") or not url.hostname:
            raise RunbookConfigError("AD_LDAP_URL не задан: например ldaps://dc01.factory.local")
        if url.scheme == "ldap" and not s.ad_start_tls:
            raise RunbookConfigError(
                "AD_LDAP_URL без TLS: нужен ldaps:// или AD_START_TLS=true, пароль не отправляется открытым текстом."
            )
        tls = ldap3.Tls(
            validate=ssl.CERT_REQUIRED, ca_certs_file=s.ad_ca_file or None, ca_certs_data=s.ad_ca_pem.strip() or None
        )
        server = ldap3.Server(
            url.hostname,
            port=url.port or (636 if url.scheme == "ldaps" else 389),
            use_ssl=url.scheme == "ldaps",
            tls=tls,
            get_info=ldap3.DSA,
            connect_timeout=5,
        )
        bind = ldap3.AUTO_BIND_TLS_BEFORE_BIND if url.scheme == "ldap" else ldap3.AUTO_BIND_NO_TLS
        try:
            return ldap3.Connection(
                server, user=login.username, password=login.password, authentication=ldap3.SIMPLE,
                auto_bind=bind, receive_timeout=15, raise_exceptions=False,
            )  # fmt: skip
        except ldap3.core.exceptions.LDAPBindError:
            raise RunbookConfigError("AD отклонил вход RUNBOOK_CREDENTIAL: проверьте логин и пароль.") from None
        except ldap3.core.exceptions.LDAPException as e:
            raise RunbookConfigError(f"Нет связи с AD ({url.hostname}): {e}") from None

    def _default_naming_context(self) -> str:
        info = getattr(self.conn.server, "info", None)
        ctx = (info.other.get("defaultNamingContext") or [""])[0] if info and info.other else ""
        if not ctx:
            raise RunbookConfigError("Не удалось узнать корень домена: задайте AD_BASE_DN (DC=factory,DC=local).")
        return ctx

    def _search(self, base: str, flt: str, attrs: list[str], scope: str = "SUBTREE") -> list[dict]:
        self.conn.search(base, flt, search_scope=scope, attributes=attrs, size_limit=2)
        rows = self.conn.response or []
        return [r for r in rows if r.get("type", "searchResEntry") == "searchResEntry" and r.get("dn")]

    def find(self, sam: str, groups_too: bool = False) -> Principal | None:
        esc = self._ldap3.utils.conv.escape_filter_chars(sam)
        user = f"(&(objectCategory=person)(objectClass=user)(sAMAccountName={esc}))"
        rows = self._search(self.base_dn, user, USER_ATTRS)
        if rows:
            return principal_from_raw(rows[0]["dn"], rows[0]["raw_attributes"], is_group=False)
        if groups_too:
            group = f"(&(objectClass=group)(sAMAccountName={esc}))"
            rows = self._search(self.base_dn, group, ["sAMAccountName", "objectSid"])
            if rows:
                return principal_from_raw(rows[0]["dn"], rows[0]["raw_attributes"], is_group=True)
        return None

    def token_sids(self, dn: str) -> set[str]:
        # tokenGroups: every group the object is in, nested ones included; readable on a base search only.
        rows = self._search(dn, "(objectClass=*)", ["tokenGroups"], scope="BASE")
        raw = rows[0]["raw_attributes"].get("tokenGroups", []) if rows else []
        return {sid_to_str(v) for v in raw}

    def modify(self, dn: str, changes: dict[str, Any]) -> None:
        replace = self._ldap3.MODIFY_REPLACE
        ok = self.conn.modify(dn, {k: [(replace, [v])] for k, v in changes.items()})
        if not ok:
            r = self.conn.result or {}
            raise RunbookConfigError(f"AD отказал в изменении: {r.get('description')} {r.get('message', '')}".strip())


def winrm_shell(settings: Settings, login: Login) -> Shell:
    def run(host: str, script: str) -> tuple[int, str, str]:
        import winrm

        https = settings.winrm_https
        port = settings.winrm_port or (5986 if https else 5985)
        # NTLM over HTTP still encrypts the messages; HTTPS checks the server certificate.
        session = winrm.Session(
            f"{'https' if https else 'http'}://{host}:{port}/wsman",
            auth=(login.username, login.password),
            transport="ntlm",
            server_cert_validation="validate",
            message_encryption="auto" if https else "always",
            operation_timeout_sec=20,
            read_timeout_sec=30,
        )
        try:
            r = session.run_ps(script)
        except Exception as e:  # network, auth: one readable line, never the login
            return 1, "", f"нет связи по WinRM: {type(e).__name__}: {e}"
        return r.status_code, r.std_out.decode("utf-8", "replace"), r.std_err.decode("utf-8", "replace")

    return run
