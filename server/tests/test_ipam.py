from app.ipam.analyze import Host, analyze, check_address, usable_hosts

VLANS = [(10, "Office", "10.0.10.0/24"), (20, "Cameras", "10.0.20.0/30"), (99, "Mgmt", None)]


def h(name: str, ip: str) -> Host:
    return Host(name, name, ip)


def test_usage_counts_and_next_free():
    usage, conflicts = analyze(VLANS, [h("A", "10.0.10.1"), h("B", "10.0.10.3"), h("C", "10.0.20.1")])
    office = next(u for u in usage if u.vlan_id == 10)
    assert (office.total, office.used, office.free) == (254, 2, 252)
    assert office.next_free[:2] == ["10.0.10.2", "10.0.10.4"]
    cams = next(u for u in usage if u.vlan_id == 20)
    assert (cams.total, cams.used, cams.next_free) == (2, 1, ["10.0.20.2"])
    assert not conflicts
    assert all(u.vlan_id != 99 for u in usage)  # no subnet, nothing to count


def test_conflicts():
    hosts = [
        h("A", "10.0.10.5"),
        h("B", "10.0.10.5/24"),
        h("C", "172.16.0.9"),
        h("D", "10.0.10.0"),
        h("E", "10.0.20.3"),
    ]
    _, conflicts = analyze(VLANS, hosts)
    kinds = {(c.kind, c.ip) for c in conflicts}
    assert ("duplicate", "10.0.10.5") in kinds
    assert ("outside_subnets", "172.16.0.9") in kinds
    assert ("network_address", "10.0.10.0") in kinds
    assert ("broadcast_address", "10.0.20.3") in kinds
    dup = next(c for c in conflicts if c.kind == "duplicate")
    assert sorted(dup.devices) == ["A", "B"]


def test_unparsable_addresses_are_ignored_and_no_subnets_means_no_outside_conflicts():
    usage, conflicts = analyze([(99, "Mgmt", None)], [h("A", "not-an-ip"), h("B", "192.168.1.1")])
    assert usage == [] and conflicts == []


def test_check_address():
    hosts = [h("A", "10.0.10.5")]
    assert check_address("10.0.10.5", VLANS, hosts)["owners"] == ["A"]
    free = check_address("10.0.10.6", VLANS, hosts)
    assert free["free"] and free["vlan_id"] == 10 and free["problems"] == []
    assert "адрес не входит ни в одну подсеть VLAN" in check_address("8.8.8.8", VLANS, hosts)["problems"]
    assert check_address("zzz", VLANS, hosts)["valid"] is False
    assert usable_hosts(__import__("ipaddress").ip_network("10.0.0.0/31")) == 2


def test_api_overview_and_check(client):
    r = client.post("/api/v1/vlans", json={"vlan_id": 61, "name": "IPAM", "subnet_cidr": "10.99.1.0/24"})
    assert r.status_code == 201
    r = client.get("/api/v1/ipam")
    assert r.status_code == 200
    body = r.json()
    assert {"subnets", "conflicts"} <= set(body)
    fresh = next(s for s in body["subnets"] if s["vlan_id"] == 61)
    assert (fresh["total"], fresh["used"], fresh["next_free"][0]) == (254, 0, "10.99.1.1")

    r = client.get("/api/v1/ipam/check", params={"ip": "10.99.1.1"})
    assert r.status_code == 200 and r.json()["free"] is True and r.json()["vlan_id"] == 61

    owner = next(d for d in client.get("/api/v1/devices").json() if d.get("ip"))
    r = client.get("/api/v1/ipam/check", params={"ip": owner["ip"]})
    assert r.json()["free"] is False and owner["name"] in r.json()["owners"]
