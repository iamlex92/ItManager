package com.itmanager.app

import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.vlanUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortVlanViewTest {
    private fun port(n: Int, mode: PortMode, access: Int? = null, tagged: List<Int> = emptyList()) = SwitchPort(
        id = "p$n", number = n, name = "gi1/0/$n", isUplink = false, mode = mode,
        accessVlan = access, taggedVlans = tagged, isAdminUp = true, isOperUp = true,
        speedMbps = 1000, poeEnabled = false, connectedDeviceName = null,
    )

    @Test
    fun accessPortCarriesOnlyItsVlan() {
        // A leftover access VLAN on a trunk port must not count, and vice versa.
        val access = port(1, PortMode.ACCESS, access = 10, tagged = listOf(20))
        assertEquals(listOf(10), access.vlanIds)
        assertFalse(access.carries(20))
    }

    @Test
    fun trunkCarriesTaggedVlansSorted() {
        val trunk = port(2, PortMode.TRUNK, access = 10, tagged = listOf(30, 20))
        assertEquals(listOf(20, 30), trunk.vlanIds)
        assertTrue(trunk.carries(30))
        assertFalse(trunk.carries(10))
    }

    @Test
    fun accessPortWithoutVlanCarriesNothing() {
        assertEquals(emptyList<Int>(), port(3, PortMode.ACCESS).vlanIds)
    }

    @Test
    fun usageCountsEachPortOncePerVlan() {
        val ports = listOf(
            port(1, PortMode.ACCESS, access = 20),
            port(2, PortMode.ACCESS, access = 10),
            port(3, PortMode.TRUNK, tagged = listOf(10, 20, 20)),
            port(4, PortMode.ACCESS),
        )
        assertEquals(listOf(10 to 2, 20 to 2), ports.vlanUsage().toList())
    }
}
