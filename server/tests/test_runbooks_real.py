"""Real-mode runbooks against an in-memory LDAP (ldap3 MOCK_SYNC) and a fake WinRM shell."""

import json
import struct

import pytest
from fastapi.testclient import TestClient
from ldap3 import MOCK_SYNC, OFFLINE_AD_2012_R2, Connection, Server

from app.config import RunbookMode
from app.main import create_app
from app.runbooks import real
from app.runbooks.real import LdapDirectory, Login, RealRunbooks

from .conftest import AUTH, make_settings

BASE = "DC=factory,DC=local"
DOMAIN_SID = "S-1-5-21-100-200-300"
SVC = "CN=svc-netadmin,OU=Service,DC=factory,DC=local"


def sid(text: str) -> bytes:
    parts = text.split("-")
    subs = [int(x) for x in parts[3:]]
    return bytes([int(parts[1]), len(subs)]) + int(parts[2]).to_bytes(6, "big") + struct.pack(f"<{len(subs)}I", *subs)


def user(sam, rid, *, name="", dept="", uac=512, locked=False, admin=False, groups=()):
    attrs = {
        "objectClass": ["top", "person", "organizationalPerson", "user"],
        "objectCategory": "person",
        "sAMAccountName": sam,
        "displayName": name,
        "department": dept,
        "userAccountControl": uac,
        "msDS-User-Account-Control-Computed": 16 if locked else 0,
        "lockoutTime": 1 if locked else 0,
        "objectSid": sid(f"{DOMAIN_SID}-{rid}"),
        "tokenGroups": [sid(f"{DOMAIN_SID}-513"), *(sid(f"{DOMAIN_SID}-{g}") for g in groups)],
    }
    if admin:
        attrs["adminCount"] = 1
    return attrs


class Mock:
    """One in-memory directory shared by every run, so a change is visible to the next run."""

    def __init__(self):
        server = Server("dc01", get_info=OFFLINE_AD_2012_R2)
        self.conn = Connection(server, user=SVC, password="pw", client_strategy=MOCK_SYNC)
        s = self.conn.strategy
        s.add_entry(SVC, {"userPassword": "pw", "sAMAccountName": "svc-netadmin"})
        ivanov = user("i.ivanov", 1105, name="Иванов Иван", dept="Бухгалтерия", locked=True, groups=(2001,))
        s.add_entry(f"CN=Ivanov,{BASE}", ivanov)
        s.add_entry(f"CN=Sidorov,{BASE}", user("s.sidorov", 1106, uac=514, groups=(2003,)))
        s.add_entry(f"CN=Admin,{BASE}", user("a.kuznetsov", 1107, admin=True, groups=(512,)))
        s.add_entry(
            f"CN=GG_Buh_RW,{BASE}",
            {"objectClass": ["top", "group"], "sAMAccountName": "GG_Buh_RW", "objectSid": sid(f"{DOMAIN_SID}-2001")},
        )
        self.conn.bind()
        self.shell_calls: list[tuple[str, str]] = []

    def directory(self, settings):
        return lambda login: LdapDirectory(settings, login, connection=self.conn)

    def attr(self, dn: str, name: str):
        self.conn.search(dn, "(objectClass=*)", search_scope="BASE", attributes=[name])
        return self.conn.response[0]["raw_attributes"].get(name)

    def shell(self, aces):
        def run(host, script):
            self.shell_calls.append((host, script))
            return 0, json.dumps(aces), ""

        return lambda login: run


def env(mock: Mock, aces=(), **kw) -> RealRunbooks:
    settings = make_settings(runbook_mode=RunbookMode.REAL, ad_base_dn=BASE, runbook_file_servers="fs01, fs02", **kw)
    return RealRunbooks(settings, directory_factory=mock.directory(settings), shell_factory=mock.shell(list(aces)))


LOGIN = Login("svc-netadmin@factory.local", "pw")


@pytest.fixture
def mock():
    return Mock()


