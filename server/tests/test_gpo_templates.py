"""Ready-made settings («Шаблоны»): what gpedit would write, read back, and changed without touching other values."""

from app.gpo.native import parse_pol
from app.gpo.templates import FIREWALL, RA_HELPERS, TEMPLATES, TS, describe, plan

from .test_gpo_native import FOLDER, GID, LOGIN, FakeDomain, FakeSysvol, _backend

BUH = "5c3e0f0a-8a3b-4b8e-9d1e-0b6f4a1e2c01"


def _state(gpo_rows, tid):
    d = describe(TEMPLATES[tid], gpo_rows)
    return d["state"], d["values"]


async def test_remote_assistance_like_gpedit_straight_over_sysvol():
    domain, sysvol = FakeDomain(), FakeSysvol()
    gpo = _backend(domain, sysvol)
    rows = (await gpo.detail(GID, LOGIN))["registry"]
    assert _state(rows, "ra_offer")[0] == "not_configured"

    values = {"control": True, "helpers": "ECOTEHS\\Domain Admins\nECOTEHS\\kharca01\necotehs\\KHARCA01\n"}
    await gpo.registry_batch(GID, plan(TEMPLATES["ra_offer"], "enabled", values, rows), LOGIN)
    rows = (await gpo.detail(GID, LOGIN))["registry"]
    state, read = _state(rows, "ra_offer")
    assert state == "enabled" and read == {"control": True, "helpers": ["ECOTEHS\\Domain Admins", "ECOTEHS\\kharca01"]}
    pol = {(e.key, e.name): e for e in parse_pol(sysvol.files[f"{FOLDER}\\machine\\registry.pol"])}
    rel = TS[5:]
    assert pol[(rel, "fAllowUnsolicited")].data == (1).to_bytes(4, "little")
    helper = pol[(RA_HELPERS[5:], "ECOTEHS\\kharca01")]
    assert helper.data.decode("utf-16-le").rstrip("\0") == "ECOTEHS\\kharca01"  # name = value, as gpedit writes it
    # One write and one version for the whole template, not one per value.
    assert domain.objects[f"cn={{{GID}}},cn=policies,cn=system,dc=ecotehs,dc=local"]["attrs"]["versionNumber"] == [
        "65539"
    ]

    # Taking a helper off the list removes its value; other settings of the GPO stay.
    await gpo.registry_set(GID, "HKLM\\Software\\Policies\\Other", "Keep", "DWord", 1, LOGIN)
    rows = (await gpo.detail(GID, LOGIN))["registry"]
    ops = plan(TEMPLATES["ra_offer"], "enabled", {"control": False, "helpers": ["ECOTEHS\\kharca01"]}, rows)
    assert ("remove", RA_HELPERS, "ECOTEHS\\Domain Admins") in ops
    await gpo.registry_batch(GID, ops, LOGIN)
    rows = (await gpo.detail(GID, LOGIN))["registry"]
    assert _state(rows, "ra_offer") == ("enabled", {"control": False, "helpers": ["ECOTEHS\\kharca01"]})

    await gpo.registry_batch(GID, plan(TEMPLATES["ra_offer"], "not_configured", {}, rows), LOGIN)
    rows = (await gpo.detail(GID, LOGIN))["registry"]
    assert [r["name"] for r in rows] == ["Keep"]


def test_every_template_round_trips_through_its_own_reading():
    for t in TEMPLATES.values():
        states = ["enabled", "not_configured"] + (["disabled"] if t.disable else [])
        defaults = {f.id: f.default for f in t.fields}
        if t.id == "ra_offer":
            defaults["helpers"] = ["ECOTEHS\\kharca01"]
        for state in states:
            rows = [
                {"key": op[1], "name": op[2], "type": op[3], "value": str(op[4]), "state": "Set"}
                for op in plan(t, state, defaults, [])
                if op[0] == "set"
            ]
            got, values = describe(t, rows)["state"], describe(t, rows)["values"]
            assert got == state, (t.id, state)
            if state == "enabled":
                assert values == {**defaults, **{k: values[k] for k in values if isinstance(defaults[k], list)}}, t.id
            # Applying the same thing again changes nothing.
            assert [op for op in plan(t, state, defaults, rows) if op[0] == "remove"] == [], (t.id, state)


