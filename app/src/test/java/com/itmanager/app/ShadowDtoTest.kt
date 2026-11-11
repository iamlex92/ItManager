package com.itmanager.app

import com.itmanager.app.core.network.ShadowDto
import com.itmanager.app.core.network.ShadowSessionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShadowDtoTest {
    @Test
    fun theReadyCommandAndTheViewOnlyVariant() {
        val info = ShadowDto(
            host = "PC-1", sessionId = 2, user = "kharca01",
            command = "mstsc /shadow:2 /v:PC-1 /control /noConsentPrompt",
            commands = mapOf("2" to "mstsc /shadow:2 /v:PC-1 /control /noConsentPrompt"),
            sessions = listOf(ShadowSessionDto(2, "console", "kharca01", "Active", active = true, shadowable = true)),
        ).toDomain()
        assertEquals("mstsc /shadow:2 /v:PC-1 /noConsentPrompt", info.viewOf(info.command!!))
        assertEquals(1, info.sessions.count { it.shadowable })
    }

    @Test
    fun nobodyLoggedOnHasNoCommand() {
        val info = ShadowDto(host = "PC-2", message = "нет сеанса").toDomain()
        assertNull(info.command)
        assertEquals("нет сеанса", info.message)
    }
}
