package com.itmanager.app

import com.itmanager.app.core.network.HistoryEntryDto
import com.itmanager.app.data.model.HistoryKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {
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
