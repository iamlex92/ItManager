package com.itmanager.app

import com.itmanager.app.core.network.PortDto
import com.itmanager.app.core.network.toPatch
import com.itmanager.app.data.model.PortDraft
import com.itmanager.app.data.model.PortMode
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortLabelTest {
    private fun draft(speed: Int, label: String?) = PortDraft(
        number = 3, name = "3", isUplink = false, mode = PortMode.ACCESS, accessVlan = null,
        taggedVlans = emptyList(), speedMbps = speed, poeEnabled = false, label = label,
    )

    @Test
    fun labelIsSentOnlyWhenSet() {
        assertFalse("label" in draft(1000, null).toPatch())
        assertEquals(JsonPrimitive("каб. 214"), draft(1000, "каб. 214").toPatch()["label"])
        // An empty text clears the label on the server, so it is sent.
        assertEquals(JsonPrimitive(""), draft(1000, "").toPatch()["label"])
    }

    @Test
    fun patchPanelPortWithoutSpeedDoesNotSendIt() {
        assertTrue("speed_mbps" !in draft(0, "каб. 1").toPatch())
        assertTrue("speed_mbps" in draft(100, "каб. 1").toPatch())
    }

    @Test
    fun portDtoCarriesTheLabel() {
        val port = PortDto(id = "p", number = 1, name = "1", label = "бухгалтерия").toDomain()
        assertEquals("бухгалтерия", port.label)
    }
}
