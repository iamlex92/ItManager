package com.itmanager.app

import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSystemTest {
    private fun pc(os: String?) = Device(
        "id", "PC-01", DeviceType.WORKSTATION, null, null, null, null, null, 0f, 0f, isOnline = true, vlanId = null,
        metadata = if (os == null) emptyMap() else mapOf("os" to JsonPrimitive(os)),
    )

    @Test
    fun `the system comes from the metadata the server keeps`() {
        assertEquals("Windows", pc("windows").systemName)
        assertEquals("Astra Linux", pc("astra").systemName)
        assertNull(pc(null).systemName)
        assertNull(pc("beos").systemName)
    }

    @Test
    fun `the system is searchable by name or by the word Linux`() {
        assertTrue(pc("astra").matches("linux"))
        assertTrue(pc("windows").matches("WIN"))
        assertFalse(pc(null).matches("linux"))
    }
}
