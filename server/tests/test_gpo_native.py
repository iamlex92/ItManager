"""Group Policy straight over LDAP and SYSVOL (Samba AD on Astra Linux, or Windows), against a fake domain."""

import struct
import uuid

import pytest

from app.config import RunbookMode
from app.gpo.backend import GpoError
from app.gpo.native import (
    APPLY_GROUP_POLICY,
    CSE_REGISTRY,
    NativeGpo,
    PolEntry,
    add_extension,
    apply_trustees,
    build_gplink,
    build_pol,
    canonical,
    decode_value,
    encode_value,
    parse_gplink,
    parse_pol,
)
from app.runbooks.real import Login

from .conftest import make_settings

BASE = "DC=ecotehs,DC=local"
GID = "6b1a3f5e-2d4c-4b8a-9e7f-0a1b2c3d4e5f"
GPO_DN = f"CN={{{GID.upper()}}},CN=Policies,CN=System,{BASE}"
OU = f"OU=Бухгалтерия,OU=Компьютеры,{BASE}"


def _sid(text: str) -> bytes:
    parts = text.split("-")
    subs = [int(x) for x in parts[3:]]
    return (
        bytes([int(parts[1]), len(subs)]) + int(parts[2]).to_bytes(6, "big") + struct.pack("<" + "I" * len(subs), *subs)
    )


def _sd(*sids: str) -> bytes:
    aces = b""
    for s in sids:
        body = struct.pack("<II", 0x100 | 0x20094, 1) + APPLY_GROUP_POLICY.bytes_le + _sid(s)
        aces += bytes([0x05, 0x00]) + struct.pack("<H", 4 + len(body)) + body
    acl = bytes([4, 0]) + struct.pack("<HHH", 8 + len(aces), len(sids), 0) + aces
    return bytes([1, 0]) + struct.pack("<HIIII", 0x8004, 0, 0, 0, 20) + acl


class FakeDomain:
    def __init__(self, apply_to: tuple[str, ...] = ("S-1-5-11",)) -> None:
        self.base_dn = BASE
        self.objects = {
            GPO_DN.lower(): {
                "dn": GPO_DN,
                "attrs": {
                    "objectClass": ["groupPolicyContainer"],
                    "cn": [f"{{{GID.upper()}}}"],
                    "displayName": ["Бухгалтерия"],
                    "flags": ["0"],
                    "versionNumber": ["65538"],
                    "whenChanged": ["20261029083000.0Z"],
                    "gPCFileSysPath": [f"\\\\ecotehs.local\\SysVol\\ecotehs.local\\Policies\\{{{GID.upper()}}}"],
                },
                "raw": {"nTSecurityDescriptor": [_sd(*apply_to)]},
            },
            BASE.lower(): {"dn": BASE, "attrs": {"objectClass": ["domain"]}, "raw": {}},
            f"OU=Компьютеры,{BASE}".lower(): {
                "dn": f"OU=Компьютеры,{BASE}",
                "attrs": {"objectClass": ["organizationalUnit"]},
                "raw": {},
            },
            OU.lower(): {
                "dn": OU,
                "attrs": {"objectClass": ["organizationalUnit"], "gPLink": [f"[LDAP://{GPO_DN};0]"]},
                "raw": {},
            },
            f"CN=PC-BUH-01,{OU}".lower(): {
                "dn": f"CN=PC-BUH-01,{OU}",
                "attrs": {
                    "objectClass": ["computer"],
                    "name": ["PC-BUH-01"],
                    "dNSHostName": ["pc-buh-01.ecotehs.local"],
                    "userAccountControl": ["4096"],
                },
                "raw": {"objectSid": [_sid("S-1-5-21-1-2-3-1105")]},
            },
            f"CN=Buh-PCs,{BASE}".lower(): {
                "dn": f"CN=Buh-PCs,{BASE}",
                "attrs": {"objectClass": ["group"], "sAMAccountName": ["Buh-PCs"]},
                "raw": {"objectSid": [_sid("S-1-5-21-1-2-3-2001")]},
            },
        }
        self.replaced: list[tuple[str, str, list]] = []

    def search(self, base, flt, attrs, sd=False, scope="SUBTREE"):
        base = base.lower()
        out = []
        for key, o in self.objects.items():
            a = o["attrs"]
            under = (
                key == base
                if scope == "BASE"
                else (
                    key.endswith("," + base) and key.count(",") == base.count(",") + 1
                    if scope == "LEVEL"
                    else key == base or key.endswith("," + base)
                )
            )
            if not under:
                continue
            classes = [c.lower() for c in a.get("objectClass", [])]
            if "grouppolicycontainer" in flt.lower() and "grouppolicycontainer" not in classes:
                continue
            if "(cn={" in flt.lower() and flt.lower().split("(cn=")[1].split(")")[0] != a.get("cn", [""])[0].lower():
                continue
            if flt == "(gPLink=*)" and not a.get("gPLink"):
                continue
            if flt == "(objectClass=organizationalUnit)" and "organizationalunit" not in classes:
                continue
            if "objectclass=computer" in flt.lower() and "computer" not in classes:
                continue
            if "memberof" in flt.lower() and "1105" not in self.members:
                continue
            if flt.startswith("(objectSid="):
                sid = flt[len("(objectSid=") : -1]
                raw = o["raw"].get("objectSid", [b""])[0]
                if raw != _sid(sid):
                    continue
            out.append({"dn": o["dn"], "attrs": a, "raw": o["raw"]})
        return out

    members = "1105"

    def replace(self, dn, attr, values):
        self.replaced.append((dn, attr, values))
        a = self.objects[dn.lower()]["attrs"]
        if values:
            a[attr] = [str(v) for v in values]
        else:
            a.pop(attr, None)

    def add(self, dn, object_class, attrs):
        assert dn.lower() not in self.objects
        cn = dn.split(",", 1)[0].split("=", 1)[1]
        a = {"objectClass": [object_class], "cn": [cn], **{k: [str(v)] for k, v in attrs.items()}}
        self.objects[dn.lower()] = {"dn": dn, "attrs": a, "raw": {}}

    def delete(self, dn):
        assert not [k for k in self.objects if k.endswith("," + dn.lower())], "children first"
        del self.objects[dn.lower()]


