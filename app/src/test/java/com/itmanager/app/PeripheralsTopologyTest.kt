package com.itmanager.app

import com.itmanager.app.core.network.FaceplateDto
import com.itmanager.app.core.network.PeripheralDto
import com.itmanager.app.core.network.TopologyDto
import com.itmanager.app.core.network.toBody
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FaceplateMarks
import com.itmanager.app.data.model.PeripheralDraft
import com.itmanager.app.data.model.PeripheralKind
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Topology
import com.itmanager.app.data.model.TopologyLayouter
import com.itmanager.app.data.model.TopologyLink
import com.itmanager.app.data.model.TopologyLinkKind
import com.itmanager.app.data.model.TopologyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeripheralsTopologyTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private fun port(n: Int) = SwitchPort(
        id = "p$n", number = n, name = "gi1/0/$n", isUplink = false, mode = PortMode.ACCESS, accessVlan = 10,
        taggedVlans = emptyList(), isAdminUp = true, isOperUp = true, speedMbps = 1000, poeEnabled = false,
        connectedDeviceName = null,
    )

    @Test
    fun `peripheral parses and its form clears blank fields`() {
        val p = json.decodeFromString(
            PeripheralDto.serializer(),
            """{"id":"m1","kind":"MONITOR","vendor":"Dell","model":"P2422H","specs":"24\"","serial_number":"CN0X",
               "inventory_number":null,"device_id":"pc1","device_name":"PC-BUH-01","created_at":"2026-10-27T10:00:00Z"}""",
        ).toDomain()
        assertEquals("Монитор Dell P2422H", p.title)
        assertEquals("24\" · S/N CN0X", p.details)
        assertTrue(p.matches("buh"))
        assertEquals(PeripheralKind.OTHER, PeripheralKind.parse("hologram"))

        val body = PeripheralDraft(PeripheralKind.KEYBOARD, vendor = "  ", model = " K120 ", deviceId = null).toBody()
        assertEquals("KEYBOARD", body["kind"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["vendor"])
        assertEquals("K120", body["model"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["device_id"])
    }

    @Test
    fun `marks follow the port order and the row continues at the same spacing`() {
        val ports = (1..8).map(::port)
        val first = FaceplateMarks.centredAt(ports[0], 0.1f, 0.3f, 0.04f, 0.2f)
        assertEquals(0.08f, first.x, 1e-6f)
        // Kept inside the drawing
        val edge = FaceplateMarks.centredAt(ports[0], 0.0f, 0.99f, 0.04f, 0.2f)
        assertEquals(0f, edge.x, 1e-6f)
        assertEquals(0.8f, edge.y, 1e-6f)

        // Odd ports on top: every other one
        assertEquals(listOf(1, 3, 5, 7), FaceplateMarks.sequence(ports, ports[0], 2).map { it.number })
        assertEquals(3, FaceplateMarks.next(ports, ports[0], 2, setOf("p1"))?.number)
        assertNull(FaceplateMarks.next(ports, ports[6], 2, emptySet()))

        val marks = mapOf(
            "p1" to FaceplateMarks.centredAt(ports[0], 0.10f, 0.3f, 0.04f, 0.2f),
            "p3" to FaceplateMarks.centredAt(ports[2], 0.15f, 0.3f, 0.04f, 0.2f),
        )
        val filled = FaceplateMarks.continueRow(ports, marks, ports[4], 2)
        assertNotNull(filled)
        assertEquals(setOf("p1", "p3", "p5", "p7"), filled!!.keys)
        assertEquals(0.25f - 0.02f, filled.getValue("p7").x, 1e-5f)
        // Two marks are needed to know the spacing
        assertNull(FaceplateMarks.continueRow(ports, marks, ports[2], 2))
    }

    @Test
    fun `a row stops at the edge of the drawing`() {
        val ports = (1..30).map(::port)
        val marks = mapOf(
            "p1" to FaceplateMarks.centredAt(ports[0], 0.05f, 0.5f, 0.02f, 0.2f),
            "p2" to FaceplateMarks.centredAt(ports[1], 0.15f, 0.5f, 0.02f, 0.2f),
        )
        val filled = FaceplateMarks.continueRow(ports, marks, ports[2], 1)!!
        assertTrue(filled.values.all { it.x + it.w <= 1.0001f })
        assertEquals(10, filled.size)
    }

    @Test
    fun `faceplate parses`() {
        val f = json.decodeFromString(
            FaceplateDto.serializer(),
            """{"device_id":"s1","width":482,"height":44,"updated_at":"2026-10-27T10:00:00Z",
               "ports":[{"port_id":"p1","port_name":"gi1/0/1","port_number":1,"x":0.1,"y":0.2,"w":0.02,"h":0.3}]}""",
        ).toDomain()
        assertEquals(482f / 44f, f.aspect, 1e-4f)
        assertTrue(f.ports.single().contains(0.11f, 0.3f))
    }

    private fun node(id: String, type: DeviceType, host: String? = null) =
        TopologyNode(id, id, type, null, null, null, true, null, host)

    private fun cable(a: String, b: String) = TopologyLink(a, b, TopologyLinkKind.CABLE, "1", "2", emptyList(), emptyList(), true, 1000)

    @Test
    fun `the tree starts at the firewall and end devices sit under their switch`() {
        val t = Topology(
            nodes = listOf(
                node("fw", DeviceType.FIREWALL), node("core", DeviceType.SWITCH), node("acc1", DeviceType.SWITCH),
                node("acc2", DeviceType.SWITCH), node("pc1", DeviceType.WORKSTATION), node("pc2", DeviceType.WORKSTATION),
                node("srv", DeviceType.SERVER), node("vm", DeviceType.VIRTUAL, host = "srv"), node("lonely", DeviceType.PRINTER),
            ),
            links = listOf(
                cable("fw", "core"), cable("core", "acc1"), cable("core", "acc2"), cable("acc1", "acc2"),
                cable("pc1", "acc1"), cable("pc2", "acc1"), cable("srv", "core"),
                TopologyLink("srv", "vm", TopologyLinkKind.VIRTUAL, null, null, emptyList(), emptyList(), null, null),
            ),
        )
        val l = TopologyLayouter.layout(t, showEndDevices = true)
        val p = l.places
        assertEquals(9, p.size)
        assertEquals(0f, p.getValue("fw").y)
        assertTrue(p.getValue("core").y > p.getValue("fw").y)
        assertTrue(p.getValue("acc1").y > p.getValue("core").y)
        // acc2 hangs off the core, not off acc1, though both links exist
        assertEquals(p.getValue("acc1").y, p.getValue("acc2").y)
        assertTrue(p.getValue("pc1").y > p.getValue("acc1").y)
        assertTrue(p.getValue("vm").y >= p.getValue("srv").y)
        // The printer with no cable goes below everything
        assertTrue(p.getValue("lonely").y > p.values.filter { it != p.getValue("lonely") }.maxOf { it.y } - 0.001f)
        // No two devices in one place
        assertEquals(p.size, p.values.map { it.x to it.y }.toSet().size)

        val infra = TopologyLayouter.layout(t, showEndDevices = false)
        assertEquals(setOf("fw", "core", "acc1", "acc2"), infra.places.keys)
    }

    @Test
    fun `topology parses`() {
        val t = json.decodeFromString(
            TopologyDto.serializer(),
            """{"nodes":[{"id":"a","name":"SW","type":"SWITCH","is_online":true,"degree":1},{"id":"b","name":"PC","type":"WORKSTATION"}],
               "links":[{"a_id":"b","b_id":"a","kind":"CABLE","a_port":"eth0","b_port":"gi1/0/1","via":["R-1.01","PP-1"],
                         "cable_types":["Cat6"],"is_up":false,"speed_mbps":1000,"connection_ids":["c1","c2","c3"]}]}""",
        ).toDomain()
        val link = t.links.single()
        assertEquals("gi1/0/1", link.portOf("a"))
        assertEquals("a", link.other("b"))
        assertEquals(false, link.isUp)
        // Unplugging the PC removes its patch cord; unplugging at the switch, the switch-side cable.
        assertEquals("c1", link.cableAt("b"))
        assertEquals("c3", link.cableAt("a"))
        assertEquals(DeviceType.WORKSTATION, t.nodes[1].type)
    }
}
