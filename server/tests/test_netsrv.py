"""DHCP (ISC dhcpd) and DNS (Samba, BIND) read from the servers and compared with the inventory."""

from datetime import UTC, datetime

from fastapi.testclient import TestClient

from app.config import RunbookMode
from app.main import create_app
from app.netsrv import compare
from app.netsrv.dhcpd import parse_conf, parse_leases
from app.netsrv.dnsdata import ARecord, parse_samba_query
from app.netsrv.source import RealNetServices

from .conftest import AUTH, make_settings

CONF = """
# the plant
option domain-name "factory.local";
shared-network plant {
  subnet 10.10.10.0 netmask 255.255.255.0 {
    range 10.10.10.200 10.10.10.209;   # clients
    option routers 10.10.10.1;
    option domain-name-servers 10.10.10.2, 10.10.10.3;
  }
  host prn-buh-01 { hardware ethernet 00:1B:44:11:3A:B7; fixed-address 10.10.10.40; }
  host ghost { hardware ethernet 00:1b:44:11:3a:ff; fixed-address 10.10.10.41; }
}
subnet 10.10.20.0 netmask 255.255.255.0 { }
"""

LEASES = """# comment
lease 10.10.10.201 {
  starts 4 2026/11/12 10:00:00;
  ends 4 2099/11/12 10:00:00;
  binding state active;
  hardware ethernet 00:11:22:33:44:55;
  client-hostname "TABLET-1";
}
lease 10.10.10.202 {
  starts 4 2026/11/12 10:00:00;
  ends 4 2020/11/12 10:00:00;
  binding state active;
  hardware ethernet 00:11:22:33:44:56;
}
lease 10.10.10.203 {
  starts 4 2026/11/12 10:00:00;
  ends never;
  binding state active;
  hardware ethernet 00:11:22:33:44:57;
  client-hostname "PC-BUH-01";
}
lease 10.10.10.201 {
  starts 4 2026/11/13 10:00:00;
  ends 4 2099/11/13 10:00:00;
  binding state free;
  hardware ethernet 00:11:22:33:44:55;
}
"""

SAMBA = """  Name=, Records=1, Children=0
    A: 10.10.10.2 (flags=f0, serial=5, ttl=900)
  Name=pc-buh-01, Records=1, Children=0
    A: 10.10.10.21 (flags=f0, serial=5, ttl=1200)
  Name=old-printer, Records=1, Children=0
    A: 10.10.10.77 (flags=f0, serial=5, ttl=1200)
"""


def test_dhcpd_conf_and_leases():
    scopes, hosts = parse_conf(CONF)
    assert [s.network for s in scopes] == ["10.10.10.0/24", "10.10.20.0/24"]
    assert scopes[0].ranges == [("10.10.10.200", "10.10.10.209")] and scopes[0].total == 10
    assert scopes[0].routers == "10.10.10.1" and scopes[0].dns == "10.10.10.2, 10.10.10.3"
    assert [(h.name, h.ip, h.mac) for h in hosts] == [
        ("prn-buh-01", "10.10.10.40", "00:1b:44:11:3a:b7"),
        ("ghost", "10.10.10.41", "00:1b:44:11:3a:ff"),
    ]
    leases = parse_leases(LEASES, now=datetime(2026, 11, 13, tzinfo=UTC))
    # The later block of .201 says «free», .202 has run out, .203 never ends.
    assert sorted(lease.ip for lease in leases) == ["10.10.10.203"]
    assert parse_leases(
        LEASES.replace("binding state free", "binding state active"), datetime(2026, 11, 13, tzinfo=UTC)
    )


def test_samba_query_output():
    assert parse_samba_query(SAMBA) == [
        ARecord("@", "10.10.10.2"), ARecord("pc-buh-01", "10.10.10.21"), ARecord("old-printer", "10.10.10.77"),
    ]  # fmt: skip


def _inv():
    D = compare.Dev
    return [
        D("1", "PC-BUH-01", "WORKSTATION", ("10.10.10.21",)),
        D("2", "PRN-BUH-01", "PRINTER", ("10.10.10.40",)),
        D("3", "SRV-FILE", "SERVER", ("10.10.10.205",)),  # inside the pool, not fixed
        D("4", "PRN-OK-01", "PRINTER", ("10.10.10.41",)),  # the fixed host «ghost» holds its address
        D("5", "NOADDR", "WORKSTATION", ()),
        D("6", "PP-1", "PATCH_PANEL", ()),
    ]


