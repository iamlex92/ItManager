"""What kind of device this is, from what a scan could see without logging in:
open ports, the MAC vendor, names and banners. SNMP, when it answers, is more
precise and wins (see model.guess_type)."""

import logging
import re

from .model import FoundDevice
from .probe import PORT_NAMES, HostInfo

log = logging.getLogger(__name__)

# OUI registration (lower case) -> vendor as the inventory stores it.
VENDORS = (
    ("cisco", "CISCO"),
    ("eltex", "ELTEX"),
    ("hikvision", "HIKVISION"),
    ("dahua", "DAHUA"),
    ("axis communications", "AXIS"),
    ("kyocera", "KYOCERA"),
    ("hewlett", "HP"),
    ("hp inc", "HP"),
    ("brother", "BROTHER"),
    ("canon", "CANON"),
    ("xerox", "XEROX"),
    ("ricoh", "RICOH"),
    ("seiko epson", "EPSON"),
    ("lexmark", "LEXMARK"),
    ("konica", "KONICA MINOLTA"),
    ("sharp", "SHARP"),
    ("pantum", "PANTUM"),
    ("zebra", "ZEBRA"),
    ("routerboard", "MIKROTIK"),
    ("mikrotik", "MIKROTIK"),
    ("ubiquiti", "UBIQUITI"),
    ("tp-link", "TP-LINK"),
    ("d-link", "D-LINK"),
    ("huawei", "HUAWEI"),
    ("h3c", "H3C"),
    ("zyxel", "ZYXEL"),
    ("juniper", "JUNIPER"),
    ("aruba", "ARUBA"),
    ("ruckus", "RUCKUS"),
    ("snr", "SNR"),
    ("qtech", "QTECH"),
    ("vmware", "VMWARE"),
    ("super micro", "SUPERMICRO"),
    ("supermicro", "SUPERMICRO"),
    ("dell", "DELL"),
    ("lenovo", "LENOVO"),
    ("intel", "INTEL"),
    ("realtek", "REALTEK"),
    ("asustek", "ASUS"),
    ("giga-byte", "GIGABYTE"),
    ("micro-star", "MSI"),
    ("microsoft", "MICROSOFT"),
    ("apple", "APPLE"),
    ("samsung", "SAMSUNG"),
    ("xiaomi", "XIAOMI"),
    ("raspberry", "RASPBERRY PI"),
    ("synology", "SYNOLOGY"),
    ("qnap", "QNAP"),
    ("american power", "APC"),
    ("schneider", "SCHNEIDER"),
    ("yealink", "YEALINK"),
    ("grandstream", "GRANDSTREAM"),
    ("polycom", "POLY"),
)
PRINTER_VENDORS = {"KYOCERA", "BROTHER", "CANON", "XEROX", "RICOH", "EPSON", "LEXMARK", "KONICA MINOLTA", "SHARP"}
PRINTER_VENDORS |= {"PANTUM", "ZEBRA"}
CAMERA_VENDORS = {"HIKVISION", "DAHUA", "AXIS"}
NETWORK_VENDORS = {"CISCO", "ELTEX", "MIKROTIK", "HUAWEI", "H3C", "ZYXEL", "JUNIPER", "SNR", "QTECH", "D-LINK"}
AP_VENDORS = {"UBIQUITI", "RUCKUS", "ARUBA"}
SERVER_VENDORS = {"VMWARE", "SUPERMICRO", "SYNOLOGY", "QNAP"}

PRINTER_WORDS = ("laserjet", "officejet", "ecosys", "taskalfa", "command center", "printer", "mfp", "jetdirect",
                 "kyocera", "ricoh", "xerox", "brother", "lexmark", "pantum", "bizhub", "imagerunner", "workcentre",
                 "versalink", "epson", "web image monitor", "embedded web server")  # fmt: skip
CAMERA_WORDS = ("hikvision", "dahua", "camera", "ipcam", "nvr", "dvr", "axis", "trassir", "ip camera", "webcam",
                "doc/page/login", "dh-ipc", "ds-2cd")  # fmt: skip
AP_WORDS = ("unifi", "aironet", "access point", "omada", "airos", "wireless ap")
SWITCH_WORDS = ("cisco", "eltex", "mes2", "mes3", "routeros", "mikrotik", "user access verification", "switch",
                "huawei", "comware", "procurve", "zyxel", "catalyst", "edgeswitch", "vrp")  # fmt: skip
GENERIC = {"SERVER", "WORKSTATION"}
SERVER_NAME = re.compile(r"(^|[-_.])(srv|server|dc\d*|esx\w*|hv\d*|hyperv|sql\w*|fs\d+|nas|backup|vcenter)([-_.\d]|$)")


def vendor_from_mac(mac: str | None) -> str | None:
    """Vendor from the first three bytes of the MAC (IEEE OUI list shipped with netaddr)."""
    if not mac:
        return None
    try:
        from netaddr import EUI

        org = EUI(mac).oui.registration().org
    except Exception:
        # Not registered, randomised (phones) or netaddr missing.
        return None
    return short_vendor(org)


