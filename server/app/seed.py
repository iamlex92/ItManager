"""Realistic demo data: one floor, four Cisco/Eltex switches, patch panels,
wall sockets, PCs, printers, cameras and servers, all cabled together.

    python -m app.seed            # refuses if the database already has devices
    python -m app.seed --reset    # wipes all tables first
    python -m app.seed --if-empty # seeds only an empty database, else does nothing
"""

import argparse
import asyncio
import itertools
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from .config import get_settings
from .db import make_engine, make_sessionmaker
from .models import Base, Connection, Device, DeviceAlert, Floor, Port, Vlan

VLANS = [
    (1, "default", "10.0.1.0/24", "#95a5a6", "Неиспользуемые порты"),
    (10, "Офис", "10.10.10.0/24", "#3498db", "Бухгалтерия, отдел кадров"),
    (20, "АСУ ТП", "10.10.20.0/24", "#e67e22", "АРМ операторов цеха №1"),
    (30, "Печать", "10.10.30.0/24", "#9b59b6", "МФУ и принтеры"),
    (40, "Видеонаблюдение", "10.10.40.0/24", "#e74c3c", "IP-камеры"),
    (50, "Серверы", "10.10.50.0/24", "#2ecc71", "Серверная"),
    (99, "Управление", "10.99.0.0/24", "#34495e", "Management коммутаторов и точек доступа"),
]
TRUNK_VLANS = [10, 20, 30, 40, 50, 99]

_mac_counter = itertools.count(1)


def _mac(oui: str) -> str:
    n = next(_mac_counter)
    return f"{oui}:{(n >> 16) & 0xFF:02x}:{(n >> 8) & 0xFF:02x}:{n & 0xFF:02x}"


@dataclass
class SwitchSpec:
    name: str
    vendor: str
    model: str
    ip: str
    pos: tuple[int, int]
    access_ports: int
    access_fmt: str
    uplink_names: list[str]
    uplink_speed: int
    nfc: str


SWITCHES = [
    SwitchSpec(
        "SW-CORE-01",
        "CISCO",
        "C9300-48P",
        "10.99.0.1",
        (300, 200),
        48,
        "Gi1/0/{}",
        [f"Te1/1/{i}" for i in range(1, 5)],
        10000,
        "04:A2:3B:1C:5D:80:01",
    ),
    SwitchSpec(
        "SW-ACC-01",
        "ELTEX",
        "MES2324P",
        "10.99.0.11",
        (1150, 520),
        24,
        "gi1/0/{}",
        [f"te1/0/{i}" for i in range(1, 5)],
        10000,
        "04:A2:3B:1C:5D:80:02",
    ),
    SwitchSpec(
        "SW-ACC-02",
        "ELTEX",
        "MES2348P",
        "10.99.0.12",
        (1210, 720),
        48,
        "gi1/0/{}",
        [f"te1/0/{i}" for i in range(1, 5)],
        10000,
        "04:A2:3B:1C:5D:80:03",
    ),
    SwitchSpec(
        "SW-ACC-03",
        "CISCO",
        "WS-C2960X-24PS-L",
        "10.99.0.13",
        (1670, 520),
        24,
        "Gi1/0/{}",
        [f"Gi1/0/{i}" for i in range(25, 29)],
        1000,
        "04:A2:3B:1C:5D:80:04",
    ),
]


@dataclass
class HostSpec:
    name: str
    type: str
    vendor: str | None
    model: str | None
    ip: str
    pos: tuple[int, int]
    switch: str
    port: int
    vlan: int
    speed: int = 1000
    poe: bool = False
    online: bool = True
    socket: str | None = None  # None: patched straight into the switch (server room)
    mode: str = "ACCESS"
    metadata: dict | None = None


def _printer_meta(pages: int, supplies: list[tuple[str, str, int]]) -> dict:
    return {
        "snmp": {
            "total_pages": pages,
            "consumables": [{"name": n, "color_hex": c, "level_percent": lvl} for n, c, lvl in supplies],
        }
    }


