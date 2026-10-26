"""Finds and recognises hosts without SNMP: ICMP echo, TCP connect to common ports,
the server's own ARP cache, reverse DNS, NetBIOS names and the banners that SSH,
Telnet and HTTP(S) send anyway. Read-only: connects, reads what the host offers
and closes. No logins, no writes, nothing but GET over HTTP."""

import asyncio
import contextlib
import ipaddress
import logging
import re
import socket
import ssl
import struct
import sys
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

log = logging.getLogger(__name__)

Pinger = Callable[[str], Awaitable[bool]]

# Tried on every address: a host that drops ICMP still answers or refuses one of these.
FAST_PORTS = (22, 23, 80, 135, 443, 445, 554, 3389, 9100)
# Tried only on hosts that are up, to tell what they are.
EXTRA_PORTS = (21, 53, 88, 139, 389, 515, 631, 902, 1433, 3306, 5432, 5900, 5985, 8000, 8080, 8291, 8443, 37777)
PORT_NAMES = {
    21: "FTP",
    22: "SSH",
    23: "Telnet",
    53: "DNS",
    80: "HTTP",
    88: "Kerberos",
    135: "RPC",
    139: "NetBIOS",
    389: "LDAP",
    443: "HTTPS",
    445: "SMB",
    515: "LPD",
    554: "RTSP",
    631: "IPP",
    902: "ESXi",
    1433: "MS SQL",
    3306: "MySQL",
    3389: "RDP",
    5432: "PostgreSQL",
    5900: "VNC",
    5985: "WinRM",
    8000: "HTTP-8000",
    8080: "HTTP-8080",
    8291: "Winbox",
    8443: "HTTPS-8443",
    9100: "RAW-печать",
    37777: "Dahua",
}
HTTP_PORTS = (80, 8080, 8000)
HTTPS_PORTS = (443, 8443)
BANNER_BYTES = 32 * 1024


@dataclass
class HostInfo:
    ip: str
    ping: bool = False
    # Some port was open.
    tcp: bool = False
    # Some port was refused: usually a host with that port closed, but a firewall may refuse for every address.
    refused: bool = False
    open_ports: set[int] = field(default_factory=set)
    hostname: str | None = None
    netbios: str | None = None
    ssh: str | None = None
    telnet: str | None = None
    http_server: str | None = None
    http_title: str | None = None
    http_realm: str | None = None
    cert_name: str | None = None
    mac: str | None = None

    @property
    def alive(self) -> bool:
        return self.ping or self.tcp or self.refused

    def http_text(self) -> str:
        return " ".join(x for x in (self.http_title, self.http_server, self.http_realm, self.cert_name) if x)


