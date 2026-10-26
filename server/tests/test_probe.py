"""Scanning without SNMP: TCP probe, banners, names, ARP and the device type guess."""

import asyncio
import contextlib
import datetime
import ipaddress
import ssl
import struct

import pytest

from app.discovery import classify
from app.discovery.model import FoundDevice
from app.discovery.probe import (
    HostInfo,
    Prober,
    cert_name,
    html_title,
    parse_arp,
    parse_http,
    parse_nbstat,
    same_host_path,
    strip_iac,
)
from app.discovery.snmp import SnmpScanner

from .conftest import make_settings


@contextlib.asynccontextmanager
async def serve(handler, ssl_ctx=None):
    server = await asyncio.start_server(handler, "127.0.0.1", 0, ssl=ssl_ctx)
    try:
        yield server.sockets[0].getsockname()[1]
    finally:
        server.close()
        with contextlib.suppress(Exception):
            await server.wait_closed()


def free_port() -> int:
    import socket

    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


async def silent(_r, w):
    w.close()


async def no_arp() -> dict[str, str]:
    return {}


# --- TCP probe and banners against local servers -------------------------------


async def test_a_refused_port_still_means_someone_is_there():
    async with serve(silent) as open_port:
        prober = Prober(None, fast_ports=(open_port, free_port()), connect_timeout=1)
        info = await prober.alive("127.0.0.1")
    assert info.alive and not info.ping and info.open_ports == {open_port}


async def test_a_dead_address_is_not_alive():
    async def never(_ip):
        return False

    prober = Prober(never, fast_ports=())
    assert not (await prober.alive("192.0.2.1")).alive


async def test_ping_that_cannot_run_is_reported_once():
    calls = 0

    async def broken(_ip):
        nonlocal calls
        calls += 1
        raise FileNotFoundError("ping")

    prober = Prober(broken, fast_ports=())
    await prober.alive("127.0.0.1")
    await prober.alive("127.0.0.2")
    assert calls == 1 and "FileNotFoundError" in prober.ping_error


async def test_ssh_and_telnet_banners():
    async def ssh(_r, w):
        w.write(b"SSH-2.0-OpenSSH_9.2p1 Debian-2+deb12u3\r\n")
        await w.drain()
        w.close()

    async def telnet(_r, w):
        # Option negotiation first, as real switches do, then the prompt.
        w.write(b"\xff\xfb\x01\xff\xfb\x03\xff\xfa\x18\x01\xff\xf0\r\n\r\nUser Access Verification\r\n\r\nUsername: ")
        await w.drain()
        w.close()

    async with serve(ssh) as ssh_port, serve(telnet) as telnet_port:
        prober = Prober(None)
        info = HostInfo("127.0.0.1")
        await asyncio.gather(prober._ssh(info, ssh_port), prober._telnet(info, telnet_port))
    assert info.ssh == "SSH-2.0-OpenSSH_9.2p1 Debian-2+deb12u3"
    assert info.telnet == "User Access Verification Username:"


async def test_http_follows_a_redirect_to_the_login_page():
    requests = []

    async def http(r, w):
        head = (await r.readuntil(b"\r\n\r\n")).decode()
        requests.append(head.split("\r\n")[0])
        if head.startswith("GET / "):
            w.write(b"HTTP/1.1 302 Found\r\nLocation: /doc/page/login.asp\r\nServer: webs\r\n\r\n")
        else:
            body = b"<html><head><title>  Hikvision\n DS-2CD2143G2-I </title></head></html>"
            w.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" + body)
        await w.drain()
        w.close()

    async with serve(http) as port:
        info = HostInfo("127.0.0.1")
        await Prober(None)._http(info, port, tls=False)
    assert requests == ["GET / HTTP/1.0", "GET /doc/page/login.asp HTTP/1.0"]
    assert info.http_title == "Hikvision DS-2CD2143G2-I" and info.http_server == "webs"


async def test_https_reads_title_and_self_signed_certificate(tmp_path):
    cert_file, key_file = self_signed(tmp_path, "KM8F1A2B", "KYOCERA Document Solutions Inc.")
    ctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    ctx.load_cert_chain(cert_file, key_file)

    async def https(r, w):
        await r.readuntil(b"\r\n\r\n")
        w.write(b'HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm="ECOSYS M2540dn"\r\n\r\n')
        await w.drain()
        w.close()

    async with serve(https, ctx) as port:
        info = HostInfo("127.0.0.1")
        await Prober(None)._http(info, port, tls=True)
    assert info.cert_name == "KM8F1A2B · KYOCERA Document Solutions Inc."
    assert info.http_realm == "ECOSYS M2540dn"
    assert classify.classify(info, None)[0] == "PRINTER"


