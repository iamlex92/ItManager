import uuid
from datetime import UTC, datetime, timedelta

from fastapi.testclient import TestClient

from app.discovery.snmp import SnmpScanner
from app.main import create_app
from app.models import Device, Port
from app.services import traffic
from app.services.traffic import IF_HC_IN_OCTETS, IF_HC_OUT_OCTETS, IF_NAME, SnmpTraffic, emulated_rate, workday_load

from .conftest import AUTH, TEST_DB, find, make_settings, ports_of
from .snmp_util import V2, start_agent

MSK = traffic.PLANT_TZ


def _port(**kw) -> Port:
    base = dict(
        id=uuid.uuid4(),
        port_number=1,
        port_name="gi1/0/1",
        is_uplink=False,
        is_admin_up=True,
        is_oper_up=True,
        speed_mbps=1000,
        if_index=None,
    )
    base.update(kw)
    return Port(**base)


def test_workday_curve():
    tuesday = datetime(2026, 10, 27, tzinfo=MSK)
    assert workday_load(tuesday.replace(hour=3)) < 0.1
    assert workday_load(tuesday.replace(hour=10)) == 1.0
    assert workday_load(tuesday.replace(hour=12, minute=30)) < 1.0
    assert workday_load(datetime(2026, 10, 25, 11, tzinfo=MSK)) < 0.1  # Sunday


def test_emulated_rates_are_stable_and_capped():
    at = datetime(2026, 10, 27, 10, tzinfo=MSK)
    p = _port()
    assert emulated_rate(p, at) == emulated_rate(p, at)
    assert emulated_rate(_port(is_oper_up=False), at) == (0, 0)
    slow = _port(speed_mbps=10, is_uplink=True)
    assert max(emulated_rate(slow, at)) <= 10_000_000
    uplink = _port(is_uplink=True, speed_mbps=10000)
    assert emulated_rate(uplink, at)[0] >= 150_000_000 * 0.6


async def _sample(at: datetime, keep_days: int = 7) -> int:
    from app.db import make_engine, make_sessionmaker

    engine = make_engine(TEST_DB)
    try:
        return await traffic.sample_once(make_sessionmaker(engine), traffic.EmulatedTraffic(), at, keep_days)
    finally:
        await engine.dispose()


def test_samples_feed_the_summary_and_the_chart(client):
    import asyncio

    now = datetime.now(UTC).replace(microsecond=0)
    for k in range(12, 0, -1):
        written = asyncio.run(_sample(now - timedelta(minutes=5 * k)))
    assert written > 100

    sw = find(client, "SW-ACC-01")
    summary = client.get(f"/api/v1/devices/{sw['id']}/traffic", params={"hours": 2}).json()
    by_name = {p["port_name"]: p for p in summary["ports"]}
    up = by_name["gi1/0/2"]
    assert up["peak_in_bps"] >= up["avg_in_bps"] > 0 and up["last_in_bps"] is not None
    port = next(p for p in ports_of(client, sw["id"]) if p["name"] == "gi1/0/2")
    # Night-time load rounds to 0.0 %, so compare with the samples instead of a fixed bound.
    peak = max(up["peak_in_bps"], up["peak_out_bps"])
    assert up["peak_utilization"] == round(100 * peak / (port["speed_mbps"] * 1_000_000), 1) <= 100
    assert by_name["gi1/0/22"]["peak_in_bps"] == 0  # admin down in the seed

    chart = client.get(f"/api/v1/ports/{port['id']}/traffic", params={"hours": 2, "points": 10}).json()
    assert 1 <= len(chart["points"]) <= 10
    assert chart["points"] == sorted(chart["points"], key=lambda x: x["at"])
    assert client.get(f"/api/v1/ports/{uuid.uuid4()}/traffic").status_code == 404


def test_old_samples_are_dropped(client):
    import asyncio

    now = datetime.now(UTC).replace(microsecond=0)
    asyncio.run(_sample(now - timedelta(days=3)))
    asyncio.run(_sample(now, keep_days=1))
    sw = find(client, "SW-ACC-01")
    port = ports_of(client, sw["id"])[1]
    chart = client.get(f"/api/v1/ports/{port['id']}/traffic", params={"hours": 24 * 7}).json()
    assert len(chart["points"]) == 1


def test_emulator_backfills_a_day_on_start(seeded):
    with TestClient(create_app(make_settings(traffic_interval_seconds=3600)), headers=AUTH) as c:
        sw = find(c, "SW-CORE-01")
        import time

        port = ports_of(c, sw["id"])[0]
        points = []
        for _ in range(200):
            points = c.get(f"/api/v1/ports/{port['id']}/traffic").json()["points"]
            if len(points) >= 20:
                break
            time.sleep(0.05)
        assert len(points) >= 20
        assert c.get(f"/api/v1/devices/{sw['id']}/traffic").json()["ports"]


async def test_snmp_counters_become_bits_per_second():
    table = {
        f"{IF_NAME}.10101": V2.OctetString("Gi1/0/1"),
        f"{IF_NAME}.10102": V2.OctetString("Gi1/0/2"),
        f"{IF_HC_IN_OCTETS}.10101": V2.Counter64(1_000_000),
        f"{IF_HC_IN_OCTETS}.10102": V2.Counter64(5_000),
        f"{IF_HC_OUT_OCTETS}.10101": V2.Counter64(2_000_000),
        f"{IF_HC_OUT_OCTETS}.10102": V2.Counter64(9_000),
    }
    transport, _agent, port = await start_agent(table, community="ro-c0mm")

    class Reader(SnmpScanner):
        async def _target(self, ip):
            from pysnmp.hlapi.v3arch import asyncio as h

            return await h.UdpTransportTarget.create(("127.0.0.1", port), timeout=0.5, retries=0)

    now = [100.0]
    settings = make_settings()
    source = SnmpTraffic(settings, reader=Reader(settings), clock=lambda: now[0])
    device = Device(name="SW", device_type="SWITCH", ip_address="10.99.0.2", snmp_community="ro-c0mm")
    p1 = _port(port_name="gi1/0/1")
    p2 = _port(port_number=2, port_name="gi1/0/2", if_index=10102)
    try:
        assert await source.rates(device, [p1, p2], datetime.now(UTC)) == {}  # first poll: nothing to compare
        now[0] += 10
        table[f"{IF_HC_IN_OCTETS}.10101"] = V2.Counter64(1_000_000 + 12_500_000)  # 100 Mbit over 10 s
        table[f"{IF_HC_OUT_OCTETS}.10101"] = V2.Counter64(2_000_000 + 1_250_000)
        table[f"{IF_HC_IN_OCTETS}.10102"] = V2.Counter64(1_000)  # went back: switch rebooted
        rates = await source.rates(device, [p1, p2], datetime.now(UTC))
    finally:
        transport.close()
    assert rates == {p1.id: (10_000_000, 1_000_000)}
