"""The inventory as NetBox bulk-import CSV files in one zip. Reads the inventory only; nothing is sent anywhere.

Column names follow NetBox 4.x. Import the files in the order the README inside the zip lists.
"""

import csv
import io
import re
import zipfile
from collections.abc import Iterable, Sequence

from ..models import Connection, Device, Floor, Vlan

_TRANSLIT = dict(
    zip(
        "абвгдеёжзийклмнопрстуфхцчшщъыьэюя",
        ["a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u",
         "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"],
        strict=True,
    )
)  # fmt: skip

# NetBox wants a slug for every named object; the role is the device type of the inventory.
ROLE_NAMES = {
    "SWITCH": "Switch", "WORKSTATION": "Workstation", "SERVER": "Server", "PRINTER": "Printer", "CAMERA": "Camera",
    "ACCESS_POINT": "Access point", "ROUTER": "Router", "FIREWALL": "Firewall", "UPS": "UPS", "PHONE": "Phone",
    "VIRTUAL": "Virtual machine", "RACK": "Rack", "UNKNOWN": "Unknown",
}  # fmt: skip

CABLE_TYPES = {"cat5e": "cat5e", "cat6": "cat6", "cat6a": "cat6a", "cat7": "cat7"}

README = """\
NetBox import files from ItManager (NetBox 4.x column names).

Import in this order (NetBox: Import button on each list page, CSV format):
 1. sites.csv  2. manufacturers.csv  3. device_roles.csv  4. device_types.csv  5. vlans.csv  6. prefixes.csv
 7. devices.csv  8. interfaces.csv  9. ip_addresses.csv  10. cables.csv

Notes:
 - Floors become sites, device types become roles. Devices without a floor go to the site "No floor".
 - A switch port becomes an interface of the same name; its type comes from the port speed.
 - IP addresses are exported as /32 because the inventory keeps no prefix length for a device address.
 - Cables join interfaces of the devices; a cable whose end port has no device is skipped.
 - Passwords, SNMP communities and credentials are never exported.
"""


def slugify(text: str) -> str:
    out = "".join(_TRANSLIT.get(c, c) for c in text.lower())
    out = re.sub(r"[^a-z0-9]+", "-", out).strip("-")
    return out or "x"


def site_name(floor: Floor | None) -> str:
    if floor is None:
        return "No floor"
    return floor.name or f"{floor.building_name} floor {floor.floor_number}"


def interface_type(speed_mbps: int) -> str:
    if speed_mbps >= 10000:
        return "10gbase-x-sfpp"
    if speed_mbps >= 1000:
        return "1000base-t"
    if speed_mbps >= 100:
        return "100base-tx"
    return "10base-t"


def _csv(header: Sequence[str], rows: Iterable[Sequence[object]]) -> str:
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(header)
    w.writerows(rows)
    return buf.getvalue()


def build_zip(
    floors: Sequence[Floor], vlans: Sequence[Vlan], devices: Sequence[Device], connections: Sequence[Connection]
) -> bytes:
    """Devices must have their ports loaded."""
    floor_by_id = {f.id: f for f in floors}
    site_of = {d.id: site_name(floor_by_id.get(d.floor_id)) for d in devices}
    sites = sorted({*(site_name(f) for f in floors), *(site_of.values())})
    maker = {d.id: (d.vendor or "Unknown") for d in devices}
    makers = sorted(set(maker.values()))
    roles = sorted({d.device_type for d in devices})
    models = sorted({(maker[d.id], d.model or d.device_type.title()) for d in devices})

    files: dict[str, str] = {
        "sites.csv": _csv(["name", "slug", "status"], [(s, slugify(s), "active") for s in sites]),
        "manufacturers.csv": _csv(["name", "slug"], [(m, slugify(m)) for m in makers]),
        "device_roles.csv": _csv(
            ["name", "slug", "color"], [(ROLE_NAMES.get(r, r), slugify(r), "9e9e9e") for r in roles]
        ),
        "device_types.csv": _csv(
            ["manufacturer", "model", "slug"], [(m, mod, slugify(f"{m}-{mod}")) for m, mod in models]
        ),
        "vlans.csv": _csv(
            ["vid", "name", "status", "description"],
            [(v.vlan_id, v.name, "active", f"{v.subnet_cidr} {v.description or ''}".strip()) for v in vlans],
        ),
        "prefixes.csv": _csv(
            ["prefix", "status", "description"],
            [(str(v.subnet_cidr), "active", f"VLAN {v.vlan_id} {v.name}") for v in vlans],
        ),
        "devices.csv": _csv(
            ["name", "role", "manufacturer", "device_type", "status", "site", "comments"],
            [
                (
                    d.name,
                    ROLE_NAMES.get(d.device_type, d.device_type),
                    maker[d.id],
                    d.model or d.device_type.title(),
                    "active" if d.is_active else "offline",
                    site_of[d.id],
                    d.mac_address or "",
                )
                for d in devices
            ],
        ),
    }
    iface_rows, ip_rows = [], []
    port_name: dict[object, tuple[str, str]] = {}
    for d in devices:
        for p in d.ports:
            port_name[p.id] = (d.name, p.port_name)
            iface_rows.append(
                (
                    d.name,
                    p.port_name,
                    interface_type(p.speed_mbps),
                    str(p.is_admin_up).lower(),
                    p.speed_mbps * 1000,
                    p.connected_mac or "",
                )
            )
        if d.ip_address:
            ip_rows.append((f"{d.ip_address}/32", "active", d.name))
    files["interfaces.csv"] = _csv(["device", "name", "type", "enabled", "speed", "description"], iface_rows)
    files["ip_addresses.csv"] = _csv(["address", "status", "description"], ip_rows)

    cable_rows = []
    for c in connections:
        a, b = port_name.get(c.port_a_id), port_name.get(c.port_b_id)
        if a is None or b is None:
            continue
        cable_rows.append(
            (
                a[0], "dcim.interface", a[1], b[0], "dcim.interface", b[1],
                CABLE_TYPES.get((c.cable_type or "").lower(), ""), "connected", c.label or "",
                c.length_meters if c.length_meters is not None else "", "m" if c.length_meters is not None else "",
            )
        )  # fmt: skip
    files["cables.csv"] = _csv(
        ["side_a_device", "side_a_type", "side_a_name", "side_b_device", "side_b_type", "side_b_name",
         "type", "status", "label", "length", "length_unit"],
        cable_rows,
    )  # fmt: skip
    files["README.txt"] = README

    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, text in files.items():
            z.writestr(name, text.encode("utf-8-sig") if name.endswith(".csv") else text.encode("utf-8"))
    return out.getvalue()