async def test_writes_are_off_by_default(mock):
    e = env(mock)
    r = await e.run("ad_unlock_account", {"account": "FACTORY\\i.ivanov"}, LOGIN)
    assert r.exit_code == 2 and "ALLOW_RUNBOOK_WRITES=false" in r.stderr
    assert "заблокирована" in r.stdout and "Иванов Иван" in r.stdout
    assert mock.attr(f"CN=Ivanov,{BASE}", "lockoutTime") == [b"1"]
    r = await e.run("ad_reset_password", {"account": "i.ivanov"}, LOGIN)
    assert r.exit_code == 2 and r.secret is None and not mock.attr(f"CN=Ivanov,{BASE}", "unicodePwd")


async def test_unlock_reset_and_disable_with_writes(mock):
    e = env(mock, allow_runbook_writes=True)
    r = await e.run("ad_unlock_account", {"account": "i.ivanov"}, LOGIN)
    assert r.exit_code == 0 and "Unlock-ADAccount" in r.stdout
    assert mock.attr(f"CN=Ivanov,{BASE}", "lockoutTime") == [b"0"]

    r = await e.run("ad_reset_password", {"account": "i.ivanov@factory.local"}, LOGIN)
    assert r.exit_code == 0 and r.secret and r.secret not in r.stdout
    assert mock.attr(f"CN=Ivanov,{BASE}", "unicodePwd") == [f'"{r.secret}"'.encode("utf-16-le")]
    assert mock.attr(f"CN=Ivanov,{BASE}", "pwdLastSet") == [b"0"]

    r = await e.run("ad_set_enabled", {"account": "i.ivanov", "action": "disable"}, LOGIN)
    assert r.exit_code == 0 and mock.attr(f"CN=Ivanov,{BASE}", "userAccountControl") == [b"514"]
    r = await e.run("ad_set_enabled", {"account": "s.sidorov", "action": "disable"}, LOGIN)
    assert r.exit_code == 0 and "уже отключена" in r.stdout


async def test_protected_accounts_are_not_changed(mock):
    e = env(mock, allow_runbook_writes=True)
    for rb, params in (("ad_reset_password", {}), ("ad_set_enabled", {"action": "disable"})):
        r = await e.run(rb, {"account": "a.kuznetsov", **params}, LOGIN)
        assert r.exit_code == 1 and "adminCount=1" in r.stderr
    assert not mock.attr(f"CN=Admin,{BASE}", "unicodePwd")


async def test_unknown_account_and_injection(mock):
    e = env(mock, allow_runbook_writes=True)
    r = await e.run("ad_unlock_account", {"account": "nobody"}, LOGIN)
    assert r.exit_code == 1 and "Cannot find" in r.stderr
    r = await e.run("ad_unlock_account", {"account": "*)(sAMAccountName=*"}, LOGIN)
    assert r.exit_code == 1 and "Недопустимое" in r.stderr


async def test_no_login_and_infrastructure_runbooks(mock):
    e = env(mock)
    r = await e.run("ad_unlock_account", {"account": "i.ivanov"}, None)
    assert r.exit_code == 1 and "RUNBOOK_CREDENTIAL" in r.stderr
    r = await e.run("vm_snapshot", {"vm": "vm-mes"}, LOGIN)
    assert r.exit_code == 1 and "эмулятор" in r.stderr


BUH_ACES = [
    {"sid": "S-1-5-32-544", "name": "BUILTIN\\Administrators", "rights": 0x1F01FF, "allow": True, "inherited": True},
    {"sid": f"{DOMAIN_SID}-2001", "name": "FACTORY\\GG_Buh_RW", "rights": 0x1301BF, "allow": True, "inherited": False},
    {"sid": "S-1-5-32-545", "name": "BUILTIN\\Users", "rights": 0x1200A9, "allow": True, "inherited": True},
    # Applies to subfolders only: must not count for the folder itself.
    {"sid": f"{DOMAIN_SID}-513", "name": "FACTORY\\Domain Users", "rights": 0x1F01FF, "allow": True, "propagation": 2},
]


