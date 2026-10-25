package com.itmanager.app

import com.itmanager.app.data.export.InventoryReport
import com.itmanager.app.data.export.ReportTable
import com.itmanager.app.data.export.XlsxWriter
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.SwitchPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

class ExportTest {
    private fun device(name: String, type: DeviceType, online: Boolean = true) = Device(
        id = name, name = name, type = type, vendor = "Cisco", model = null, ip = "10.0.0.1", mac = null,
        floorId = null, x = 0f, y = 0f, isOnline = online, vlanId = 10,
    )

    private fun port(n: Int, up: Boolean, uplink: Boolean = false) = SwitchPort(
        id = "p$n", number = n, name = "gi1/0/$n", isUplink = uplink, mode = if (uplink) PortMode.TRUNK else PortMode.ACCESS,
        accessVlan = if (uplink) null else 10, taggedVlans = if (uplink) listOf(10, 20) else emptyList(),
        isAdminUp = n != 3, isOperUp = up, speedMbps = 1000, poeEnabled = false, connectedDeviceName = if (up) "PC-$n" else null,
    )

    private val report = InventoryReport(
        generatedAt = Instant.parse("2026-10-25T09:30:00Z"),
        source = "демо-данные",
        devices = listOf(device("PC-1", DeviceType.WORKSTATION, online = false), device("SW-1", DeviceType.SWITCH), device("R-1", DeviceType.SOCKET, online = false)),
        vlans = emptyList(),
        switchPorts = listOf(device("SW-1", DeviceType.SWITCH) to listOf(port(2, up = true), port(1, up = false), port(3, up = false), port(25, up = true, uplink = true))),
        zone = ZoneOffset.ofHours(3),
    )

    @Test
    fun `report rows`() {
        assertEquals("25.10.2026 12:30 · демо-данные", report.subtitle)
        assertEquals(listOf("SW-1", "PC-1", "R-1"), report.devicesTable.rows.map { it[0] })
        assertEquals(listOf("в сети", "не отвечает", "пассивное"), report.devicesTable.rows.map { it.last() })
        assertTrue("passive sockets are not counted as down", ("Не отвечают" to "1") in report.summary)
        val ports = report.portsTable.rows
        assertEquals(listOf("gi1/0/1", "gi1/0/2", "gi1/0/3", "gi1/0/25 (аплинк)"), ports.map { it[1] })
        assertEquals(listOf("нет линка", "линк", "shutdown", "линк"), ports.map { it[4] })
        assertEquals("10, 20", ports[3][3])
        assertEquals("trunk", ports[3][2])
    }

    @Test
    fun `workbook has one sheet per table and escapes text`() {
        val out = ByteArrayOutputStream()
        val tricky = ReportTable("Порты: все", listOf("A", "B"), listOf(listOf("a & <b>", "x\u0001y")), listOf(10, 10))
        XlsxWriter.write(listOf(tricky) + report.tables, out, report.summary)
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(files.keys.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet4.xml")))
        val sheet = files.getValue("xl/worksheets/sheet2.xml")
        assertTrue(sheet.contains("a &amp; &lt;b&gt;"))
        assertTrue(sheet.contains("x y"))
        assertTrue(sheet.contains("""<autoFilter ref="A1:B2"/>"""))
        assertTrue(files.getValue("xl/workbook.xml").contains("""name="Порты  все""""))
        assertEquals("A", XlsxWriter.column(0))
        assertEquals("Z", XlsxWriter.column(25))
        assertEquals("AA", XlsxWriter.column(26))
    }
}
