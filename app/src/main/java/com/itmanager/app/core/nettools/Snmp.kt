package com.itmanager.app.core.nettools

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

/** Minimal BER encoder/decoder, just enough for SNMP v2c GET and GETBULK. */
object Ber {
    const val INTEGER = 0x02
    const val OCTET_STRING = 0x04
    const val NULL = 0x05
    const val OID = 0x06
    const val SEQUENCE = 0x30
    const val IP_ADDRESS = 0x40
    const val PDU_GET = 0xA0
    const val PDU_RESPONSE = 0xA2
    const val PDU_GETBULK = 0xA5
    const val NO_SUCH_OBJECT = 0x80
    const val NO_SUCH_INSTANCE = 0x81
    const val END_OF_MIB = 0x82

    fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    fun int(v: Long): ByteArray {
        val out = ArrayList<Byte>()
        var n = v
        do {
            out.add(0, (n and 0xFF).toByte())
            n = n shr 8
        } while (!((n == 0L && out[0] >= 0) || (n == -1L && out[0] < 0)))
        return tlv(INTEGER, out.toByteArray())
    }

    fun octets(b: ByteArray) = tlv(OCTET_STRING, b)

    fun oid(s: String): ByteArray {
        val p = s.trim('.').split('.').map { it.toLong() }
        val out = ArrayList<Byte>()
        fun sub(v: Long) {
            val tmp = ArrayList<Byte>()
            var x = v
            tmp.add(0, (x and 0x7F).toByte())
            x = x shr 7
            while (x > 0) { tmp.add(0, ((x and 0x7F) or 0x80).toByte()); x = x shr 7 }
            out.addAll(tmp)
        }
        sub(p[0] * 40 + p[1])
        for (i in 2 until p.size) sub(p[i])
        return tlv(OID, out.toByteArray())
    }

    class Node(val tag: Int, val buf: ByteArray, val start: Int, val end: Int) {
        val content: ByteArray get() = buf.copyOfRange(start, end)

        /** Children of a constructed node. */
        fun children(): List<Node> {
            val out = ArrayList<Node>()
            var pos = start
            while (pos < end) {
                val n = read(buf, pos, end)
                out += n.first
                pos = n.second
            }
            return out
        }
    }

    /** Reads one TLV at [pos]; returns the node and the position after it. */
    fun read(buf: ByteArray, pos: Int, limit: Int = buf.size): Pair<Node, Int> {
        require(pos + 2 <= limit) { "truncated" }
        val tag = buf[pos].toInt() and 0xFF
        var p = pos + 1
        var len = buf[p++].toInt() and 0xFF
        if (len >= 0x80) {
            val n = len and 0x7F
            require(n in 1..4 && p + n <= limit) { "bad length" }
            len = 0
            repeat(n) { len = (len shl 8) or (buf[p++].toInt() and 0xFF) }
        }
        require(len >= 0 && p + len <= limit) { "truncated" }
        return Node(tag, buf, p, p + len) to (p + len)
    }

    fun decodeOid(n: Node): String {
        val subs = ArrayList<Long>()
        var v = 0L
        for (i in n.start until n.end) {
            val b = n.buf[i].toInt() and 0xFF
            v = (v shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) { subs += v; v = 0 }
        }
        if (subs.isEmpty()) return ""
        val first = subs[0]
        val head = when {
            first < 40 -> listOf(0L, first)
            first < 80 -> listOf(1L, first - 40)
            else -> listOf(2L, first - 80)
        }
        return (head + subs.drop(1)).joinToString(".")
    }

    fun decodeLong(n: Node, signed: Boolean): Long {
        if (n.end == n.start) return 0
        var v = if (signed && n.buf[n.start] < 0) -1L else 0L
        for (i in n.start until n.end) v = (v shl 8) or (n.buf[i].toLong() and 0xFF)
        return v
    }
}

data class SnmpVar(val oid: String, val tag: Int, val raw: ByteArray) {
    val isEnd: Boolean get() = tag == Ber.NO_SUCH_OBJECT || tag == Ber.NO_SUCH_INSTANCE || tag == Ber.END_OF_MIB

    fun asLong(): Long? = when (tag) {
        Ber.INTEGER -> Ber.decodeLong(Ber.Node(tag, raw, 0, raw.size), true)
        0x41, 0x42, 0x43, 0x46 -> Ber.decodeLong(Ber.Node(tag, raw, 0, raw.size), false)
        else -> null
    }

    fun asText(): String = when (tag) {
        Ber.OCTET_STRING -> {
            val s = String(raw, Charsets.UTF_8)
            if (s.all { it == '\n' || it == '\r' || it == '\t' || it >= ' ' && it != '�' }) s.trim()
            else raw.joinToString(":") { "%02x".format(it) }
        }
        Ber.OID -> Ber.decodeOid(Ber.Node(tag, raw, 0, raw.size))
        Ber.IP_ADDRESS -> raw.joinToString(".") { (it.toInt() and 0xFF).toString() }
        else -> asLong()?.toString() ?: ""
    }