class FakeSysvol:
    def __init__(self) -> None:
        self.files: dict[str, bytes] = {}

    def read(self, path):
        return self.files.get(path.lower())

    def write(self, path, data):
        self.files[path.lower()] = data

    def mkdir(self, path):
        self.files[path.lower() + "\\"] = b""

    def remove_tree(self, path):
        for k in [k for k in self.files if k.startswith(path.lower() + "\\")]:
            del self.files[k]


def _backend(domain: FakeDomain, sysvol: FakeSysvol, writes: bool = True) -> NativeGpo:
    s = make_settings(
        pc_mode="real",
        runbook_mode=RunbookMode.REAL,
        ad_ldap_url="ldaps://rukrsrvdc01.ecotehs.local",
        allow_runbook_writes=writes,
    )
    return NativeGpo(s, directory_factory=lambda login: domain, sysvol_factory=lambda host, login: sysvol)


LOGIN = Login("ECOTEHS\\administrator", "pw")
FOLDER = f"\\\\rukrsrvdc01.ecotehs.local\\SysVol\\ecotehs.local\\Policies\\{{{GID.upper()}}}".lower()


def test_formats_round_trip():
    entries = [
        PolEntry("Software\\Policies\\X", "A", *encode_value("DWord", 7)),
        PolEntry("Software\\Policies\\X", "B", *encode_value("MultiString", "a\nb")),
        PolEntry("Software\\Policies\\X", "C", *encode_value("String", "текст")),
    ]
    back = parse_pol(build_pol(entries))
    assert [decode_value(e) for e in back] == ["7", "a\nb", "текст"]
    links = parse_gplink(f"[LDAP://{GPO_DN};2][LDAP://cn={{X}},cn=policies;1]")
    assert links[0].enforced and links[0].enabled and not links[1].enabled
    assert build_gplink(links[:1]) == f"[LDAP://{GPO_DN};2]"
    merged = add_extension(
        "[{827D319E-6EAC-11D2-A4EA-00C04F79F83A}{803E14A0-B4FB-11D0-A0D0-00A0C90F574B}]",
        CSE_REGISTRY,
        "{D02B1F72-3407-48AE-BA88-E8213C6761F1}",
    )
    assert merged.startswith("[{35378EAC") and merged.count("[") == 2  # sorted by CSE GUID
    assert apply_trustees(_sd("S-1-5-11", "S-1-5-21-1-2-3-2001")) == ["S-1-5-11", "S-1-5-21-1-2-3-2001"]
    assert canonical(OU) == "ecotehs.local/Компьютеры/Бухгалтерия"
    assert uuid.UUID(str(APPLY_GROUP_POLICY))