async def test_folder_rights_through_group_membership(mock):
    e = env(mock, aces=BUH_ACES)
    r = await e.run("fs_effective_access", {"principal": "i.ivanov", "path": r"\\fs01\Бухгалтерия"}, LOGIN)
    assert r.exit_code == 0, r.stderr
    assert "Эффективные права: Modify" in r.stdout
    host, script = mock.shell_calls[-1]
    assert host == "fs01" and "Бухгалтерия" not in script  # path goes base64-encoded, never into the script text
    r = await e.run("fs_effective_access", {"principal": "s.sidorov", "path": r"\\fs01\Бухгалтерия"}, LOGIN)
    assert "Эффективные права: ReadAndExecute" in r.stdout  # BUILTIN\Users only
    r = await e.run("fs_effective_access", {"principal": "GG_Buh_RW", "path": r"\\fs01\Бухгалтерия"}, LOGIN)
    assert "Эффективные права: Modify" in r.stdout


async def test_folder_rights_only_on_listed_servers(mock):
    e = env(mock, aces=BUH_ACES)
    r = await e.run("fs_effective_access", {"principal": "i.ivanov", "path": r"\\evil-host\share"}, LOGIN)
    assert r.exit_code == 1 and "RUNBOOK_FILE_SERVERS" in r.stderr and not mock.shell_calls
    r = await e.run("fs_effective_access", {"principal": "i.ivanov", "path": "C:\\Windows"}, LOGIN)
    assert r.exit_code == 1 and not mock.shell_calls
    fqdn = r"\\FS01.factory.local\Бухгалтерия"
    r = await e.run("fs_effective_access", {"principal": "i.ivanov", "path": fqdn}, LOGIN)
    assert r.exit_code == 0


def test_explicit_allow_beats_inherited_deny_and_deny_first():
    sids = {"A"}
    aces = [
        {"sid": "A", "rights": 0x1301BF, "allow": True, "inherited": False},
        {"sid": "A", "rights": 0x1F01FF, "allow": False, "inherited": True},
    ]
    assert real.level(real.effective_mask(aces, sids)) == "Modify"
    aces = [
        {"sid": "A", "rights": 0x10000, "allow": False},  # DELETE denied
        {"sid": "A", "rights": 0x1F01FF, "allow": True},
    ]
    assert real.level(real.effective_mask(aces, sids)) == "ReadAndExecute"
    assert real.level(real.effective_mask([{"sid": "A", "rights": 0x10000000, "allow": True}], sids)) == "FullControl"


def test_plain_ldap_is_refused():
    settings = make_settings(runbook_mode=RunbookMode.REAL, ad_ldap_url="ldap://dc01.factory.local")
    with pytest.raises(real.RunbookConfigError, match="TLS"):
        LdapDirectory(settings, LOGIN)


def test_api_in_real_mode(seeded):
    settings = make_settings(runbook_mode=RunbookMode.REAL, runbook_credential="ad-runbooks")
    with TestClient(create_app(settings), headers=AUTH) as c:
        health = c.get("/api/v1/health").json()
        assert health["runbook_mode"] == "real" and health["runbook_writes_enabled"] is False
        ids = {rb["id"] for rb in c.get("/api/v1/runbooks").json()}
        assert ids == {"ad_reset_password", "ad_unlock_account", "ad_set_enabled", "fs_effective_access"}
        body = {"params": {"vm": "vm-mes"}, "confirm": True}
        assert c.post("/api/v1/runbooks/vm_snapshot/run", json=body).status_code == 404
        # No such stored credential yet: a clear message, audited as a real (not simulated) run.
        r = c.post("/api/v1/runbooks/ad_unlock_account/run", json={"params": {"account": "i.ivanov"}})
        assert r.status_code == 200 and r.json()["exit_code"] == 1 and "RUNBOOK_CREDENTIAL" in r.json()["stderr"]
        audit = c.get("/api/v1/audit", params={"kind": "runbook"}).json()
        assert audit[0]["parameters"]["simulated"] is False
