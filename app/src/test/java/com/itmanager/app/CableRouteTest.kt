package com.itmanager.app

import com.itmanager.app.core.network.ConnectionDto
import com.itmanager.app.core.network.FloorDto
import com.itmanager.app.core.network.WaypointDto
import com.itmanager.app.core.network.toDomain
import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.Waypoint
import com.itmanager.app.data.model.inserted
import com.itmanager.app.data.model.moved
import com.itmanager.app.data.model.removed
import com.itmanager.app.data.model.routeOn
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CableRouteTest {
    private fun dev(id: String, x: Float, y: Float, floor: String) =
        Device(id, id.uppercase(), DeviceType.SWITCH, null, null, null, null, floor, x, y, true, null)

    private val sw = dev("sw", 100f, 100f, "f1")
    private val pc = dev("pc", 500f, 300f, "f1")
    private val up = dev("up", 200f, 50f, "f2")
    private val floorOf = mapOf("sw" to "f1", "pc" to "f1", "up" to "f2")::get

    @Test
    fun cableOnOneFloorRunsThroughItsBends() {
        val link = Connection("c", "sw", "pc", "Cat6", null, listOf(Waypoint(100f, 300f), Waypoint(400f, 300f)))
        val route = routeOn(link, "f1", mapOf("sw" to sw, "pc" to pc), floorOf)!!
        assertEquals(listOf(-1, 0, 1, 2), route.points.map { it.index })
        assertNull(route.riser)
        // Dragging the "+" between the switch and the first bend makes bend 0; between the last bend and the PC, bend 2.
        assertEquals(0, route.insertAt(0))
        assertEquals(2, route.insertAt(2))
        val straight = routeOn(link.copy(waypoints = emptyList()), "f1", mapOf("sw" to sw, "pc" to pc), floorOf)!!
        assertEquals(0, straight.insertAt(0))
    }

    @Test
    fun cableBetweenFloorsShowsItsPartAndARiserOnEach() {
        val link = Connection(
            "r", "sw", "up", "OM3", "Стояк",
            listOf(Waypoint(300f, 100f, "f1"), Waypoint(300f, 40f), Waypoint(300f, 40f, "f2")),
        )
        val ground = routeOn(link, "f1", mapOf("sw" to sw, "pc" to pc), floorOf)!!
        // A bend without a floor lies on end A's floor.
        assertEquals(listOf(-1, 0, 1), ground.points.map { it.index })
        val riser = ground.riser!!
        assertEquals("f2", riser.otherFloorId)
        assertFalse(riser.virtual)
        assertEquals(300f to 40f, riser.x to riser.y)

        val upper = routeOn(link, "f2", mapOf("up" to up), floorOf)!!
        assertEquals(listOf(2, 3), upper.points.map { it.index })
        assertEquals("f1", upper.riser!!.otherFloorId)

        assertNull(routeOn(link, "f3", emptyMap(), floorOf))
    }

    @Test
    fun riserWithoutBendsSitsBesideTheDeviceAndAddsTheFirstBend() {
        val link = Connection("r", "sw", "up", "OM3", null)
        val ground = routeOn(link, "f1", mapOf("sw" to sw), floorOf)!!
        assertTrue(ground.riser!!.virtual)
        assertEquals(0, ground.riser!!.insertAt)
        val upper = routeOn(link, "f2", mapOf("up" to up), floorOf)!!
        // On end B's floor the local bends come last.
        assertEquals(0, upper.riser!!.insertAt)
        val withBend = link.copy(waypoints = listOf(Waypoint(1f, 1f, "f1")))
        assertEquals(1, routeOn(withBend, "f2", mapOf("up" to up), floorOf)!!.riser!!.insertAt)
    }

    @Test
    fun editsKeepTheOrder() {
        val pts = listOf(Waypoint(0f, 0f), Waypoint(10f, 10f))
        assertEquals(listOf(Waypoint(0f, 0f), Waypoint(5f, 5f), Waypoint(10f, 10f)), pts.inserted(1, Waypoint(5f, 5f)))
        assertEquals(Waypoint(3f, 4f), pts.moved(1, 3f, 4f)[1])
        assertEquals(listOf(Waypoint(10f, 10f)), pts.removed(0))
    }

    @Test
    fun routeAndFloorNameFromServer() {
        val json = Json { ignoreUnknownKeys = true }
        val dto = json.decodeFromString(
            ConnectionDto.serializer(),
            """{"id": "c", "device_a_id": "a", "device_b_id": "b", "waypoints": [{"x": 1.5, "y": 2}, {"x": 3, "y": 4, "floor_id": "f2"}]}""",
        )
        assertEquals(listOf(Waypoint(1.5f, 2f), Waypoint(3f, 4f, "f2")), dto.toDomain()!!.waypoints)
        assertEquals(WaypointDto(3f, 4f, "f2"), WaypointDto.of(Waypoint(3f, 4f, "f2")))
        val floor = FloorDto("f1", "АБК", 2, name = "Серверная")
        assertEquals("Серверная", floor.info().title)
        assertEquals("Этаж 2", FloorDto("f2", "АБК", 2).info().title)
        assertEquals("АБК · этаж 2", FloorDto("f2", "АБК", 2).toDomain("http://s", 1f, 1f).title)
    }
}