async def test_reads_and_edits_like_gpmc():
    domain, sysvol = FakeDomain(), FakeSysvol()
    gpo = _backend(domain, sysvol)
    [g] = await gpo.gpos(LOGIN)
    assert g["id"] == GID and g["name"] == "Бухгалтерия" and g["links"][0]["target_name"].endswith("/Бухгалтерия")
    assert {o["name"] for o in await gpo.ous(LOGIN)} >= {"ecotehs.local", "ecotehs.local/Компьютеры/Бухгалтерия"}

    await gpo.registry_set(
        GID, "HKLM\\Software\\Policies\\Microsoft\\Windows\\Personalization", "NoLockScreen", "DWord", "1", LOGIN
    )
    pol = sysvol.files[f"{FOLDER}\\machine\\registry.pol"]
    assert decode_value(parse_pol(pol)[0]) == "1"
    attrs = domain.objects[GPO_DN.lower()]["attrs"]
    assert attrs["versionNumber"] == ["65539"]  # the computer half went up by one
    assert b"Version=65539" in sysvol.files[f"{FOLDER}\\gpt.ini"]
    assert CSE_REGISTRY in attrs["gPCMachineExtensionNames"][0]

    d = await gpo.detail(GID, LOGIN)
    assert d["registry"][0]["key"] == "HKLM\\Software\\Policies\\Microsoft\\Windows\\Personalization"
    assert d["security_filter"] == [{"name": "Authenticated Users", "type": "WellKnownGroup"}]
    await gpo.registry_remove(
        GID, "HKLM\\Software\\Policies\\Microsoft\\Windows\\Personalization", "NoLockScreen", LOGIN
    )
    assert parse_pol(sysvol.files[f"{FOLDER}\\machine\\registry.pol"]) == []
    with pytest.raises(GpoError):
        await gpo.registry_remove(GID, "HKLM\\Software\\X", "Nope", LOGIN)

    await gpo.set_status(GID, "UserSettingsDisabled", LOGIN)
    assert attrs["flags"] == ["1"]
    await gpo.link_set(GID, OU, None, True, LOGIN)
    assert domain.objects[OU.lower()]["attrs"]["gPLink"] == [f"[LDAP://{GPO_DN};2]"]
    await gpo.link_remove(GID, OU, LOGIN)
    assert "gPLink" not in domain.objects[OU.lower()]["attrs"]
    await gpo.link_add(GID, BASE, False, LOGIN)
    assert domain.objects[BASE.lower()]["attrs"]["gPLink"] == [f"[LDAP://{GPO_DN};0]"]


async def test_creates_and_deletes_like_samba_tool():
    domain, sysvol = FakeDomain(), FakeSysvol()
    gpo = _backend(domain, sysvol)
    with pytest.raises(GpoError, match="уже есть"):
        await gpo.create("бухгалтерия", "", LOGIN)

    gid = await gpo.create("Цех: экран блокировки", "", LOGIN)
    cn = f"{{{gid.upper()}}}"
    dn = f"CN={cn},CN=Policies,CN=System,{BASE}"
    a = domain.objects[dn.lower()]["attrs"]
    assert a["objectClass"] == ["groupPolicyContainer"] and a["displayName"] == ["Цех: экран блокировки"]
    assert a["gPCFileSysPath"] == [f"\\\\ecotehs.local\\SysVol\\ecotehs.local\\Policies\\{cn}"]
    assert {f"cn=user,{dn}".lower(), f"cn=machine,{dn}".lower()} <= domain.objects.keys()
    folder = f"\\\\rukrsrvdc01.ecotehs.local\\SysVol\\ecotehs.local\\Policies\\{cn}".lower()
    assert sysvol.files[f"{folder}\\gpt.ini"] == b"[General]\r\nVersion=0\r\n"

    # It works at once: settings, a link, then gone with its link and folder.
    await gpo.registry_set(gid, "HKCU\\Software\\Policies\\X", "A", "DWord", 1, LOGIN)
    assert (await gpo.detail(gid, LOGIN))["registry"][0]["name"] == "A"
    await gpo.link_add(gid, OU, False, LOGIN)
    await gpo.delete(gid, LOGIN)
    assert not [k for k in domain.objects if cn.lower() in k]
    assert not [k for k in sysvol.files if k.startswith(folder)]
    assert domain.objects[OU.lower()]["attrs"]["gPLink"] == [f"[LDAP://{GPO_DN};0]"]
    assert [g["id"] for g in await gpo.gpos(LOGIN)] == [GID]


async def test_scope_follows_links_and_the_security_filter():
    gpo = _backend(FakeDomain(), FakeSysvol())
    s = await gpo.scope(GID, LOGIN, [])
    assert [c["name"] for c in s["computers"]] == ["PC-BUH-01"] and s["filtered"] is False

    narrowed = FakeDomain(apply_to=("S-1-5-21-1-2-3-2001",))
    s = await _backend(narrowed, FakeSysvol()).scope(GID, LOGIN, [])
    assert s["filtered"] is True and [c["name"] for c in s["computers"]] == ["PC-BUH-01"]
    narrowed.members = ""
    assert (await _backend(narrowed, FakeSysvol()).scope(GID, LOGIN, []))["computers"] == []


def test_smb_sysvol_reads_a_missing_file_as_empty():
    """A policy with no user settings has no User\\Registry.pol; smbprotocol reports it as SMBOSError, not
    FileNotFoundError, and opening the policy must not fail on it."""
    from smbprotocol.exceptions import SMBOSError
    from smbprotocol.header import NtStatus

    from app.gpo.native import SmbSysvol

    class Smb:
        def __init__(self, status):
            self.status = status

        def open_file(self, path, mode):
            raise SMBOSError(self.status, path)

    sysvol = SmbSysvol.__new__(SmbSysvol)
    for status in (NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND):
        sysvol._smb = Smb(status)
        assert sysvol.read(r"\\dc\SysVol\d\Policies\{X}\User\Registry.pol") is None
    sysvol._smb = Smb(NtStatus.STATUS_ACCESS_DENIED)
    with pytest.raises(GpoError):
        sysvol.read(r"\\dc\SysVol\d\Policies\{X}\User\Registry.pol")