def test_firewall_rules_as_gpmc_stores_them():
    t = TEMPLATES["firewall_in"]
    ops = plan(t, "enabled", {"ra": True, "rdp": True, "sources": "192.168.82.0/24, 10.0.0.5"}, [])
    rules = {op[2]: op[4] for op in ops}
    assert all(op[1] == FIREWALL for op in ops) and len(rules) == 5
    assert rules["NetAdmin-RA-DCOM-In"] == (
        "v2.10|Action=Allow|Active=TRUE|Dir=In|Protocol=6|Profile=Domain|LPort=135|RA4=192.168.82.0/255.255.255.0|"
        "RA4=10.0.0.5|App=%SystemRoot%\\system32\\svchost.exe|Svc=RpcSs|Name=Удалённый помощник (DCOM, входящие)|"
    )
    rows = [{"key": FIREWALL, "name": n, "type": "String", "value": v, "state": "Set"} for n, v in rules.items()]
    rows.append({"key": FIREWALL, "name": "{someone-else}", "type": "String", "value": "v2.10|", "state": "Set"})
    assert describe(t, rows)["values"] == {
        "ra": True,
        "rdp": True,
        "winrm": False,
        "sources": "192.168.82.0/24, 10.0.0.5",
    }
    # Dropping RDP removes only its two rules; a rule the template does not own stays.
    ops = plan(t, "enabled", {"ra": True, "rdp": False, "sources": "192.168.82.0/24, 10.0.0.5"}, rows)
    assert sorted(op[2] for op in ops if op[0] == "remove") == ["NetAdmin-RDP-TCP-In", "NetAdmin-RDP-UDP-In"]


def test_templates_through_the_api(client):
    listed = client.get("/api/v1/gpo/templates").json()
    assert {t["id"] for t in listed} >= {"ra_solicit", "ra_offer", "firewall_in", "windows_update"}
    assert all(t["state"] is None for t in listed)

    body = {"state": "enabled", "values": {"control": True, "helpers": "ECOTEHS\\kharca01"}}
    assert client.post(f"/api/v1/gpo/{BUH}/templates/ra_offer", json=body).status_code == 400  # not confirmed
    d = client.post(f"/api/v1/gpo/{BUH}/templates/ra_offer", json={**body, "confirm": True}).json()
    assert {(r["key"], r["name"]) for r in d["registry"]} >= {
        (TS, "fAllowUnsolicited"),
        (RA_HELPERS, "ECOTEHS\\kharca01"),
    }
    mine = {t["id"]: t for t in client.get(f"/api/v1/gpo/templates?gpo={BUH}").json()}
    assert mine["ra_offer"]["state"] == "enabled" and mine["ra_offer"]["values"]["helpers"] == ["ECOTEHS\\kharca01"]
    assert mine["ra_solicit"]["state"] == "not_configured"

    empty = client.post(
        f"/api/v1/gpo/{BUH}/templates/ra_offer", json={"state": "enabled", "values": {"helpers": ""}, "confirm": True}
    )
    assert empty.status_code == 422 and "помощника" in empty.json()["detail"]["message"]
    no_off = client.post(f"/api/v1/gpo/{BUH}/templates/autoplay_off", json={"state": "disabled", "confirm": True})
    assert no_off.status_code == 422
    assert (
        client.post(f"/api/v1/gpo/{BUH}/templates/nope", json={"state": "enabled", "confirm": True}).status_code == 422
    )

    audit = client.get("/api/v1/audit", params={"kind": "runbook"}).json()
    entry = next(a for a in audit if a["action"] == "gpo:template")
    assert entry["title"] == "GPO: шаблон" and "kharca01" in str(entry["parameters"])


def test_uac_prompts_reach_the_helper():
    t = TEMPLATES["ra_uac"]
    system = "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System"
    assert plan(t, "enabled", {}, []) == [("set", system, "EnableUIADesktopToggle", "DWord", 1)]
    both = plan(t, "enabled", {"uia_toggle": True, "no_secure_desktop": True}, [])
    assert ("set", system, "PromptOnSecureDesktop", "DWord", 0) in both
    rows = [{"key": op[1], "name": op[2], "type": "DWord", "value": str(op[4]), "state": "Set"} for op in both]
    assert describe(t, rows)["values"] == {"uia_toggle": True, "no_secure_desktop": True}
    # Back to the remote-assistance-only setting: the secure desktop comes back for everything else.
    assert ("remove", system, "PromptOnSecureDesktop") in plan(t, "enabled", {"no_secure_desktop": False}, rows)
