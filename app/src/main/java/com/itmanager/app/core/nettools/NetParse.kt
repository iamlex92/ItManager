package com.itmanager.app.core.nettools

// Pure parsers and helpers for the phone's own network tools. No Android types here, so all of it is unit-tested.

// ---- IPv4 and subnets ----

fun parseIpv4(s: String): Long? {
    val parts = s.trim().split('.')
    if (parts.size != 4) return null
    var r = 0L
    for (part in parts) {
        val n = part.toIntOrNull() ?: return null
        if (n !in 0..255) return null
        r = (r shl 8) or n.toLong()
    }
    return r
}

fun ipv4ToString(v: Long): String =
    "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

data class Cidr(val ip: Long, val prefix: Int) {
    val mask: Long get() = if (prefix == 0) 0 else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    val network: Long get() = ip and mask
    val broadcast: Long get() = network or (mask.inv() and 0xFFFFFFFFL)
    val wildcard: Long get() = mask.inv() and 0xFFFFFFFFL
    val totalAddresses: Long get() = 1L shl (32 - prefix)
    val usableHosts: Long get() = when (prefix) { 32 -> 1; 31 -> 2; else -> totalAddresses - 2 }
    val firstHost: Long get() = if (prefix >= 31) network else network + 1
    val lastHost: Long get() = if (prefix >= 31) broadcast else broadcast - 1

    fun contains(addr: Long) = (addr and mask) == network

    companion object {
        /** "10.0.0.5/24", "10.0.0.5 255.255.255.0" or a bare address (taken as /32). */
        fun parse(s: String): Cidr? {
            val t = s.trim()
            val parts = t.split('/', ' ').filter { it.isNotBlank() }
            val ip = parseIpv4(parts.firstOrNull() ?: return null) ?: return null
            if (parts.size == 1) return Cidr(ip, 32)
            if (parts.size != 2) return null
            val p = parts[1].toIntOrNull() ?: maskToPrefix(parts[1]) ?: return null
            if (p !in 0..32) return null
            return Cidr(ip, p)
        }
    }
}

