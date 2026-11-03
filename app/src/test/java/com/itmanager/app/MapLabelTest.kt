package com.itmanager.app

import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.MapLabel
import com.itmanager.app.data.model.mapLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class MapLabelTest {
    private fun device(type: DeviceType, ip: String? = null, vlanId: Int? = null) = Device(
        id = "d", name = "PC-BUH-01", type = type, vendor = null, model = null, ip = ip, mac = null, floorId = null,
        x = 0f, y = 0f, isOnline = true, vlanId = vlanId,
    )

    private val vlans = mapOf(20 to "Бухгалтерия")

    @Test
    fun markerSaysNameAddressOrVlan() {
        val pc = device(DeviceType.WORKSTATION, ip = "10.10.20.11", vlanId = 20)
        assertEquals("PC-BUH-01", pc.mapLabel(MapLabel.NAME, vlans))
        assertEquals("10.10.20.11", pc.mapLabel(MapLabel.IP, vlans))
        assertEquals("20 · Бухгалтерия", pc.mapLabel(MapLabel.VLAN, vlans))
        assertEquals("VLAN 30", device(DeviceType.WORKSTATION, vlanId = 30).mapLabel(MapLabel.VLAN, vlans))
    }

    @Test
    fun missingValuesAreSaidAndCablePlantKeepsItsName() {
        val bare = device(DeviceType.PRINTER)
        assertEquals("нет IP", bare.mapLabel(MapLabel.IP, vlans))
        assertEquals("нет VLAN", bare.mapLabel(MapLabel.VLAN, vlans))
        val socket = device(DeviceType.SOCKET)
        assertEquals("PC-BUH-01", socket.mapLabel(MapLabel.IP, vlans))
        assertEquals("PC-BUH-01", socket.mapLabel(MapLabel.VLAN, vlans))
    }
}
