package com.itmanager.app

import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.source.CommandTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortChangeTest {
    private val port = SwitchPort(
        id = "p5", number = 5, name = "gi1/0/5", isUplink = false, mode = PortMode.ACCESS,
        accessVlan = 10, taggedVlans = emptyList(), isAdminUp = true, isOperUp = true,
        speedMbps = 1000, poeEnabled = false, connectedDeviceName = null,
    )

    @Test
    fun unchangedDraftIsEmpty() {
        assertTrue(PortChange.between(port, PortMode.ACCESS, 10, emptyList(), true).isEmpty)
    }

    @Test
    fun onlyChangedFieldsAreSent() {
        assertEquals(PortChange(accessVlan = 20), PortChange.between(port, PortMode.ACCESS, 20, emptyList(), true))
        assertEquals(PortChange(isAdminUp = false), PortChange.between(port, PortMode.ACCESS, 10, emptyList(), false))
    }

    @Test
    fun switchingToTrunkSendsModeAndVlans() {
        assertEquals(
            PortChange(mode = PortMode.TRUNK, taggedVlans = listOf(10, 20)),
            PortChange.between(port, PortMode.TRUNK, 10, listOf(20, 10), true),
        )
    }

    @Test
    fun eltexSyntax() {
        assertEquals(
            listOf("interface gigabitethernet 1/0/5", "switchport access vlan 20", "shutdown", "exit"),
            CommandTranslator.portChange("Eltex", port, PortChange(accessVlan = 20, isAdminUp = false)),
        )
    }

    @Test
    fun ciscoSyntax() {
        val cisco = port.copy(name = "Gi1/0/5")
        assertEquals(
            listOf("interface Gi1/0/5", "switchport mode trunk", "switchport trunk allowed vlan 10,20", "exit"),
            CommandTranslator.portChange("Cisco", cisco, PortChange(mode = PortMode.TRUNK, taggedVlans = listOf(10, 20))),
        )
    }
}