def test_dhcp_comparison():
    scopes, hosts = parse_conf(CONF)
    leases = parse_leases(LEASES.replace("ends 4 2020", "ends 4 2099"), datetime(2026, 11, 13, tzinfo=UTC))
    inv = [*_inv(), compare.Dev("7", "SRV-X", "SERVER", ("10.10.10.203",))]  # the lease says «PC-BUH-01»
    rep = compare.dhcp_report(scopes, hosts, leases, inv, {("10.10.10.200", "10.10.10.209")}, {"10.10.10.40"})
    kinds = {(f["kind"], f["ip"]) for f in rep.findings}
    assert ("static_in_pool", "10.10.10.205") in kinds
    assert ("reservation_conflict", "10.10.10.41") in kinds
    assert rep.new_ranges == [] and [r["ip"] for r in rep.new_reservations] == ["10.10.10.41"]
    assert ("lease_conflict", "10.10.10.203") in kinds
    assert rep.scopes[0]["total"] == 10 and rep.scopes[0]["leased"] == 2
    assert [u["ip"] for u in rep.unknown_leases] == ["10.10.10.202"]


def test_dns_comparison():
    recs = [ARecord("pc-buh-01", "10.10.10.99"), ARecord("old-printer", "10.10.10.77"), ARecord("_msdcs", "10.10.10.2"),
            ARecord("prn-buh-01.factory.local", "10.10.10.40")]  # fmt: skip
    rep = compare.dns_report(recs, _inv())
    assert [m["name"] for m in rep["missing"]] == ["PRN-OK-01", "SRV-FILE"]
    assert rep["wrong"] == [{"name": "PC-BUH-01", "inventory": "10.10.10.21", "dns": ["10.10.10.99"]}]
    assert [s["name"] for s in rep["stale"]] == ["old-printer"]


def test_emulated_api(client):
    st = client.get("/api/v1/netsrv/status").json()
    assert st["simulated"] and st["dhcp"] and st["dns"]
    d = client.get("/api/v1/netsrv/dhcp").json()
    assert d["simulated"] and d["scopes"] and d["new_ranges"]
    imported = client.post("/api/v1/netsrv/dhcp/import").json()
    assert imported["ranges"] == len(d["new_ranges"])
    again = client.get("/api/v1/netsrv/dhcp").json()
    assert again["new_ranges"] == [] and again["new_reservations"] == []
    ranges = client.get("/api/v1/ipam/ranges").json()
    assert any(r["kind"] == "dhcp" for r in ranges)
    dns = client.get("/api/v1/netsrv/dns").json()
    assert dns["simulated"] and dns["records"] and dns["missing"] and dns["stale_count"] >= 1


def test_real_servers_over_ssh(seeded):
    settings = make_settings(
        runbook_mode=RunbookMode.REAL, runbook_credential="adm", dhcp_host="dhcp.factory.local",
        dns_host="dc.factory.local", dns_zones="factory.local", dns_method="samba",
    )  # fmt: skip
    with TestClient(create_app(settings), headers=AUTH) as c:
        c.post("/api/v1/credentials", json={"name": "adm", "kind": "ssh", "username": "adm", "secret": "pw"})
        calls = []

        def factory(login):
            async def run(host, command, stdin):
                calls.append((host, command, stdin))
                if "dhcpd.conf" in command:
                    return 0, CONF, ""
                if "dhcpd.leases" in command:
                    return 0, LEASES, ""
                return 0, SAMBA, ""

            return run

        c.app.state.netsrv = RealNetServices(settings, runner_factory=factory)
        assert c.get("/api/v1/netsrv/status").json()["simulated"] is False
        d = c.get("/api/v1/netsrv/dhcp").json()
        assert d["scopes"][0]["total"] == 10 and d["hosts"][0]["name"] == "prn-buh-01"
        assert calls[0][0] == "dhcp.factory.local" and calls[0][1].startswith(
            "sudo -S -p '' -- cat /etc/dhcp/dhcpd.conf"
        )
        assert calls[0][2] == "pw\n"
        dns = c.get("/api/v1/netsrv/dns").json()
        assert "samba-tool dns query localhost factory.local @ A -P" in calls[-1][1]
        assert dns["records"] == 3 and any(s["name"] == "old-printer" for s in dns["stale"])

        # A path or a zone that could carry a command is refused before anything is sent.
        n = len(calls)
        c.app.state.netsrv.settings.dhcp_conf_path = "/etc/x; reboot"
        bad = c.get("/api/v1/netsrv/dhcp")
        assert bad.status_code == 409 and len(calls) == n