def self_signed(tmp_path, cn: str, org: str):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn), x509.NameAttribute(NameOID.ORGANIZATION_NAME, org)])
    now = datetime.datetime.now(datetime.UTC)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(1)
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=1))
        .sign(key, hashes.SHA256())
    )
    cert_file, key_file = tmp_path / "cert.pem", tmp_path / "key.pem"
    cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_file.write_bytes(
        key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    )
    return str(cert_file), str(key_file)


# --- the whole real scan, without ping and without SNMP --------------------------


async def test_real_scan_without_ping_or_snmp_still_finds_and_names_the_host():
    async def rdp(_r, w):
        w.close()

    async with serve(rdp) as port:
        prober = Prober(None, fast_ports=(port,), extra_ports=(), names=False)
        scanner = SnmpScanner(make_settings(snmp_timeout_seconds=0.3), pinger=None, prober=prober, arp=no_arp)
        seen = []
        result = await scanner.scan([ipaddress.ip_network("127.0.0.1/32")], "public", seen.append)
    assert seen == [1]
    (dev,) = result.devices
    assert dev.seen_by == "tcp" and dev.open_ports == [port] and not dev.snmp
    text = " ".join(result.warnings)
    assert "нет утилиты ping" in text
    assert "Сервер не в подсети 127.0.0.1/32" in text
    assert "(community public) не ответил никто" in text


async def test_a_scan_that_finds_nothing_says_where_to_look():
    async def never(_ip):
        return False

    prober = Prober(never, fast_ports=(), extra_ports=(), names=False)
    scanner = SnmpScanner(make_settings(), prober=prober, arp=no_arp)
    result = await scanner.scan([ipaddress.ip_network("192.0.2.0/30")], "s3cret-c0mm", lambda n: None)
    assert result.devices == []
    assert "Никто не ответил" in result.warnings[0]
    assert "s3cret" not in " ".join(result.warnings)


async def test_a_firewall_that_refuses_for_every_address_is_not_a_subnet_of_hosts():
    async def never(_ip):
        return False

    # All of 127.0.0.0/8 is this machine on Linux: a closed port is refused at every address.
    prober = Prober(never, fast_ports=(free_port(),), extra_ports=(), names=False)
    scanner = SnmpScanner(make_settings(snmp_timeout_seconds=0.2), prober=prober, arp=no_arp)
    result = await scanner.scan([ipaddress.ip_network("127.0.0.0/28")], "public", lambda n: None)
    assert result.devices == []
    assert "отвечает отказом за все адреса" in result.warnings[0]


async def test_a_host_only_the_arp_cache_knows_is_listed_with_its_vendor():
    async def never(_ip):
        return False

    async def arp():
        return {"192.0.2.1": "44:47:cc:12:34:56", "10.1.1.1": "00:11:22:33:44:55"}

    prober = Prober(never, fast_ports=(), extra_ports=(), names=False)
    scanner = SnmpScanner(make_settings(snmp_timeout_seconds=0.2), prober=prober, arp=arp)
    result = await scanner.scan([ipaddress.ip_network("192.0.2.0/30")], "public", lambda n: None)
    (dev,) = result.devices
    assert (dev.ip, dev.seen_by, dev.mac) == ("192.0.2.1", "arp", "44:47:cc:12:34:56")
    assert dev.vendor == "HIKVISION" and dev.device_type == "CAMERA"
    assert not any("Сервер не в подсети" in w for w in result.warnings)


# --- classification ----------------------------------------------------------------


def host(*ports, **kw) -> HostInfo:
    return HostInfo("10.0.0.9", ping=True, open_ports=set(ports), **kw)


@pytest.mark.parametrize(
    ("info", "vendor", "expected"),
    [
        (host(80, 443, 9100), "HP", "PRINTER"),
        (host(80), None, "UNKNOWN"),
        (host(80, http_title="TASKalfa 3253ci"), None, "PRINTER"),
        (host(80, 443), "BROTHER", "PRINTER"),
        (host(80, 554), "HIKVISION", "CAMERA"),
        (host(80, 554), None, "CAMERA"),
        (host(22, 80, 443), "UBIQUITI", "ACCESS_POINT"),
        (host(22, 80, 8291), None, "SWITCH"),
        (host(23, telnet="User Access Verification Username:"), None, "SWITCH"),
        (host(22, 80, 443), "ELTEX", "SWITCH"),
        (host(135, 445, 3389), "DELL", "WORKSTATION"),
        (host(135, 445, 3389, 5985), "DELL", "SERVER"),
        (host(53, 88, 135, 389, 445), None, "SERVER"),
        (host(135, 445, netbios="SRV-FILES"), None, "SERVER"),
        (host(443, 902), None, "SERVER"),
        (host(22, ssh="SSH-2.0-OpenSSH_9.2p1 Debian"), None, "SERVER"),
        # An HP laptop is not a printer.
        (host(135, 445), "HP", "WORKSTATION"),
    ],
)
def test_classify(info, vendor, expected):
    kind, evidence = classify.classify(info, vendor)
    assert kind == expected
    assert bool(evidence) == (expected != "UNKNOWN")