class Prober:
    def __init__(
        self,
        pinger: Pinger | None,
        fast_ports: tuple[int, ...] = FAST_PORTS,
        extra_ports: tuple[int, ...] = EXTRA_PORTS,
        connect_timeout: float = 0.8,
        read_timeout: float = 2.0,
        names: bool = True,
        connections: int = 256,
    ) -> None:
        self.pinger = pinger
        self.fast_ports = fast_ports
        self.extra_ports = tuple(p for p in extra_ports if p not in fast_ports)
        self.connect_timeout = connect_timeout
        self.read_timeout = read_timeout
        self.names = names
        self._conn = asyncio.Semaphore(connections)
        # Set when the ping binary turned out not to work, so a /22 does not log a thousand errors.
        self.ping_error: str | None = None

    async def _ping(self, ip: str) -> bool:
        if self.pinger is None or self.ping_error:
            return False
        try:
            return await self.pinger(ip)
        except (NotImplementedError, FileNotFoundError, PermissionError) as e:
            self.ping_error = f"{e.__class__.__name__}: {e}"
            log.warning("ping does not work here, falling back to TCP: %s", self.ping_error)
            return False
        except Exception:
            log.debug("ping %s failed", ip, exc_info=True)
            return False

    async def _connect(self, info: HostInfo, port: int) -> None:
        async with self._conn:
            try:
                _r, w = await asyncio.wait_for(asyncio.open_connection(info.ip, port), self.connect_timeout)
            except ConnectionRefusedError:
                info.refused = True
                return
            except (TimeoutError, OSError):
                return
            info.tcp = True
            info.open_ports.add(port)
            w.close()
            with contextlib.suppress(Exception):
                await w.wait_closed()

    async def alive(self, ip: str) -> HostInfo:
        """Is anything at this address: ICMP echo and the fast ports at the same time."""
        info = HostInfo(ip)

        async def ping() -> None:
            info.ping = await self._ping(ip)

        await asyncio.gather(ping(), *(self._connect(info, p) for p in self.fast_ports))
        return info

    async def identify(self, info: HostInfo) -> None:
        """The rest of the ports, names and banners of a host that is up."""
        await asyncio.gather(*(self._connect(info, p) for p in self.extra_ports))
        jobs = []
        if self.names:
            jobs.append(self._rdns(info))
            if info.open_ports & {135, 139, 445}:
                jobs.append(self._netbios(info))
        if 22 in info.open_ports:
            jobs.append(self._ssh(info))
        if 23 in info.open_ports:
            jobs.append(self._telnet(info))
        http = [p for p in HTTP_PORTS if p in info.open_ports]
        https = [p for p in HTTPS_PORTS if p in info.open_ports]
        if http:
            jobs.append(self._http(info, http[0], tls=False))
        if https:
            jobs.append(self._http(info, https[0], tls=True))
        await asyncio.gather(*jobs)

    # --- names ---------------------------------------------------------------

    async def _rdns(self, info: HostInfo) -> None:
        loop = asyncio.get_running_loop()
        try:
            host, _ = await asyncio.wait_for(loop.getnameinfo((info.ip, 0), socket.NI_NAMEREQD), self.read_timeout)
        except (TimeoutError, OSError):
            return
        if host and host != info.ip:
            info.hostname = host.rstrip(".")

    async def _netbios(self, info: HostInfo) -> None:
        loop = asyncio.get_running_loop()
        got: asyncio.Future[bytes] = loop.create_future()

        class Proto(asyncio.DatagramProtocol):
            def datagram_received(self, data, addr):
                if not got.done():
                    got.set_result(data)

            def error_received(self, exc):
                if not got.done():
                    got.set_exception(exc)

        try:
            transport, _ = await loop.create_datagram_endpoint(Proto, remote_addr=(info.ip, 137))
        except OSError:
            return
        try:
            transport.sendto(NBSTAT_QUERY)
            data = await asyncio.wait_for(got, self.read_timeout)
            info.netbios = parse_nbstat(data)
        except (TimeoutError, OSError, ValueError):
            pass
        finally:
            transport.close()

    # --- banners -------------------------------------------------------------

    async def _read(self, ip: str, port: int, send: bytes | None = None, tls: bool = False, until: bytes | None = None):
        """Up to BANNER_BYTES of what the host sends; for TLS also the certificate (DER)."""
        async with self._conn:
            try:
                r, w = await asyncio.wait_for(
                    asyncio.open_connection(ip, port, ssl=_tls() if tls else None), self.read_timeout
                )
            except (TimeoutError, OSError, ssl.SSLError):
                return b"", None
            cert = None
            if tls:
                sslobj = w.get_extra_info("ssl_object")
                with contextlib.suppress(Exception):
                    cert = sslobj.getpeercert(binary_form=True) if sslobj else None
            data = b""
            try:
                if send:
                    w.write(send)
                    await w.drain()
                loop = asyncio.get_running_loop()
                deadline = loop.time() + self.read_timeout
                while len(data) < BANNER_BYTES:
                    left = deadline - loop.time()
                    if left <= 0:
                        break
                    chunk = await asyncio.wait_for(r.read(4096), left)
                    if not chunk:
                        break
                    data += chunk
                    if until and until in data:
                        break
            except (TimeoutError, OSError, ssl.SSLError):
                pass
            finally:
                w.close()
                with contextlib.suppress(Exception):
                    await w.wait_closed()
            return data, cert

    async def _ssh(self, info: HostInfo, port: int = 22) -> None:
        data, _ = await self._read(info.ip, port, until=b"\n")
        line = data.split(b"\n", 1)[0].strip().decode("latin-1")
        if line.startswith("SSH-"):
            info.ssh = line[:120]

    async def _telnet(self, info: HostInfo, port: int = 23) -> None:
        data, _ = await self._read(info.ip, port)
        text = clean_text(strip_iac(data))
        if text:
            info.telnet = text[:200]

    async def _http(self, info: HostInfo, port: int, tls: bool, path: str = "/", hops: int = 1) -> None:
        request = (
            f"GET {path} HTTP/1.0\r\nHost: {info.ip}\r\nUser-Agent: NetAdmin-scan\r\n"
            "Accept: text/html\r\nConnection: close\r\n\r\n"
        ).encode()
        data, cert = await self._read(info.ip, port, send=request, tls=tls)
        if cert and not info.cert_name:
            info.cert_name = cert_name(cert)
        status, headers, body = parse_http(data)
        if not status:
            return
        info.http_server = info.http_server or headers.get("server")
        realm = re.search(r'realm="([^"]{1,80})"', headers.get("www-authenticate", ""))
        if realm and not info.http_realm:
            info.http_realm = realm.group(1)
        title = html_title(body)
        if title and not info.http_title:
            info.http_title = title
        # Cameras and printers often redirect / to their login page, which has the title.
        location = headers.get("location", "")
        if not title and hops and status in (301, 302, 303, 307, 308):
            target = same_host_path(location, info.ip)
            if target and target != path:
                await self._http(info, port, tls, target, hops - 1)


