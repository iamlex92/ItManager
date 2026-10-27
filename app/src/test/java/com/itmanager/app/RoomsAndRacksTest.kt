package com.itmanager.app

import com.itmanager.app.core.network.DeviceDto
import com.itmanager.app.core.network.FloorPatch
import com.itmanager.app.core.network.ZoneDto
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.Zone
import com.itmanager.app.data.model.roomAt
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomsAndRacksTest {
    private fun square(id: String, x: Float, y: Float, size: Float, room: Boolean = true) =
        Zone(id, id, room, 0xFF0A84FF, listOf(x, y, x + size, y, x + size, y + size, x, y + size))

    @Test
    fun deviceIsInTheRoomItsIconLiesIn() {
        val zone = square("srv", 0f, 0f, 100f)
        assertTrue(zone.contains(50f, 50f))
        assertFalse(zone.contains(150f, 50f))
        assertFalse(zone.contains(50f, -1f))
        // An L-shaped room: the notch is outside.
        val l = Zone("l", "L", true, 0, listOf(0f, 0f, 100f, 0f, 100f, 40f, 40f, 40f, 40f, 100f, 0f, 100f))
        assertTrue(l.contains(20f, 80f))
        assertFalse(l.contains(80f, 80f))
    }

    @Test
    fun smallestRoomWinsAndPlainZonesAreNotRooms() {
        val zones = listOf(square("hall", 0f, 0f, 500f), square("cabinet", 100f, 100f, 50f), square("area", 0f, 0f, 20f, room = false))
        assertEquals("cabinet", zones.roomAt(120f, 120f)?.id)
        assertEquals("hall", zones.roomAt(300f, 300f)?.id)
        assertEquals("hall", zones.roomAt(10f, 10f)?.id)
        assertNull(zones.roomAt(900f, 900f))
        assertNull(listOf(Zone("two", "x", true, 0, listOf(0f, 0f, 1f, 1f))).roomAt(0f, 0f))
    }

    @Test
    fun zonesTravelToAndFromTheServer() {
        val json = Json { ignoreUnknownKeys = true }
        val dto = json.decodeFromString(
            ZoneDto.serializer(),
            """{"id": "z1", "name": "Серверная", "is_room": true, "color": "#FF9F0A", "points": [0, 0, 10, 0, 10, 10]}""",
        )
        val zone = dto.toDomain()
        assertEquals("Серверная", zone.name)
        assertEquals(0xFFFF9F0AL, zone.colorArgb)
        assertEquals("#FF9F0A", ZoneDto.of(zone).color)
        assertEquals(1, FloorPatch.zones(listOf(zone)).zones?.size)
    }

    @Test
    fun deviceCarriesCommentAndRackPlace() {
        val json = Json { ignoreUnknownKeys = true }
        val device = json.decodeFromString(
            DeviceDto.serializer(),
            """{"id": "sw", "name": "SW-1", "type": "SWITCH", "comment": "аплинк на 24",
                "rack_id": "r1", "rack_u": 20, "rack_h": 2}""",
        ).toDomain()
        assertEquals("аплинк на 24", device.comment)
        assertEquals(21, device.rackTop)
        val rack = json.decodeFromString(DeviceDto.serializer(), """{"id": "r1", "name": "A1", "type": "RACK", "rack_units": 24}""")
        assertEquals(DeviceType.RACK, rack.toDomain().type)
        assertEquals(24, rack.toDomain().rackUnits)
        // Editing a field through the draft keeps the rack place.
        val draft = device.toDraft().copy(comment = null)
        assertEquals("r1", draft.rackId)
        assertEquals(20, draft.rackU)
        assertNull(Device("x", "x", DeviceType.SERVER, null, null, null, null, null, 0f, 0f, true, null).rackTop)
    }
}