def short_vendor(org: str) -> str:
    low = org.lower()
    for key, name in VENDORS:
        if key in low:
            return name
    words = re.sub(r"[,.()]", " ", org).split()
    return (words[0] if words else org).upper()[:50]


def _has(text: str, words: tuple[str, ...]) -> bool:
    low = text.lower()
    return any(w in low for w in words)


def classify(info: HostInfo, vendor: str | None) -> tuple[str, list[str]]:
    """Device type and the evidence for it, in Russian, for the scan result."""
    ports = info.open_ports
    http = info.http_text()
    cli = " ".join(x for x in (info.ssh, info.telnet) if x)
    name = (info.netbios or info.hostname or "").lower()
    windows = bool(ports & {135, 139, 445})

    if 9100 in ports or 515 in ports:
        return "PRINTER", [f"открыт порт печати {9100 if 9100 in ports else 515}"]
    if _has(http, PRINTER_WORDS):
        return "PRINTER", [f"веб-страница принтера «{http[:60]}»"]
    if vendor in PRINTER_VENDORS and not windows:
        return "PRINTER", [f"MAC производителя принтеров {vendor}"]

    if vendor in CAMERA_VENDORS:
        return "CAMERA", [f"MAC производителя камер {vendor}"]
    if _has(http, CAMERA_WORDS):
        return "CAMERA", [f"веб-страница камеры «{http[:60]}»"]
    if (554 in ports or 37777 in ports) and not windows:
        return "CAMERA", ["открыт порт видеопотока RTSP" if 554 in ports else "открыт порт Dahua 37777"]

    if vendor in AP_VENDORS or _has(http, AP_WORDS):
        return "ACCESS_POINT", [f"точка доступа {vendor}" if vendor in AP_VENDORS else f"веб-страница «{http[:60]}»"]

    if 8291 in ports:
        return "SWITCH", ["открыт Winbox (MikroTik)"]
    if _has(cli, SWITCH_WORDS):
        return "SWITCH", [f"приглашение консоли «{cli[:60]}»"]
    if vendor in NETWORK_VENDORS and not windows and not ports & {3389, 5985}:
        return "SWITCH", [f"MAC производителя сетевого оборудования {vendor}"]
    if _has(http, SWITCH_WORDS) and not windows:
        return "SWITCH", [f"веб-страница «{http[:60]}»"]

    server_ports = ports & {88, 389, 902, 1433, 3306, 5432, 5985, 53}
    if 902 in ports or vendor in SERVER_VENDORS:
        return "SERVER", ["гипервизор или сервер " + (vendor or "VMware ESXi")]
    if server_ports and (windows or 22 in ports):
        names = ", ".join(PORT_NAMES[p] for p in sorted(server_ports))
        return "SERVER", [f"серверные службы: {names}"]
    if SERVER_NAME.search(name):
        return "SERVER", [f"имя сервера «{info.netbios or info.hostname}»"]
    if windows or 3389 in ports:
        return "WORKSTATION", ["Windows: открыт " + ", ".join(PORT_NAMES[p] for p in sorted(ports & {135, 445, 3389}))]
    if info.ssh and not ports & {80, 443}:
        return "SERVER", [f"Linux/Unix: {info.ssh[:60]}"]
    return "UNKNOWN", []


def apply(dev: FoundDevice, info: HostInfo | None) -> None:
    """Fills in what SNMP did not tell: name, vendor, type and a short description."""
    if not dev.vendor and dev.mac:
        dev.vendor = vendor_from_mac(dev.mac)
    if info is None:
        return
    dev.open_ports = sorted(info.open_ports)
    dev.hostname = info.hostname
    if not dev.name:
        dev.name = info.netbios or _host_label(info.hostname)
    guess, evidence = classify(info, dev.vendor)
    # SNMP's sysDescr says "Linux" for many cameras and printers too: a specific guess from ports wins over it.
    if dev.device_type == "UNKNOWN" or (dev.device_type in GENERIC and guess not in GENERIC | {"UNKNOWN"}):
        dev.device_type = guess
    else:
        evidence = []
    if dev.snmp:
        return
    facts = []
    if evidence:
        facts.append("Тип: " + "; ".join(evidence))
    if dev.open_ports:
        facts.append("Порты: " + ", ".join(f"{p} {PORT_NAMES.get(p, '')}".strip() for p in dev.open_ports))
    for label, value in (
        ("SSH", info.ssh),
        ("Telnet", info.telnet),
        ("Веб", info.http_title),
        ("Сервер", info.http_server),
        ("Сертификат", info.cert_name),
    ):
        if value:
            facts.append(f"{label}: {value[:100]}")
    dev.description = "\n".join(facts) or None


def _host_label(hostname: str | None) -> str | None:
    if not hostname:
        return None
    return hostname.split(".")[0][:100] or None