def _tls() -> ssl.SSLContext:
    # Only to read the certificate and the page title: the device's own certificate is
    # self-signed almost always, and nothing secret is sent over this connection.
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with contextlib.suppress(ValueError, ssl.SSLError):
        ctx.minimum_version = ssl.TLSVersion.TLSv1
        ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
    return ctx


# --- parsing (pure functions, tested without a network) ---------------------

# NetBIOS node status request for the wildcard name "*".
NBSTAT_QUERY = (
    struct.pack(">HHHHHH", 0x4E41, 0, 1, 0, 0, 0) + b"\x20" + b"CK" + b"AA" * 15 + b"\x00" + struct.pack(">HH", 0x21, 1)
)


def parse_nbstat(data: bytes) -> str | None:
    """The workstation name (suffix 0x00, unique) from a node status response."""
    if len(data) < 57:
        raise ValueError("short NBSTAT answer")
    # header 12, name 34, type 2, class 2, ttl 4, rdlength 2
    pos = 12 + 34 + 10
    count = data[pos]
    pos += 1
    for _ in range(count):
        entry = data[pos : pos + 18]
        if len(entry) < 18:
            break
        name, suffix, flags = entry[:15], entry[15], struct.unpack(">H", entry[16:18])[0]
        if suffix == 0 and not flags & 0x8000:
            text = name.decode("latin-1").strip()
            if text and text.isprintable():
                return text
        pos += 18
    return None


def strip_iac(data: bytes) -> bytes:
    """Telnet option negotiation (IAC ...) removed, so the login banner is left."""
    out = bytearray()
    i = 0
    while i < len(data):
        b = data[i]
        if b != 0xFF:
            out.append(b)
            i += 1
            continue
        cmd = data[i + 1] if i + 1 < len(data) else None
        if cmd == 0xFF:
            out.append(0xFF)
            i += 2
        elif cmd == 0xFA:
            end = data.find(b"\xff\xf0", i + 2)
            i = len(data) if end < 0 else end + 2
        elif cmd is not None and 0xFB <= cmd <= 0xFE:
            i += 3
        else:
            i += 2
    return bytes(out)


def clean_text(data: bytes) -> str:
    text = data.decode("utf-8", errors="replace")
    text = "".join(c if c.isprintable() or c in "\n\t" else " " for c in text)
    return " ".join(line.strip() for line in text.splitlines() if line.strip())