    /** Last arc(s) of the OID after [base]: "1.3.6.1.2.1.2.2.1.2.17" - base -> "17". */
    fun suffix(base: String): String = oid.removePrefix("$base.")
}

object SnmpCodec {
    fun request(pduTag: Int, community: String, requestId: Int, oids: List<String>, nonRepeaters: Int = 0, maxRepetitions: Int = 0): ByteArray {
        val binds = oids.map { Ber.tlv(Ber.SEQUENCE, Ber.oid(it) + Ber.tlv(Ber.NULL, ByteArray(0))) }
            .fold(ByteArray(0)) { a, b -> a + b }
        val second = if (pduTag == Ber.PDU_GETBULK) Ber.int(nonRepeaters.toLong()) else Ber.int(0)
        val third = if (pduTag == Ber.PDU_GETBULK) Ber.int(maxRepetitions.toLong()) else Ber.int(0)
        val pdu = Ber.tlv(pduTag, Ber.int(requestId.toLong()) + second + third + Ber.tlv(Ber.SEQUENCE, binds))
        return Ber.tlv(Ber.SEQUENCE, Ber.int(1) + Ber.octets(community.toByteArray()) + pdu) // version 1 = SNMPv2c
    }

    class Response(val requestId: Int, val errorStatus: Int, val vars: List<SnmpVar>)

    fun parseResponse(buf: ByteArray, length: Int = buf.size): Response? = try {
        val msg = Ber.read(buf, 0, length).first
        val pdu = msg.children()[2]
        if (pdu.tag != Ber.PDU_RESPONSE) {
            null
        } else {
            val f = pdu.children()
            val vars = f[3].children().map { vb ->
                val c = vb.children()
                SnmpVar(Ber.decodeOid(c[0]), c[1].tag, c[1].content)
            }
            Response(Ber.decodeLong(f[0], true).toInt(), Ber.decodeLong(f[1], true).toInt(), vars)
        }
    } catch (e: Exception) {
        null
    }
}

/** SNMP v2c over UDP, reads only. Methods return null on timeout or protocol errors. */
class SnmpClient(private val host: InetAddress, private val community: String, private val timeoutMs: Int = 900, private val retries: Int = 1) {

    private fun exchange(pduTag: Int, oids: List<String>, maxRep: Int = 0): SnmpCodec.Response? {
        val id = Random.nextInt(1, Int.MAX_VALUE)
        val data = SnmpCodec.request(pduTag, community, id, oids, 0, maxRep)
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            repeat(retries + 1) {
                try {
                    sock.send(DatagramPacket(data, data.size, InetSocketAddress(host, 161)))
                    val buf = ByteArray(8192)
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val r = SnmpCodec.parseResponse(buf, pkt.length)
                    if (r != null && r.requestId == id) return if (r.errorStatus == 0) r else null
                } catch (e: java.net.SocketTimeoutException) {
                    // retry
                } catch (e: Exception) {
                    return null
                }
            }
        }
        return null
    }

    fun get(vararg oids: String): List<SnmpVar>? = exchange(Ber.PDU_GET, oids.toList())?.vars

    /** Walks a subtree with GETBULK. Stops at the end of the subtree, the MIB, or [limit] entries. */
    fun walk(base: String, limit: Int = 3000): List<SnmpVar> {
        val out = ArrayList<SnmpVar>()
        var cur = base
        while (out.size < limit) {
            val vars = exchange(Ber.PDU_GETBULK, listOf(cur), maxRep = 20)?.vars ?: break
            if (vars.isEmpty()) break
            var done = false
            for (v in vars) {
                if (v.isEnd || !v.oid.startsWith("$base.")) { done = true; break }
                out += v
                cur = v.oid
            }
            if (done) break
        }
        return out
    }
}

data class SnmpRow(val oid: String, val name: String?, val value: String)
data class IfRow(val index: Int, val name: String, val alias: String, val up: Boolean?, val speedMbps: Long)

/** Interactive SNMP queries for the tools screen. */
object SnmpTools {
    private const val IF_DESCR = "1.3.6.1.2.1.2.2.1.2"
    private const val IF_OPER = "1.3.6.1.2.1.2.2.1.8"
    private const val IF_SPEED = "1.3.6.1.2.1.2.2.1.5"
    private const val IF_HIGH_SPEED = "1.3.6.1.2.1.31.1.1.1.15"
    private const val IF_NAME = "1.3.6.1.2.1.31.1.1.1.1"
    private const val IF_ALIAS = "1.3.6.1.2.1.31.1.1.1.18"

