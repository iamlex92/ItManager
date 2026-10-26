package com.itmanager.app

import com.itmanager.app.core.network.AuditDto
import com.itmanager.app.core.network.ScanDto
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.LinkSource
import com.itmanager.app.data.model.ScanRanges
import com.itmanager.app.data.model.ScanStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test
    fun rangesAreNormalizedAndDeduplicated() {
        assertEquals(
            listOf("10.99.0.0/24", "192.168.1.0/28", "172.16.5.7/32"),
            ScanRanges.parse("10.99.0.17/24, 10.99.0.0/24\n192.168.1.5/28; 172.16.5.7").getOrThrow(),
        )
    }

    @Test
    fun publicLargeAndBrokenRangesAreRefused() {
        fun error(text: String) = ScanRanges.parse(text).exceptionOrNull()?.message.orEmpty()
        assertTrue(error("").contains("хотя бы одну"))
        assertTrue(error("8.8.8.0/24").contains("внутренние"))
        assertTrue(error("172.32.0.0/24").contains("внутренние"))
        assertTrue(error("10.0.0.0/16").contains("/22"))
        assertTrue(error("10.0.0.300/24").contains("не похоже"))
        assertTrue(error("10.0.0.0/24/1").contains("не похоже"))
        assertTrue(error((0..8).joinToString { "10.0.$it.0/24" }).contains("8 подсетей"))
        assertTrue(error("10.0.0.0/22, 10.0.4.0/22, 10.0.8.0/22").contains("2048"))
    }

    @Test
    fun hostsSkipNetworkAndBroadcast() {
        val hosts = ScanRanges.hosts("10.99.0.0/29")
        assertEquals(listOf("10.99.0.1", "10.99.0.2", "10.99.0.3", "10.99.0.4", "10.99.0.5", "10.99.0.6"), hosts)
        assertEquals(254, ScanRanges.hosts("10.10.30.0/24").size)
        assertEquals(listOf("10.0.0.5"), ScanRanges.hosts("10.0.0.5/32"))
        assertTrue(ScanRanges.contains("10.20.0.0/23", "10.20.1.77"))
        assertFalse(ScanRanges.contains("10.20.0.0/23", "10.20.2.1"))
    }

    @Test
    fun scanFromServer() {
        val scan = json.decodeFromString(
            ScanDto.serializer(),
            """
            {"id": "s1", "status": "done", "mode": "emulator", "subnets": ["10.99.0.0/24"],
             "started_at": "2026-10-25T10:00:00Z", "finished_at": "2026-10-25T10:00:03Z", "total": 254, "probed": 254,
             "devices": [
               {"ip": "10.99.0.1", "name": "SW-CORE-01", "type": "SWITCH", "snmp": true,
                "inventory_id": "d1", "inventory_name": "SW-CORE-01"},
               {"ip": "10.99.0.14", "name": "SW-ACC-04", "type": "SWITCH", "snmp": true, "vendor": "ELTEX"},
               {"ip": "10.10.10.231", "name": null, "mac": "3c:52:82:aa:10:e7", "type": "UNKNOWN", "snmp": false}
             ],
             "links": [
               {"a": {"name": "SW-CORE-01", "port": "Te1/1/4", "ip": "10.99.0.1", "inventory_id": "d1"},
                "b": {"name": "SW-ACC-04", "port": "te1/0/1", "ip": "10.99.0.14"}, "source": "lldp", "known": false},
               {"a": {"name": "SW-ACC-03", "port": "Gi1/0/9", "ip": "10.99.0.13"},
                "b": {"name": "", "port": "", "ip": "10.10.10.231"}, "source": "fdb", "known": false}
             ],
             "error": null}
            """.trimIndent(),
        ).toDomain()
        assertEquals(ScanStatus.DONE, scan.status)
        assertTrue(scan.emulated)
        assertEquals(1f, scan.progress)
        assertEquals(listOf("10.99.0.14", "10.10.10.231"), scan.newDevices.map { it.ip })
        assertEquals("10.10.10.231", scan.devices.last().displayName)
        assertEquals(DeviceType.UNKNOWN, scan.devices.last().type)
        assertEquals(LinkSource.LLDP, scan.links.first().source)
        assertEquals("SW-CORE-01 Te1/1/4", scan.links.first().a.label)
        // A host learned from a MAC table has no name or port of its own: it shows as its address.
        assertEquals("10.10.10.231", scan.links.last().b.label)
        assertEquals(2, scan.newLinks.size)
    }

    @Test
    fun hostFoundWithoutSnmpCarriesPortsAndWarnings() {
        val scan = json.decodeFromString(
            ScanDto.serializer(),
            """
            {"id": "s2", "status": "done", "mode": "real", "total": 254, "probed": 254,
             "devices": [{"ip": "10.20.0.77", "type": "WORKSTATION", "vendor": "HP", "snmp": false, "seen_by": "tcp",
                          "open_ports": [135, 445, 3389], "hostname": "pc-buh-07.corp.local"}],
             "warnings": ["На сервере нет утилиты ping: устройства ищутся по TCP-портам и ARP."]}
            """.trimIndent(),
        ).toDomain()
        val pc = scan.devices.single()
        assertEquals(DeviceType.WORKSTATION, pc.type)
        assertEquals("tcp", pc.seenBy)
        assertEquals(listOf(135, 445, 3389), pc.openPorts)
        assertEquals("pc-buh-07.corp.local", pc.hostname)
        assertEquals(1, scan.warnings.size)
        // An older server sends neither: defaults keep working.
        val old = json.decodeFromString(ScanDto.serializer(), """{"id": "s", "devices": [{"ip": "10.0.0.1"}]}""").toDomain()
        assertEquals(emptyList<Int>(), old.devices.single().openPorts)
        assertEquals(emptyList<String>(), old.warnings)
    }

    @Test
    fun runningScanReportsProgress() {
        val scan = json.decodeFromString(ScanDto.serializer(), """{"id": "s", "status": "running", "mode": "real", "total": 200, "probed": 50}""")
            .toDomain()
        assertEquals(ScanStatus.RUNNING, scan.status)
        assertFalse(scan.emulated)
        assertEquals(0.25f, scan.progress)
    }

    @Test
    fun networkAuditRows() {
        val entry = json.decodeFromString(
            AuditDto.serializer(),
            """{"id": "a", "action": "network_scan", "kind": "network", "title": "Сканирование сети",
                "parameters": {"subnets": ["10.99.0.0/24", "10.10.30.0/24"], "mode": "emulator"}}""",
        ).toDomain()
        assertEquals(AuditKind.NETWORK, entry.kind)
        assertEquals("10.99.0.0/24, 10.10.30.0/24", entry.arguments.first { it.first == "subnets" }.second)
        assertEquals(AuditKind.SWITCH, AuditKind.parse("something-new"))
    }
}
