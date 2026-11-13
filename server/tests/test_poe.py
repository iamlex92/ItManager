"""PoE: reading the budget and the per-port power from `show power inline` (Cisco IOS and Eltex MES), and the API."""

from app.hal.poe import level_of, parse_report

CISCO_NEW = """Available:370.0(w)  Used:123.4(w)  Remaining:246.6(w)

Interface Admin  Oper       Power   Device              Class Max
                            (Watts)
--------- ------ ---------- ------- ------------------- ----- ----
Gi1/0/1   auto   on         15.4    AIR-AP2802I-R-K9    4     30.0
Gi1/0/2   auto   off        0.0     n/a                 n/a   30.0
Gi1/0/3   auto   on         6.3     Ieee PD             2     30.0
Gi1/0/4   off    off        0.0     n/a                 n/a   30.0
Gi1/0/5   auto   faulty     0.0     n/a                 n/a   30.0
"""

CISCO_TABLE = """Module   Available     Used     Remaining
          (Watts)     (Watts)    (Watts)
------   ---------   --------   ---------
1           370.0       100.0       270.0
2           370.0        50.0       320.0

Interface Admin  Oper       Power   Device              Class Max
--------- ------ ---------- ------- ------------------- ----- ----
Gi1/0/1   auto   on         15.4    Cisco IP Phone 7960 3     30.0
"""

ELTEX = """Power:  On
Nominal Power: 150 Watts
Consumed Power: 20 Watts (13%)
Usage Threshold: 95 Percent
Traps: Enabled

Port      Powered Device    State     Status    Priority  Class
--------  ----------------  --------  --------  --------  ------
gi1/0/1   IP camera         Auto      On        Low       Class3
gi1/0/2                     Auto      Off       Low       Class0
gi1/0/3                     Never     Off       Low       Class0
"""


def test_cisco_budget_and_ports():
    r = parse_report("CISCO", CISCO_NEW)
    assert (r.budget_w, r.used_w, r.percent) == (370.0, 123.4, 33)
    assert len(r.ports) == 5
    ap = next(p for p in r.ports if p.power_w == 15.4)
    assert ap.state == "on" and ap.device == "AIR-AP2802I-R-K9" and ap.max_w == 30.0
    assert sum(1 for p in r.ports if p.state == "fault") == 1
    assert sum(1 for p in r.ports if p.admin == "off") == 1


def test_cisco_module_table_is_summed():
    r = parse_report("CISCO", CISCO_TABLE)
    assert (r.budget_w, r.used_w) == (740.0, 150.0)
    assert r.ports[0].device == "Cisco IP Phone 7960"


def test_eltex_budget_and_ports():
    r = parse_report("ELTEX", ELTEX)
    assert (r.budget_w, r.used_w, r.percent) == (150.0, 20.0, 13)
    assert [p.state for p in r.ports] == ["on", "off", "off"]
    assert r.ports[0].device == "IP camera" and r.ports[2].admin == "never"


def test_nothing_known_is_not_an_error():
    r = parse_report("CISCO", "")
    assert (r.budget_w, r.used_w, r.percent, r.ports) == (None, None, None, ())
    assert level_of(None) == "unknown"
    assert [level_of(p) for p in (10, 75, 89, 90, 120)] == ["ok", "warn", "warn", "critical", "critical"]


def test_poe_api_with_the_emulator(client):
    fleet = client.get("/api/v1/poe").json()
    assert fleet["rows"], "the emulated switches with PoE ports are listed"
    top = fleet["rows"][0]
    assert top["ok"] and top["budget_w"] and top["used_w"] is not None and top["ports"] == []
    one = client.get(f"/api/v1/poe/{top['device_id']}").json()
    assert one["ports"] and all("port" in p for p in one["ports"])
    assert one["powered"] == top["powered"]
    assert client.get("/api/v1/poe/00000000-0000-0000-0000-000000000000").status_code == 404
