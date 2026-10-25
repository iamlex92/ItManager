"""CLI command translation for Cisco IOS and Eltex MES.

Pure functions: they only build text. Used both for the preview endpoint and
by drivers that actually send the commands.
"""

from .base import PortConfig, UnsupportedVendor

CISCO = "CISCO"
ELTEX = "ELTEX"
SUPPORTED_VENDORS = (CISCO, ELTEX)


_CISCO_PREFIXES = {"Gi": "GigabitEthernet", "Te": "TenGigabitEthernet", "Fa": "FastEthernet"}
_ELTEX_PREFIXES = {"gi": "gigabitethernet ", "te": "tengigabitethernet ", "fa": "fastethernet "}


def full_interface_name(vendor: str, short: str) -> str:
    """Gi1/0/5 -> GigabitEthernet1/0/5 (Cisco), gi1/0/5 -> gigabitethernet 1/0/5 (Eltex)."""
    table = _CISCO_PREFIXES if vendor.upper() == CISCO else _ELTEX_PREFIXES
    for prefix, full in table.items():
        if short.startswith(prefix):
            return full + short[len(prefix) :]
    return short


def _vendor(vendor: str | None) -> str:
    v = (vendor or "").upper()
    if v not in SUPPORTED_VENDORS:
        raise UnsupportedVendor(f"vendor {vendor!r} is not a managed switch vendor")
    return v


def _vlan_list(vlans: tuple[int, ...]) -> str:
    return ",".join(str(v) for v in vlans) if vlans else "none"


def port_config_commands(vendor: str | None, port_name: str, change: PortConfig) -> list[str]:
    v = _vendor(vendor)
    body: list[str] = []
    if change.mode == "ACCESS":
        body.append("switchport mode access")
    elif change.mode == "TRUNK":
        body.append("switchport mode trunk")
    if change.access_vlan is not None:
        body.append(f"switchport access vlan {change.access_vlan}")
    if change.tagged_vlans is not None:
        if v == CISCO:
            body.append(f"switchport trunk allowed vlan {_vlan_list(change.tagged_vlans)}")
        else:
            # MES: reset then add, "allowed vlan <list>" alone is additive there.
            body.append("switchport trunk allowed vlan remove all")
            if change.tagged_vlans:
                body.append(f"switchport trunk allowed vlan add {_vlan_list(change.tagged_vlans)}")
    if change.is_admin_up is True:
        body.append("no shutdown")
    elif change.is_admin_up is False:
        body.append("shutdown")
    if not body:
        return []
    return ["configure terminal", f"interface {full_interface_name(v, port_name)}", *body, "end"]


def bounce_commands(vendor: str | None, port_name: str) -> list[str]:
    iface = f"interface {full_interface_name(_vendor(vendor), port_name)}"
    # The 2 s pause between the two blocks is done by the driver.
    return [
        "configure terminal",
        iface,
        "shutdown",
        "end",
        "configure terminal",
        iface,
        "no shutdown",
        "end",
    ]


def save_commands(vendor: str | None) -> list[str]:
    v = _vendor(vendor)
    if v == CISCO:
        return ["write memory"]
    return ["copy running-config startup-config"]
