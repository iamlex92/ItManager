package com.itmanager.app

import com.itmanager.app.core.nettools.Ber
import com.itmanager.app.core.nettools.Cidr
import com.itmanager.app.core.nettools.Iperf3Client
import com.itmanager.app.core.nettools.Iperf3Result
import com.itmanager.app.core.nettools.PingLine
import com.itmanager.app.core.nettools.Severity
import com.itmanager.app.core.nettools.SnmpCodec
import com.itmanager.app.core.nettools.SnmpTools
import com.itmanager.app.core.nettools.certSeverity
import com.itmanager.app.core.nettools.daysUntil
import com.itmanager.app.core.nettools.ipv4ToString
import com.itmanager.app.core.nettools.isLocallyAdministered
import com.itmanager.app.core.nettools.isMulticastMac
import com.itmanager.app.core.nettools.macHex
import com.itmanager.app.core.nettools.macToString
import com.itmanager.app.core.nettools.magicPacket
import com.itmanager.app.core.nettools.nbstatQuery
import com.itmanager.app.core.nettools.ouiLookup
import com.itmanager.app.core.nettools.parseMac
import com.itmanager.app.core.nettools.parseNbstat
import com.itmanager.app.core.nettools.parseOui
import com.itmanager.app.core.nettools.parsePingLine
import com.itmanager.app.core.nettools.parsePorts
import com.itmanager.app.core.nettools.parseSshBanner
import com.itmanager.app.core.nettools.pingStats
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetToolsTest {
    @Test fun pingReplyLine() {
        val r = parsePingLine("64 bytes from 8.8.8.8: icmp_seq=1 ttl=116 time=12.3 ms") as PingLine.Reply
        assertEquals("8.8.8.8", r.from); assertEquals(116, r.ttl); assertEquals(12.3, r.rttMs, 0.001)
        val v6 = parsePingLine("64 bytes from fe80::1: icmp_seq=1 hlim=64 time=2.0 ms") as PingLine.Reply
        assertEquals("fe80::1", v6.from); assertEquals(64, v6.ttl)
    }

    @Test fun pingErrorLines() {
        val e = parsePingLine("From 192.168.1.1 icmp_seq=1 Time to live exceeded") as PingLine.Error
        assertEquals("192.168.1.1", e.from); assertTrue(e.ttlExceeded)
        val u = parsePingLine("From 10.1.1.1: icmp_seq=1 Destination Host Unreachable") as PingLine.Error
        assertFalse(u.ttlExceeded)
        assertNull(parsePingLine("PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data."))
    }

    @Test fun statistics() {
        val s = pingStats(listOf(10.0, null, 20.0, 30.0))
        assertEquals(3, s.received); assertEquals(25, s.lossPercent)
        assertEquals(20.0, s.avgMs!!, 0.001); assertEquals(10.0, s.jitterMs!!, 0.001)
        assertEquals(100, pingStats(listOf(null, null)).lossPercent)
        assertEquals(0, pingStats(emptyList()).lossPercent)
    }

    @Test fun portSpecs() {
        assertEquals(listOf(22, 80, 443), parsePorts("443, 22 80,80"))
        assertEquals(listOf(1, 2, 3, 22), parsePorts("1-3,22"))
        assertNull(parsePorts("0")); assertNull(parsePorts("70000")); assertNull(parsePorts("10-5")); assertNull(parsePorts("a,b")); assertNull(parsePorts(""))
        assertEquals(1024, parsePorts("1-1024")!!.size)
    }

    @Test fun macParsingAndMagicPacket() {
        val expected = byteArrayOf(0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E)
        for (s in listOf("00:1A:2B:3C:4D:5E", "00-1a-2b-3c-4d-5e", "001a.2b3c.4d5e", "001A2B3C4D5E")) assertArrayEquals(s, expected, parseMac(s))
        assertNull(parseMac("00:1A:2B:3C:4D")); assertNull(parseMac("zz:1A:2B:3C:4D:5E"))
        assertEquals("00:1A:2B:3C:4D:5E", macToString(expected))
        val p = magicPacket(expected)
        assertEquals(102, p.size)
        assertTrue(p.take(6).all { it == 0xFF.toByte() })
        assertArrayEquals(expected, p.copyOfRange(96, 102))
    }

    @Test fun ouiLookupPrefersMostSpecificBlock() {
        val t = parseOui("001A2B\tAyecom\nAABBCCD\tSmall\nAABBCCDDE\tTiny\nAABBCC\tBig\n\nbroken")
        assertEquals(4, t.size)
        assertEquals("Ayecom", ouiLookup(t, "001A2B3C4D5E"))
        assertEquals("Tiny", ouiLookup(t, "AABBCCDDE123"))
        assertEquals("Small", ouiLookup(t, "AABBCCD00000"))
        assertEquals("Big", ouiLookup(t, "AABBCC000000"))
        assertNull(ouiLookup(t, "123456"))
    }

    @Test fun macHexAndFlags() {
        assertEquals("001A2B3C4D5E", macHex("00-1a-2b-3c-4d-5e"))
        assertNull(macHex("00:1A")); assertNull(macHex("zz:zz:zz")); assertNull(macHex("00:1A:2B:3C:4D:5E:6F"))
        assertFalse(isLocallyAdministered("001A2B3C4D5E"))
        assertTrue(isLocallyAdministered("DA1A2B3C4D5E"))
        assertTrue(isMulticastMac("01005E000001"))
    }

    @Test fun bundledDatabaseIsReadable() {
        val file = java.io.File("src/main/assets/oui.tsv")
        assertTrue("oui.tsv must be bundled", file.exists())
        val t = parseOui(file.readText())
        assertTrue(t.size > 30_000)
        assertNotNull(ouiLookup(t, "F4F5D8000000"))
        assertTrue(ouiLookup(t, "000C29000000")!!.contains("VMware", true))
    }

    private fun nbResponse(compressedName: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x4E, 0x4D, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0))
        if (compressedName) out.write(byteArrayOf(0xC0.toByte(), 0x0C)) else { out.write(0x20); out.write("CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".toByteArray()); out.write(0) }
        out.write(byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0, 0, 0))
        out.write(2)
        fun name(n: String, suffix: Int, flags: Int) { out.write(n.padEnd(15).toByteArray()); out.write(suffix); out.write(flags shr 8); out.write(flags and 0xFF) }
        name("DESKTOP-01", 0x00, 0x0400)
        name("WORKGROUP", 0x00, 0x8400)
        out.write(byteArrayOf(0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E))
        return out.toByteArray()
    }

    @Test fun nbstat() {
        assertEquals(50, nbstatQuery().size)
        for (compressed in listOf(true, false)) {
            val info = parseNbstat(nbResponse(compressed))!!
            assertEquals("DESKTOP-01", info.hostName); assertEquals("WORKGROUP", info.workgroup); assertEquals("00:1A:2B:3C:4D:5E", info.mac)
        }
        assertNull(parseNbstat(ByteArray(10)))
        assertNull(parseNbstat(nbResponse(true).copyOf(60)))
    }

    @Test fun subnets() {
        val c = Cidr.parse("192.168.10.77/26")!!
        assertEquals("192.168.10.64", ipv4ToString(c.network)); assertEquals("192.168.10.127", ipv4ToString(c.broadcast))
        assertEquals(62L, c.usableHosts)
        assertEquals(24, Cidr.parse("10.0.0.0 255.255.255.0")!!.prefix)
        assertEquals(32, Cidr.parse("10.0.0.1")!!.prefix)
        assertNull(Cidr.parse("10.0.0.0 255.0.255.0")); assertNull(Cidr.parse("10.0.0.0/33")); assertNull(Cidr.parse("nope"))
    }

    @Test fun certificates() {
        val day = 86_400_000L
        assertEquals(10, daysUntil(1_000 * day + 10 * day + 5, 1_000 * day))
        assertEquals(-1, daysUntil(1_000 * day - 1, 1_000 * day))
        assertEquals(Severity.OK, certSeverity(120)); assertEquals(Severity.WARN, certSeverity(30)); assertEquals(Severity.FAIL, certSeverity(-1))
        assertEquals("SSH-2.0-OpenSSH_9.2", parseSshBanner("SSH-2.0-OpenSSH_9.2\r\n")); assertNull(parseSshBanner("HTTP/1.1 400"))
    }

    @Test fun snmpCodec() {
        assertArrayEquals(byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x02, 0x01, 0x01, 0x05, 0x00), Ber.oid("1.3.6.1.2.1.1.5.0"))
        val vb = Ber.tlv(Ber.SEQUENCE, Ber.oid("1.3.6.1.2.1.1.5.0") + Ber.octets("core-sw".toByteArray()))
        val pdu = Ber.tlv(Ber.PDU_RESPONSE, Ber.int(77) + Ber.int(0) + Ber.int(0) + Ber.tlv(Ber.SEQUENCE, vb))
        val r = SnmpCodec.parseResponse(Ber.tlv(Ber.SEQUENCE, Ber.int(1) + Ber.octets("public".toByteArray()) + pdu))!!
        assertEquals(77, r.requestId); assertEquals("core-sw", r.vars.single().asText())
        assertNull(SnmpCodec.parseResponse(byteArrayOf(0x30, 0x05, 0x01)))
    }

    @Test fun snmpNames() {
        assertEquals("sysDescr.0", SnmpTools.describeOid("1.3.6.1.2.1.1.1.0"))
        assertEquals("ifHCInOctets.3", SnmpTools.describeOid(".1.3.6.1.2.1.31.1.1.1.6.3"))
        assertNull(SnmpTools.describeOid("1.3.6.1.4.1.99999.1"))
        assertEquals("3 d 04:05:06", SnmpTools.formatTimeticks(((3 * 86_400L) + 4 * 3600 + 5 * 60 + 6) * 100))
        assertEquals("down", SnmpTools.ifStatusName(2))
    }

    @Test fun iperf() {
        val c = Iperf3Client.newCookie()
        assertEquals(37, c.size); assertEquals(0, c[36].toInt())
        val p = Iperf3Client.paramsJson(7, 2, true, 131072)
        assertTrue(p.contains("\"time\":7") && p.contains("\"parallel\":2") && p.contains("\"reverse\":true"))
        assertFalse(Iperf3Client.paramsJson(7, 1, false, 1024).contains("reverse"))
        assertEquals(3500L, Iperf3Client.totalBytes(Iperf3Client.resultsJson(listOf(1000L, 2500L), 5.0, sender = true)))
        assertNull(Iperf3Client.totalBytes("not json"))
        val up = Iperf3Result(false, 10.0, 125_000_000, 100_000_000, emptyList())
        assertEquals(80.0, up.receiverMbps, 0.01)
        val down = Iperf3Result(true, 10.0, 62_500_000, 125_000_000, emptyList())
        assertEquals(50.0, down.receiverMbps, 0.01)
    }
}