/** "255.255.255.0" -> 24; null if it is not a contiguous mask. */
fun maskToPrefix(mask: String): Int? {
    val m = parseIpv4(mask) ?: return null
    val bits = java.lang.Long.bitCount(m)
    return if (bits == 0 && m == 0L || (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL == m) bits else null
}

// ---- ping output ----

/** One parsed line of the system `ping` output. */
sealed interface PingLine {
    data class Reply(val from: String, val ttl: Int?, val rttMs: Double) : PingLine

    /** An ICMP error such as "Time to live exceeded" or "Destination Host Unreachable". */
    data class Error(val from: String, val message: String) : PingLine {
        val ttlExceeded: Boolean get() = message.contains("time to live", true) || message.contains("ttl", true)
    }
}

private val REPLY_RE = Regex("""bytes from ([0-9a-fA-F:.]+?):?\s.*?(?:ttl|hlim)=(\d+).*?time[=<]([\d.]+)\s*ms""", RegexOption.IGNORE_CASE)
private val REPLY_NO_TTL_RE = Regex("""bytes from ([0-9a-fA-F:.]+?):?\s.*?time[=<]([\d.]+)\s*ms""", RegexOption.IGNORE_CASE)
private val ERROR_RE = Regex("""^From ([0-9a-fA-F:.]+?):?\s+icmp_seq=\d+\s+(.+)$""", RegexOption.IGNORE_CASE)

fun parsePingLine(line: String): PingLine? {
    val l = line.trim()
    REPLY_RE.find(l)?.let { return PingLine.Reply(it.groupValues[1], it.groupValues[2].toIntOrNull(), it.groupValues[3].toDouble()) }
    REPLY_NO_TTL_RE.find(l)?.let { return PingLine.Reply(it.groupValues[1], null, it.groupValues[2].toDouble()) }
    ERROR_RE.find(l)?.let { return PingLine.Error(it.groupValues[1], it.groupValues[2].trim()) }
    return null
}

data class PingStats(val sent: Int, val received: Int, val minMs: Double?, val avgMs: Double?, val maxMs: Double?, val jitterMs: Double?) {
    val lossPercent: Int get() = if (sent == 0) 0 else Math.round((sent - received) * 100.0 / sent).toInt()
}

/** [rtts] holds one entry per probe: the round trip in ms, or null for a lost probe. */
fun pingStats(rtts: List<Double?>): PingStats {
    val ok = rtts.filterNotNull()
    if (ok.isEmpty()) return PingStats(rtts.size, 0, null, null, null, null)
    val jitter = if (ok.size < 2) 0.0 else ok.zipWithNext { a, b -> Math.abs(b - a) }.average()
    return PingStats(rtts.size, ok.size, ok.min(), ok.average(), ok.max(), jitter)
}

fun formatMs(ms: Double): String = if (ms < 10) "%.1f".format(java.util.Locale.US, ms) else "%.0f".format(java.util.Locale.US, ms)

// ---- TCP ports ----

/** "22,80,8000-8010" -> sorted distinct ports; null when malformed or longer than [max]. */
fun parsePorts(spec: String, max: Int = 10_000): List<Int>? {
    val out = sortedSetOf<Int>()
    for (raw in spec.split(',', ' ', ';')) {
        val part = raw.trim()
        if (part.isEmpty()) continue
        val range = part.split('-')
        when (range.size) {
            1 -> out += (part.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null)
            2 -> {
                val a = range[0].trim().toIntOrNull() ?: return null
                val b = range[1].trim().toIntOrNull() ?: return null
                if (a !in 1..65535 || b !in 1..65535 || b < a || b - a >= max) return null
                for (p in a..b) out += p
            }
            else -> return null
        }
        if (out.size > max) return null
    }
    return out.toList().takeIf { it.isNotEmpty() }
}

val SERVICE_NAMES: Map<Int, String> = mapOf(
    20 to "FTP data", 21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS", 67 to "DHCP", 69 to "TFTP", 80 to "HTTP",
    88 to "Kerberos", 110 to "POP3", 111 to "RPC", 123 to "NTP", 135 to "MS RPC", 139 to "NetBIOS", 143 to "IMAP", 161 to "SNMP",
    179 to "BGP", 389 to "LDAP", 443 to "HTTPS", 445 to "SMB", 465 to "SMTPS", 502 to "Modbus", 514 to "Syslog", 515 to "LPD", 587 to "SMTP",
    631 to "IPP", 636 to "LDAPS", 830 to "NETCONF", 873 to "rsync", 902 to "VMware", 993 to "IMAPS", 995 to "POP3S", 1080 to "SOCKS",
    1194 to "OpenVPN", 1433 to "MS SQL", 1521 to "Oracle", 1723 to "PPTP", 1883 to "MQTT", 2049 to "NFS", 2222 to "SSH-alt", 2375 to "Docker",
    3128 to "Proxy", 3306 to "MySQL", 3389 to "RDP", 3478 to "STUN", 4443 to "HTTPS-alt", 4840 to "OPC UA", 5060 to "SIP", 5432 to "PostgreSQL",
    5900 to "VNC", 5985 to "WinRM", 5986 to "WinRM HTTPS", 6379 to "Redis", 8000 to "HTTP-alt", 8006 to "Proxmox", 8080 to "HTTP-alt",
    8291 to "Winbox", 8443 to "HTTPS-alt", 8728 to "MikroTik API", 8883 to "MQTTS", 9000 to "Portainer", 9090 to "Prometheus",
    9100 to "JetDirect", 9200 to "Elasticsearch", 10000 to "Webmin", 10050 to "Zabbix agent", 10051 to "Zabbix", 27017 to "MongoDB",
    44818 to "EtherNet/IP",
)

/** Ready-made port lists: label to spec. */
val PORT_PRESETS: List<Pair<String, String>> = listOf(
    "Частые" to "21,22,23,25,53,80,110,135,139,143,161,389,443,445,515,631,993,995,1433,3306,3389,5432,5900,8080,8443,9100",
    "Управление" to "22,23,80,443,161,830,2222,8080,8291,8443,8728,10000",
    "Windows" to "88,135,139,389,445,636,3268,3389,5985,5986",
    "АСУ ТП" to "102,502,4840,20000,44818,47808",
    "1–1024" to "1-1024",
)

// ---- MAC addresses ----

/** 00:1A:2b..., 00-1a-2b..., 001a.2b3c.4d5e or plain hex -> six bytes; null if not a MAC. */
fun parseMac(s: String): ByteArray? {
    val hex = s.filter { it !in ":-. \t" }
    if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun macToString(b: ByteArray): String = b.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

/** Hex digits of a MAC or MAC prefix (at least the 6 digits of the OUI), upper case; null if it is not one. */
fun macHex(s: String): String? {
    val hex = s.filter { it !in ":-. \t" }.uppercase()
    if (hex.length !in 6..12 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
    return hex
}

/** Bit 1 of the first octet: the address was made up by the device (phones use random MACs per network). */
fun isLocallyAdministered(hex: String): Boolean = (hex.substring(0, 2).toInt(16) and 0x02) != 0
fun isMulticastMac(hex: String): Boolean = (hex.substring(0, 2).toInt(16) and 0x01) != 0

/** Wake-on-LAN magic packet: 6 x 0xFF, then the MAC 16 times. */
fun magicPacket(mac: ByteArray): ByteArray {
    require(mac.size == 6)
    val out = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
    for (i in 0 until 16) System.arraycopy(mac, 0, out, 6 + i * 6, 6)
    return out
}

// ---- OUI database ----

/** "AABBCC<TAB>Vendor" per line (24-bit blocks), "AABBCCD" (28-bit) and "AABBCCDDE" (36-bit) for the smaller IEEE blocks. */
fun parseOui(text: String): Map<String, String> {
    val map = HashMap<String, String>(70_000)
    for (line in text.lineSequence()) {
        val tab = line.indexOf('\t')
        if (tab < 6) continue
        map[line.substring(0, tab)] = line.substring(tab + 1).trim()
    }
    return map
}

/** Most specific match first: the 36-bit and 28-bit blocks before the 24-bit OUI. */
fun ouiLookup(table: Map<String, String>, hex: String): String? =
    (if (hex.length >= 9) table[hex.substring(0, 9)] else null)
        ?: (if (hex.length >= 7) table[hex.substring(0, 7)] else null)
        ?: table[hex.substring(0, 6)]

// ---- NetBIOS ----

/** NetBIOS node-status request ("what are your names?") for UDP port 137. */
fun nbstatQuery(txId: Int = 0x4E4D): ByteArray {
    val q = ArrayList<Byte>()
    fun u16(v: Int) { q += (v shr 8).toByte(); q += v.toByte() }
    u16(txId); u16(0); u16(1); u16(0); u16(0); u16(0)
    q += 0x20
    // "*" followed by 15 NULs, first-level encoded: every nibble becomes 'A' + nibble
    val name = ByteArray(16).also { it[0] = '*'.code.toByte() }
    for (b in name) {
        q += ('A'.code + ((b.toInt() and 0xFF) shr 4)).toByte()
        q += ('A'.code + (b.toInt() and 0x0F)).toByte()
    }
    q += 0
    u16(0x0021); u16(1)
    return q.toByteArray()
}

data class NbName(val name: String, val suffix: Int, val group: Boolean)

data class NbInfo(val names: List<NbName>, val mac: String?) {
    /** The machine name: the unique name with suffix 00 (workstation service). */
    val hostName: String? get() = names.firstOrNull { it.suffix == 0 && !it.group }?.name
    val workgroup: String? get() = names.firstOrNull { it.suffix == 0 && it.group }?.name
}

fun parseNbstat(b: ByteArray, len: Int = b.size): NbInfo? {
    if (len < 12 + 2 + 10 + 1) return null
    var pos = 12
    // the answer name is either a compression pointer or a full label sequence
    if ((b[pos].toInt() and 0xC0) == 0xC0) {
        pos += 2
    } else {
        while (pos < len && b[pos].toInt() != 0) pos += (b[pos].toInt() and 0xFF) + 1
        pos += 1
    }
    pos += 10 // type, class, ttl, rdlength
    if (pos >= len) return null
    val count = b[pos].toInt() and 0xFF
    pos++
    if (count == 0 || pos + count * 18 > len) return null
    val names = ArrayList<NbName>()
    repeat(count) {
        val raw = String(b, pos, 15, Charsets.ISO_8859_1).trimEnd(' ', '\u0000')
        val suffix = b[pos + 15].toInt() and 0xFF
        val flags = ((b[pos + 16].toInt() and 0xFF) shl 8) or (b[pos + 17].toInt() and 0xFF)
        names += NbName(raw, suffix, flags and 0x8000 != 0)
        pos += 18
    }
    val mac = if (pos + 6 <= len) b.copyOfRange(pos, pos + 6).takeIf { m -> m.any { it.toInt() != 0 } }?.let(::macToString) else null
    return NbInfo(names, mac)
}

// ---- service checks ----

enum class Severity { OK, WARN, FAIL }

/** One line of a service check, e.g. "SSH" -> "SSH-2.0-OpenSSH_9.2". */
data class ServiceResult(val service: String, val severity: Severity, val detail: String)

/** Certificates warn this many days before they expire. */
const val CERT_WARN_DAYS = 30

/** "SSH-2.0-OpenSSH_9.2p1 Debian" -> that line, or null if the server did not speak SSH. */
fun parseSshBanner(line: String?): String? = line?.trim()?.takeIf { it.startsWith("SSH-") }?.take(120)

/** Whole days from [now] to [notAfterMillis]; negative when already expired. */
fun daysUntil(notAfterMillis: Long, now: Long): Int = Math.floorDiv(notAfterMillis - now, 86_400_000L).toInt()

fun certSeverity(days: Int): Severity = when {
    days < 0 -> Severity.FAIL
    days <= CERT_WARN_DAYS -> Severity.WARN
    else -> Severity.OK
}