def parse_http(data: bytes) -> tuple[int | None, dict[str, str], bytes]:
    head, _, body = data.partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    m = re.match(r"HTTP/\d(?:\.\d)?\s+(\d{3})", lines[0]) if lines else None
    if not m:
        return None, {}, b""
    headers = {}
    for line in lines[1:]:
        k, sep, v = line.partition(":")
        if sep:
            headers.setdefault(k.strip().lower(), v.strip()[:200])
    return int(m.group(1)), headers, body


def html_title(body: bytes) -> str | None:
    m = re.search(rb"<title[^>]*>(.*?)</title", body, re.I | re.S)
    if not m:
        return None
    raw = m.group(1)
    charset = re.search(rb'charset=["\']?([\w-]+)', body[:2048], re.I)
    try:
        text = raw.decode(charset.group(1).decode() if charset else "utf-8")
    except (LookupError, UnicodeDecodeError):
        text = raw.decode("latin-1")
    text = re.sub(r"\s+", " ", text).strip()
    return text[:120] or None


def same_host_path(location: str, ip: str) -> str | None:
    if location.startswith("/") and not location.startswith("//"):
        return location[:200]
    m = re.match(r"https?://([^/:]+)(?::\d+)?(/.*)?$", location)
    if m and m.group(1) == ip:
        return (m.group(2) or "/")[:200]
    return None


def cert_name(der: bytes) -> str | None:
    """Subject CN and organisation of a device certificate: «HP LaserJet», «Hikvision», «sw-01»."""
    try:
        from cryptography import x509
        from cryptography.x509.oid import NameOID

        cert = x509.load_der_x509_certificate(der)
    except Exception:
        return None
    parts = []
    for oid in (NameOID.COMMON_NAME, NameOID.ORGANIZATION_NAME):
        for attr in cert.subject.get_attributes_for_oid(oid):
            value = str(attr.value).strip()
            if value and value not in parts:
                parts.append(value)
    return " · ".join(parts)[:120] or None


# --- the server's ARP cache --------------------------------------------------

_IP = re.compile(r"^\d+\.\d+\.\d+\.\d+$")
_MAC = re.compile(r"^[0-9a-fA-F]{2}([-:][0-9a-fA-F]{2}){5}$")


def parse_arp(text: str) -> dict[str, str]:
    """`arp -a` on Windows (any language: the columns are the same), or /proc/net/arp on Linux."""
    out: dict[str, str] = {}
    for line in text.splitlines():
        fields = line.split()
        if len(fields) >= 4 and fields[1].startswith("0x"):
            # /proc/net/arp: IP, HW type, Flags, HW address, Mask, Device. Without flag 0x2: not resolved.
            try:
                if not int(fields[2], 16) & 0x2:
                    continue
            except ValueError:
                continue
            ip, mac = fields[0], fields[3]
        elif len(fields) >= 2:
            ip, mac = fields[0], fields[1]
        else:
            continue
        if not _IP.match(ip) or not _MAC.match(mac):
            continue
        mac = mac.lower().replace("-", ":")
        if mac in ("00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff") or int(mac[:2], 16) & 1:
            continue
        out[ip] = mac
    return out


async def read_arp() -> dict[str, str]:
    """IP -> MAC from this server's ARP cache. Only hosts in the server's own subnets are there."""
    try:
        if sys.platform == "win32":
            proc = await asyncio.create_subprocess_exec(
                "arp", "-a", stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
            )
            out, _ = await asyncio.wait_for(proc.communicate(), 10)
            return parse_arp(out.decode("latin-1"))
        with open("/proc/net/arp", encoding="ascii", errors="replace") as f:
            return parse_arp(f.read())
    except Exception:
        log.debug("ARP cache unreadable", exc_info=True)
        return {}


def in_nets(ip: str, nets: list[ipaddress.IPv4Network]) -> bool:
    addr = ipaddress.ip_address(ip)
    return any(addr in n for n in nets)
