import asyncio

from app.ipam.analyze import Host, IpRange, Reservation, analyze, check_address
from app.ipam.dns import check

from .conftest import find

VLANS = [(10, "Офис", "10.20.0.0/24")]


def test_reserved_and_dhcp_addresses_are_not_offered_as_free():
    hosts = [Host("a", "SW-1", "10.20.0.1")]
    base, _ = analyze(VLANS, hosts)
    assert base[0].next_free[:2] == ["10.20.0.2", "10.20.0.3"] and base[0].reserved == 0 and base[0].dhcp == 0
    usage, _ = analyze(
        VLANS,
        hosts,
        [Reservation("10.20.0.2", "Новый принтер")],
        [IpRange("Пул клиентов", "dhcp", "10.20.0.100", "10.20.0.199")],
    )
    u = usage[0]
    assert u.next_free[0] == "10.20.0.3" and "10.20.0.2" not in u.next_free
    assert u.reserved == 1 and u.dhcp == 100
    assert u.free == base[0].free - 1 - 100
    # With a pool over almost the whole subnet, only the addresses past it are offered.
    wide = analyze(VLANS, [], [], [IpRange("Пул", "dhcp", "10.20.0.1", "10.20.0.250")])[0][0]
    assert wide.next_free == ["10.20.0.251", "10.20.0.252", "10.20.0.253", "10.20.0.254"]


def test_a_device_in_a_dhcp_pool_or_on_someone_elses_reservation_is_a_conflict():
    hosts = [Host("a", "PC-1", "10.20.0.150"), Host("b", "SRV-1", "10.20.0.5"), Host("c", "PRN-1", "10.20.0.6")]
    reservations = [Reservation("10.20.0.5", "Файловый сервер", "zzz"), Reservation("10.20.0.6", "PRN-1", "c")]
    ranges = [IpRange("Клиенты", "dhcp", "10.20.0.100", "10.20.0.199")]
    _, conflicts = analyze(VLANS, hosts, reservations, ranges)
    kinds = {(c.kind, c.ip) for c in conflicts}
    assert ("in_pool", "10.20.0.150") in kinds
    assert ("reserved_by_other", "10.20.0.5") in kinds
    # The reservation made for the very device that uses the address is no conflict.
    assert ("reserved_by_other", "10.20.0.6") not in kinds


def test_check_address_mentions_reservations_and_pools():
    r = check_address("10.20.0.7", VLANS, [], [Reservation("10.20.0.7", "Камера")], [])
    assert r["free"] is False and any("зарезервирован" in p for p in r["problems"])
    r = check_address("10.20.0.150", VLANS, [], [], [IpRange("Клиенты", "dhcp", "10.20.0.100", "10.20.0.199")])
    assert r["free"] is False and any("DHCP" in p for p in r["problems"])
    assert (
        check_address("10.20.0.50", VLANS, [], [], [IpRange("Клиенты", "dhcp", "10.20.0.100", "10.20.0.199")])["free"]
        is True
    )


def test_reservations_and_ranges_through_the_api(client):
    pc = find(client, "PC-BUH-01")
    r = client.post(
        "/api/v1/ipam/reservations",
        json={"ip": "10.10.10.200", "name": "Резерв для МФУ", "device_id": pc["id"], "mac": "AA-BB-CC-00-11-22"},
    )
    assert r.status_code == 201, r.text
    res = r.json()
    assert res["device_name"] == "PC-BUH-01" and res["mac"] == "aa:bb:cc:00:11:22"
    assert client.post("/api/v1/ipam/reservations", json={"ip": "10.10.10.200", "name": "Дубль"}).status_code == 409
    assert client.get("/api/v1/ipam/check", params={"ip": "10.10.10.200"}).json()["free"] is False
    rng = client.post(
        "/api/v1/ipam/ranges",
        json={"name": "Пул клиентов", "kind": "dhcp", "start_ip": "10.10.10.100", "end_ip": "10.10.10.150"},
    ).json()
    assert rng["size"] == 51
    assert (
        client.post(
            "/api/v1/ipam/ranges", json={"name": "Плохой", "start_ip": "10.10.10.9", "end_ip": "10.10.10.1"}
        ).status_code
        == 409
    )
    assert (
        client.post(
            "/api/v1/ipam/ranges", json={"name": "Разные", "start_ip": "10.10.10.1", "end_ip": "::1"}
        ).status_code
        == 409
    )
    subnets = client.get("/api/v1/ipam").json()["subnets"]
    mine = next(s for s in subnets if s["cidr"] == "10.10.10.0/24")
    assert mine["reserved"] >= 1 and mine["dhcp"] == 51
    assert client.patch(f"/api/v1/ipam/ranges/{rng['id']}", json={"end_ip": "10.10.10.120"}).json()["size"] == 21
    assert client.delete(f"/api/v1/ipam/ranges/{rng['id']}").status_code == 204
    assert client.delete(f"/api/v1/ipam/reservations/{res['id']}").status_code == 204
    assert client.get("/api/v1/ipam/reservations").json() == []


def test_dns_check_compares_the_inventory_with_what_dns_says():
    answers = {"pc-1.f.local": ["10.0.0.1"], "pc-2.f.local": ["10.0.0.99"], "ghost.f.local": []}
    ptr = {"10.0.0.1": "PC-1.f.local", "10.0.0.2": "someone-else.f.local", "10.0.0.3": None}

    async def resolve(name):
        return answers.get(name.lower(), [])

    async def reverse(ip):
        return ptr.get(ip)

    devices = [
        ("1", "PC-1", "WORKSTATION", "10.0.0.1", []),
        ("2", "PC-2", "WORKSTATION", "10.0.0.2", []),
        ("3", "GHOST", "SERVER", "10.0.0.3", []),
        ("4", "PP-1", "PATCH_PANEL", "10.0.0.4", []),
        ("5", "NOIP", "SERVER", "", []),
    ]
    rows = asyncio.run(check(devices, resolve, reverse, "f.local"))
    by = {r.name: r for r in rows}
    assert set(by) == {"PC-1", "PC-2", "GHOST"}
    assert (by["PC-1"].forward, by["PC-1"].reverse) == ("ok", "ok")
    assert (by["PC-2"].forward, by["PC-2"].reverse) == ("mismatch", "mismatch") and by["PC-2"].forward_ips == [
        "10.0.0.99"
    ]
    assert (by["GHOST"].forward, by["GHOST"].reverse) == ("missing", "missing")
    # The ones with a problem come first.
    assert rows[-1].name == "PC-1"


def test_dns_check_endpoint_uses_the_injected_lookups(client):
    async def resolve(name):
        return ["10.10.10.21"] if name.lower().startswith("pc-buh-01") else []

    async def reverse(ip):
        return "PC-BUH-01.factory.local" if ip == "10.10.10.21" else None

    client.app.state.dns_resolve = resolve
    client.app.state.dns_reverse = reverse
    try:
        out = client.post("/api/v1/ipam/dns-check", json={"suffix": "factory.local"}).json()
    finally:
        del client.app.state.dns_resolve, client.app.state.dns_reverse
    s = out["summary"]
    assert s["checked"] == len(out["rows"]) and s["ok"] >= 1 and s["forward_missing"] >= 1
    ok = [r for r in out["rows"] if r["name"] == "PC-BUH-01"][0]
    assert ok["forward"] == "ok" and ok["reverse"] == "ok"