HOSTS: list[HostSpec] = [
    # Server room, straight into the core
    HostSpec("SRV-DC-01", "SERVER", "HPE", "ProLiant DL360 Gen10", "10.10.50.10", (120, 300), "SW-CORE-01", 1, 50),
    HostSpec("SRV-FILE-01", "SERVER", "HPE", "ProLiant DL380 Gen10", "10.10.50.11", (180, 300), "SW-CORE-01", 2, 50),
    HostSpec("SRV-1C-01", "SERVER", "Dell", "PowerEdge R650", "10.10.50.12", (240, 300), "SW-CORE-01", 3, 50),
    HostSpec("SRV-VIDEO-01", "SERVER", "Hikvision", "DS-9632NI-I8", "10.10.40.5", (300, 300), "SW-CORE-01", 4, 40),
    # Accounting on SW-ACC-01 (Eltex 24)
    *[
        HostSpec(
            f"PC-BUH-0{i}",
            "WORKSTATION",
            "Lenovo",
            "ThinkCentre M70q",
            f"10.10.10.{20 + i}",
            (560 + (i - 1) % 3 * 180, 200 + (i - 1) // 3 * 200),
            "SW-ACC-01",
            i,
            10,
            online=i != 5,
            socket=f"R-1.0{i}",
        )
        for i in range(1, 7)
    ],
    HostSpec(
        "PRN-BUH-01",
        "PRINTER",
        "KYOCERA",
        "ECOSYS M3145dn",
        "10.10.30.21",
        (1060, 420),
        "SW-ACC-01",
        10,
        30,
        socket="R-1.10",
        metadata=_printer_meta(48213, [("Black", "#000000", 7), ("Waste toner", "#7f8c8d", 64)]),
    ),
    HostSpec(
        "CAM-01",
        "CAMERA",
        "Hikvision",
        "DS-2CD2143G2-I",
        "10.10.40.21",
        (900, 520),
        "SW-ACC-01",
        20,
        40,
        speed=100,
        poe=True,
        socket="R-1.20",
    ),
    # HR on SW-ACC-03 (Cisco 24)
    *[
        HostSpec(
            f"PC-OK-0{i}",
            "WORKSTATION",
            "HP",
            "ProDesk 400 G7",
            f"10.10.10.{40 + i}",
            (1220 + (i - 1) * 160, 260),
            "SW-ACC-03",
            i,
            10,
            socket=f"R-2.0{i}",
        )
        for i in range(1, 4)
    ],
    HostSpec(
        "PRN-OK-01",
        "PRINTER",
        "KYOCERA",
        "TASKalfa 2554ci",
        "10.10.30.22",
        (1600, 420),
        "SW-ACC-03",
        10,
        30,
        socket="R-2.10",
        metadata=_printer_meta(
            129877,
            [
                ("Black", "#000000", 54),
                ("Cyan", "#00aeef", 23),
                ("Magenta", "#ec008c", 9),
                ("Yellow", "#ffd400", 81),
                ("Waste toner", "#7f8c8d", 40),
            ],
        ),
    ),
    HostSpec(
        "AP-ADM-01",
        "ACCESS_POINT",
        "Cisco",
        "C9120AXI",
        "10.99.0.31",
        (1400, 620),
        "SW-ACC-03",
        24,
        99,
        poe=True,
        socket="R-2.24",
        mode="TRUNK",
    ),
    # Production hall on SW-ACC-02 (Eltex 48)
    *[
        HostSpec(
            f"PC-ASU-0{i}",
            "WORKSTATION",
            "Advantech",
            "IPC-610",
            f"10.10.20.{10 + i}",
            (200 + (i - 1) * 380, 900),
            "SW-ACC-02",
            i,
            20,
            speed=1000 if i != 4 else 100,
            online=i != 3,
            socket=f"R-3.0{i}",
        )
        for i in range(1, 6)
    ],
    HostSpec(
        "PRN-CEH-01",
        "PRINTER",
        "HP",
        "LaserJet Enterprise M507dn",
        "10.10.30.23",
        (2100, 1050),
        "SW-ACC-02",
        10,
        30,
        speed=100,
        socket="R-3.10",
        metadata=_printer_meta(301554, [("Black", "#000000", 38)]),
    ),
    *[
        HostSpec(
            f"CAM-0{i + 1}",
            "CAMERA",
            "Hikvision",
            "DS-2CD2T47G2-L",
            f"10.10.40.{21 + i}",
            (400 + (i - 1) * 700, 1120),
            "SW-ACC-02",
            20 + i,
            40,
            speed=100,
            poe=True,
            socket=f"R-3.2{i}",
        )
        for i in range(1, 4)
    ],
]


async def seed(session: AsyncSession) -> None:
    floor = Floor(
        building_name="Административно-производственный корпус",
        floor_number=1,
        plan_image_url="/static/plans/floor-1.svg",
        scale_pixels_per_meter=40,
        width_px=2400,
        height_px=1200,
    )
    session.add(floor)
    session.add_all(Vlan(vlan_id=v, name=n, subnet_cidr=c, color_hex=h, description=d) for v, n, c, h, d in VLANS)
    await session.flush()

    switches: dict[str, Device] = {}
    sw_ports: dict[str, dict[int, Port]] = {}
    for s in SWITCHES:
        dev = Device(
            floor_id=floor.id,
            name=s.name,
            vendor=s.vendor,
            model=s.model,
            device_type="SWITCH",
            pos_x=s.pos[0],
            pos_y=s.pos[1],
            ip_address=s.ip,
            mac_address=_mac("00:1e:7a" if s.vendor == "CISCO" else "a8:f9:4b"),
            nfc_tag_uid=s.nfc,
            is_online=True,
            last_seen_at=datetime.now(UTC),
            metadata_={"mgmt_vlan": 99, "location": "Шкаф " + s.name[-2:]},
        )
        dev.ports = [
            Port(
                port_number=i,
                port_name=s.access_fmt.format(i),
                access_vlan=1,
                is_oper_up=False,
                speed_mbps=1000,
                poe_enabled=False,
            )
            for i in range(1, s.access_ports + 1)
        ] + [
            Port(
                port_number=s.access_ports + j,
                port_name=name,
                is_uplink=True,
                mode="TRUNK",
                access_vlan=1,
                tagged_vlans=TRUNK_VLANS,
                speed_mbps=s.uplink_speed,
            )
            for j, name in enumerate(s.uplink_names, start=1)
        ]
        session.add(dev)
        switches[s.name] = dev
        sw_ports[s.name] = {p.port_number: p for p in dev.ports}

    # Unused access ports are shut down on the access switches, like a sane admin would.
    for name in ("SW-ACC-01", "SW-ACC-02"):
        for n in range(40 if name == "SW-ACC-02" else 21, 49 if name == "SW-ACC-02" else 25):
            sw_ports[name][n].is_admin_up = False

    # Patch panels next to each access switch
    panels: dict[str, Device] = {}
    for s in SWITCHES[1:]:
        pp = Device(
            floor_id=floor.id,
            name=f"PP-{s.name[3:]}",
            vendor=None,
            model="Hyperline PP3-19-24-8P8C-C6",
            device_type="PATCH_PANEL",
            pos_x=s.pos[0] + 30,
            pos_y=s.pos[1],
        )
        pp.ports = [Port(port_number=i, port_name=str(i)) for i in range(1, s.access_ports + 1)]
        session.add(pp)
        panels[s.name] = pp
    await session.flush()

    connections: list[Connection] = []

    # Uplinks: each access switch's first uplink to a core 10G port
    core = sw_ports["SW-CORE-01"]
    for idx, s in enumerate(SWITCHES[1:], start=1):
        acc_up = sw_ports[s.name][s.access_ports + 1]
        core_up = core[48 + idx]
        for p in (acc_up, core_up):
            p.is_oper_up = True
            p.speed_mbps = min(s.uplink_speed, 10000)
        core_up.speed_mbps = acc_up.speed_mbps
        connections.append(
            Connection(
                port_a_id=core_up.id,
                port_b_id=acc_up.id,
                cable_type="OM3" if s.uplink_speed == 10000 else "Cat6",
                length_meters=35 + idx * 12,
                label=f"UPL-{idx:02d}",
            )
        )

    for h in HOSTS:
        mac = _mac(
            {
                "WORKSTATION": "3c:52:82",
                "PRINTER": "00:17:c8",
                "CAMERA": "44:47:cc",
                "SERVER": "94:40:c9",
                "ACCESS_POINT": "70:db:98",
            }[h.type]
        )
        dev = Device(
            floor_id=floor.id,
            name=h.name,
            vendor=h.vendor,
            model=h.model,
            device_type=h.type,
            pos_x=h.pos[0],
            pos_y=h.pos[1],
            ip_address=h.ip,
            mac_address=mac,
            is_online=h.online,
            last_seen_at=datetime.now(UTC) if h.online else None,
            metadata_=h.metadata or {},
        )
        nic = Port(port_number=1, port_name="eth0", speed_mbps=h.speed, poe_enabled=h.poe, is_oper_up=h.online)
        dev.ports = [nic]
        session.add(dev)

        sp = sw_ports[h.switch][h.port]
        sp.mode = h.mode
        if h.mode == "TRUNK":
            sp.access_vlan = 1
            sp.tagged_vlans = [h.vlan, 10]
        else:
            sp.access_vlan = h.vlan
        sp.is_oper_up = h.online
        sp.is_admin_up = True
        sp.speed_mbps = h.speed
        sp.poe_enabled = h.poe
        sp.connected_mac = mac
        await session.flush()

        if h.socket is None:
            connections.append(
                Connection(
                    port_a_id=nic.id,
                    port_b_id=sp.id,
                    cable_type="Cat6",
                    length_meters=2,
                    label=f"{h.name}-{sp.port_name}",
                )
            )
            continue
        sock = Device(
            floor_id=floor.id,
            name=h.socket,
            device_type="SOCKET",
            model="RJ-45 Cat6",
            pos_x=h.pos[0] + 25,
            pos_y=h.pos[1] + 40,
        )
        sock_port = Port(port_number=1, port_name="1")
        sock.ports = [sock_port]
        session.add(sock)
        await session.flush()
        pp_port = next(p for p in panels[h.switch].ports if p.port_number == h.port)
        connections += [
            Connection(port_a_id=nic.id, port_b_id=sock_port.id, cable_type="Cat6", length_meters=3, label="патчкорд"),
            Connection(
                port_a_id=sock_port.id,
                port_b_id=pp_port.id,
                cable_type="Cat6",
                length_meters=round(abs(h.pos[0] - SWITCHES[0].pos[0]) / 40 + 8, 1),
                label=f"{h.socket}/{panels[h.switch].name}:{h.port}",
            ),
            Connection(
                port_a_id=pp_port.id,
                port_b_id=sp.id,
                cable_type="Cat6",
                length_meters=0.5,
                label=f"{panels[h.switch].name}:{h.port}",
            ),
        ]

    session.add_all(connections)
    await session.flush()

    # A bit of history for the alerts screen
    asu3 = sw_ports["SW-ACC-02"][3]
    buh5 = sw_ports["SW-ACC-01"][5]
    session.add_all(
        [
            DeviceAlert(
                device_id=switches["SW-ACC-02"].id,
                port_id=asu3.id,
                severity="WARNING",
                alert_type="LINK_DOWN",
                message=f"SW-ACC-02 {asu3.port_name}: link down",
            ),
            DeviceAlert(
                device_id=switches["SW-ACC-01"].id,
                port_id=buh5.id,
                severity="WARNING",
                alert_type="LINK_DOWN",
                message=f"SW-ACC-01 {buh5.port_name}: link down",
            ),
            DeviceAlert(
                device_id=switches["SW-CORE-01"].id,
                severity="INFO",
                alert_type="CPU_OVERLOAD",
                message="SW-CORE-01: CPU 87% for 5 minutes",
                is_resolved=True,
                resolved_at=func.now(),
            ),
        ]
    )
    await session.commit()


async def reset(session: AsyncSession) -> None:
    tables = ", ".join(t.name for t in Base.metadata.sorted_tables)
    await session.execute(text(f"TRUNCATE {tables} CASCADE"))
    await session.commit()


async def main(do_reset: bool, if_empty: bool = False) -> None:
    engine = make_engine(get_settings().database_url)
    sm = make_sessionmaker(engine)
    async with sm() as session:
        if do_reset:
            await reset(session)
        elif await session.scalar(select(func.count()).select_from(Device)):
            if if_empty:
                print("database already has devices, not seeding")
                await engine.dispose()
                return
            raise SystemExit("database already has devices; use --reset to wipe and reseed")
        await seed(session)
        n = await session.scalar(select(func.count()).select_from(Device))
    await engine.dispose()
    print(f"seeded {n} devices")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reset", action="store_true", help="truncate all tables before seeding")
    parser.add_argument("--if-empty", action="store_true", help="seed only when there are no devices yet")
    args = parser.parse_args()
    asyncio.run(main(args.reset, args.if_empty))