    /** Names for the OIDs people actually type; the longest matching prefix wins. */
    val KNOWN: Map<String, String> = mapOf(
        "1.3.6.1.2.1.1.1" to "sysDescr", "1.3.6.1.2.1.1.2" to "sysObjectID", "1.3.6.1.2.1.1.3" to "sysUpTime",
        "1.3.6.1.2.1.1.4" to "sysContact", "1.3.6.1.2.1.1.5" to "sysName", "1.3.6.1.2.1.1.6" to "sysLocation",
        "1.3.6.1.2.1.2.1" to "ifNumber", IF_DESCR to "ifDescr", "1.3.6.1.2.1.2.2.1.3" to "ifType", "1.3.6.1.2.1.2.2.1.4" to "ifMtu",
        IF_SPEED to "ifSpeed", "1.3.6.1.2.1.2.2.1.6" to "ifPhysAddress", "1.3.6.1.2.1.2.2.1.7" to "ifAdminStatus", IF_OPER to "ifOperStatus",
        "1.3.6.1.2.1.2.2.1.10" to "ifInOctets", "1.3.6.1.2.1.2.2.1.14" to "ifInErrors", "1.3.6.1.2.1.2.2.1.16" to "ifOutOctets",
        "1.3.6.1.2.1.2.2.1.20" to "ifOutErrors", IF_NAME to "ifName", "1.3.6.1.2.1.31.1.1.1.6" to "ifHCInOctets",
        "1.3.6.1.2.1.31.1.1.1.10" to "ifHCOutOctets", IF_HIGH_SPEED to "ifHighSpeed", IF_ALIAS to "ifAlias",
        "1.3.6.1.2.1.4.20.1.1" to "ipAdEntAddr", "1.3.6.1.2.1.4.21.1.7" to "ipRouteNextHop", "1.3.6.1.2.1.4.22.1.2" to "ipNetToMediaPhysAddress",
        "1.3.6.1.2.1.17.4.3.1.2" to "dot1dTpFdbPort", "1.3.6.1.2.1.25.1.1" to "hrSystemUptime", "1.3.6.1.2.1.25.2.3.1.5" to "hrStorageSize",
        "1.0.8802.1.1.2.1.4.1.1.9" to "lldpRemSysName", "1.3.6.1.4.1.9.9.23.1.2.1.1.6" to "cdpCacheDeviceId",
        "1.3.6.1.2.1.43.11.1.1.9" to "prtMarkerSuppliesLevel", "1.3.6.1.2.1.43.11.1.1.8" to "prtMarkerSuppliesMaxCapacity",
    )

    /** "ifDescr.3" for "1.3.6.1.2.1.2.2.1.2.3"; null if no known prefix matches. */
    fun describeOid(oid: String): String? {
        val o = oid.trim('.')
        var best: String? = null
        for (k in KNOWN.keys) if ((o == k || o.startsWith("$k.")) && (best == null || k.length > best.length)) best = k
        return best?.let { KNOWN[it] + o.removePrefix(it) }
    }

    /** Timeticks (1/100 s) -> "3 d 04:05:06". */
    fun formatTimeticks(ticks: Long): String {
        val total = ticks / 100
        val d = total / 86_400
        val hms = "%02d:%02d:%02d".format((total % 86_400) / 3600, (total % 3600) / 60, total % 60)
        return if (d > 0) "$d d $hms" else hms
    }

    fun ifStatusName(v: Long?): String = when (v?.toInt()) {
        1 -> "up"; 2 -> "down"; 3 -> "testing"; 4 -> "unknown"; 5 -> "dormant"; 6 -> "notPresent"; 7 -> "lowerLayerDown"; else -> "?"
    }

    fun format(v: SnmpVar): String = when {
        v.isEnd -> "—"
        v.tag == 0x43 -> v.asLong()?.let { "${formatTimeticks(it)}  ($it)" } ?: ""
        else -> v.asText()
    }

    private fun row(v: SnmpVar) = SnmpRow(v.oid, describeOid(v.oid), format(v))

    fun get(client: SnmpClient, oids: List<String>): List<SnmpRow>? = client.get(*oids.toTypedArray())?.map(::row)

    fun walk(client: SnmpClient, base: String, limit: Int = 500): List<SnmpRow> = client.walk(base.trim('.'), limit).map(::row)

    fun system(client: SnmpClient): List<SnmpRow>? =
        get(client, listOf("1.3.6.1.2.1.1.5.0", "1.3.6.1.2.1.1.1.0", "1.3.6.1.2.1.1.3.0", "1.3.6.1.2.1.1.4.0", "1.3.6.1.2.1.1.6.0", "1.3.6.1.2.1.1.2.0"))
            ?.filter { it.value != "—" && it.value.isNotBlank() }

    /** Interface table: name, alias (description), operational state and speed, joined by ifIndex. */
    fun interfaces(client: SnmpClient): List<IfRow> {
        fun text(base: String) = client.walk(base, 1000).associate { it.suffix(base).toIntOrNull() to it.asText() }
        fun num(base: String) = client.walk(base, 1000).associate { it.suffix(base).toIntOrNull() to it.asLong() }
        val names = text(IF_NAME).ifEmpty { text(IF_DESCR) }
        val aliases = text(IF_ALIAS)
        val oper = num(IF_OPER)
        val high = num(IF_HIGH_SPEED)
        val speed = if (high.isEmpty()) num(IF_SPEED) else emptyMap()
        return names.keys.filterNotNull().sorted().map { i ->
            val mbps = high[i] ?: speed[i]?.let { it / 1_000_000 } ?: 0L
            IfRow(i, names[i].orEmpty(), aliases[i].orEmpty(), oper[i]?.let { it == 1L }, mbps)
        }
    }
}
