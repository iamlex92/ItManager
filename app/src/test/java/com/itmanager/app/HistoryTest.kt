package com.itmanager.app

import com.itmanager.app.core.network.HistoryEntryDto
import com.itmanager.app.data.model.HistoryKind
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.describePortChange
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {
    private val port = SwitchPort(
        id = "p", number = 2, name = "gi1/0/2", isUplink = false, mode = PortMode.ACCESS, accessVlan = 10,
        taggedVlans = emptyList(), isAdminUp = true, isOperUp = true, speedMbps = 1000, poeEnabled = false,
        connectedDeviceName = null,
    )

    @Test
    fun `demo wording matches the server`() {
        assertEquals("gi1/0/2: VLAN 10 → 20", describePortChange(port, PortChange(accessVlan = 20)))
        assertEquals("gi1/0/2: выключен (shutdown)", describePortChange(port, PortChange(isAdminUp = false)))
        assertEquals(
            "gi1/0/2: режим access → trunk, trunk VLAN — → 10,20",
            describePortChange(port, PortChange(mode = PortMode.TRUNK, taggedVlans = listOf(20, 10))),
        )
    }

    @Test
    fun `server history parses`() {
        val e = Json { ignoreUnknownKeys = true }.decodeFromString(
            HistoryEntryDto.serializer(),
            """{"at":"2026-10-25T10:00:00Z","kind":"blocked","action":"port_config","title":"Настройка порта",
               "summary":"gi1/0/2: VLAN 10 → 20","actor":"api:10.0.0.5","commands":["interface gi1/0/2"]}""",
        ).toDomain()
        assertEquals(HistoryKind.BLOCKED, e.kind)
        assertEquals(1, e.commands.size)
    }
}