def test_apply_names_and_describes_a_host_without_snmp():
    dev = FoundDevice(ip="10.0.0.9", mac="00:17:c8:01:02:03", seen_by="tcp")
    info = host(80, 443, 9100, hostname="prn-sklad.corp.local", http_title="ECOSYS M2540dn")
    classify.apply(dev, info)
    assert (dev.name, dev.vendor, dev.device_type) == ("prn-sklad", "KYOCERA", "PRINTER")
    assert dev.open_ports == [80, 443, 9100] and dev.hostname == "prn-sklad.corp.local"
    assert "Порты: 80 HTTP, 443 HTTPS, 9100 RAW-печать" in dev.description
    assert "Веб: ECOSYS M2540dn" in dev.description


def test_snmp_type_wins_unless_ports_say_something_more_specific():
    switch = FoundDevice(ip="10.0.0.1", device_type="SWITCH", snmp=True, description="Cisco IOS")
    classify.apply(switch, host(22, 80, 443))
    assert switch.device_type == "SWITCH" and switch.description == "Cisco IOS"
    camera = FoundDevice(ip="10.0.0.2", device_type="SERVER", snmp=True, description="Linux 3.10")
    classify.apply(camera, host(80, 554, http_title="DS-2CD2143G2-I"))
    assert camera.device_type == "CAMERA"


def test_vendor_from_mac():
    assert classify.vendor_from_mac("a8:f9:4b:00:00:01") == "ELTEX"
    assert classify.vendor_from_mac("00:0c:29:aa:bb:cc") == "VMWARE"
    assert classify.vendor_from_mac("4c:5e:0c:00:00:00") == "MIKROTIK"
    # Locally administered (randomised phone MACs) are not in the IEEE list.
    assert classify.vendor_from_mac("da:a1:19:00:00:00") is None
    assert classify.vendor_from_mac(None) is None


# --- parsers -------------------------------------------------------------------------


def test_parse_arp_linux_and_windows():
    linux = (
        "IP address       HW type     Flags       HW address            Mask     Device\n"
        "10.10.20.1       0x1         0x2         a8:f9:4b:00:00:01     *        eth0\n"
        "10.10.20.7       0x1         0x0         00:00:00:00:00:00     *        eth0\n"
        "10.10.20.9       0x1         0x6         00:17:C8:5A:30:57     *        eth0\n"
    )
    assert parse_arp(linux) == {"10.10.20.1": "a8:f9:4b:00:00:01", "10.10.20.9": "00:17:c8:5a:30:57"}
    windows = (
        "\r\nИнтерфейс: 10.10.20.5 --- 0xb\r\n"
        "  адрес в Интернете      Физический адрес      Тип\r\n"
        "  10.10.20.1            a8-f9-4b-00-00-01     динамический\r\n"
        "  10.10.20.255          ff-ff-ff-ff-ff-ff     статический\r\n"
        "  224.0.0.22            01-00-5e-00-00-16     статический\r\n"
    )
    assert parse_arp(windows) == {"10.10.20.1": "a8:f9:4b:00:00:01"}


def test_parse_nbstat():
    def entry(name: str, suffix: int, flags: int) -> bytes:
        return name.encode().ljust(15) + bytes([suffix]) + struct.pack(">H", flags)

    names = entry("WORKGROUP", 0, 0x8400) + entry("PC-BUH-07", 0x20, 0x0400) + entry("PC-BUH-07", 0, 0x0400)
    data = b"\x00" * 12 + b"\x20" + b"A" * 32 + b"\x00" + b"\x00" * 10 + bytes([3]) + names
    assert parse_nbstat(data) == "PC-BUH-07"
    with pytest.raises(ValueError):
        parse_nbstat(b"\x00" * 10)


def test_http_helpers():
    status, headers, body = parse_http(b"HTTP/1.1 200 OK\r\nServer: Boa/0.94\r\n\r\n<TITLE>Web Image Monitor</TITLE>")
    assert status == 200 and headers["server"] == "Boa/0.94" and html_title(body) == "Web Image Monitor"
    assert parse_http(b"SSH-2.0-x\r\n") == (None, {}, b"")
    cyr = '<meta charset="windows-1251"><title>Принтер</title>'.encode("cp1251")
    assert html_title(cyr) == "Принтер"
    assert same_host_path("/login", "10.0.0.9") == "/login"
    assert same_host_path("http://10.0.0.9:80/ui/", "10.0.0.9") == "/ui/"
    assert same_host_path("https://evil.example/", "10.0.0.9") is None
    assert same_host_path("//evil.example/", "10.0.0.9") is None
    assert strip_iac(b"\xff\xfd\x18abc\xff\xffd") == b"abc\xffd"
    assert cert_name(b"not a certificate") is None
