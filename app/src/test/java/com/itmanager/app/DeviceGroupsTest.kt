package com.itmanager.app

import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceGrouping
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FloorInfo
import com.itmanager.app.data.model.Zone
import com.itmanager.app.data.model.groupDevices
import com.itmanager.app.data.model.isDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGroupsTest {
    private fun dev(id: String, floor: String?, x: Float = 0f, y: Float = 0f, type: DeviceType = DeviceType.WORKSTATION, online: Boolean = true) =
        Device(id, id, type, null, null, null, null, floor, x, y, isOnline = online, vlanId = null)

    private val floors = listOf(FloorInfo("f1", "B", 1, null), FloorInfo("f2", "B", 2, "Второй"))
    private val devices = listOf(
        dev("pc-b", "f2", 10f, 10f),
        dev("sw", "f1", type = DeviceType.SWITCH),
        dev("pc-a", "f2", 500f, 500f),
        dev("vm", null, type = DeviceType.VIRTUAL),
    )

    @Test
    fun `by type follows the type order`() {
        val groups = groupDevices(devices, DeviceGrouping.TYPE, floors)
        assertEquals(listOf("Коммутатор", "Рабочее место", "Виртуальная машина"), groups.map { it.title })
        assertEquals(listOf("pc-a", "pc-b"), groups[1].devices.map { it.id })
    }

    @Test
    fun `by floor goes bottom to top with unplaced devices last`() {
        val groups = groupDevices(devices, DeviceGrouping.FLOOR, floors)
        assertEquals(listOf("Этаж 1", "Второй", "Не на карте"), groups.map { it.title })
        assertEquals(listOf("vm"), groups.last().devices.map { it.id })
    }

    @Test
    fun `by room names rooms and keeps the rest of the floor`() {
        val room = Zone("z", "201", isRoom = true, colorArgb = 0L, points = listOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f))
        val groups = groupDevices(devices, DeviceGrouping.ROOM, floors, mapOf("f2" to listOf(room)))
        assertEquals(listOf("Этаж 1 · вне помещений", "Второй · 201", "Второй · вне помещений", "Не на карте"), groups.map { it.title })
        assertEquals(listOf("pc-b"), groups[1].devices.map { it.id })
    }

    @Test
    fun `passive cabling never counts as down`() {
        assertTrue(dev("pc", null, online = false).isDown)
        assertFalse(dev("sock", null, type = DeviceType.SOCKET, online = false).isDown)
        assertFalse(dev("rack", null, type = DeviceType.RACK, online = false).isDown)
    }
}
