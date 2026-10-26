package com.itmanager.app

import com.itmanager.app.core.network.DeviceDto
import com.itmanager.app.data.model.DeviceBulkChange
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.ui.components.DEVICE_TYPES_IN_ORDER
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTypesTest {
    @Test
    fun everyTypeIsOfferedOnceInPickersAndTheLegend() {
        assertEquals(DeviceType.entries.toSet(), DEVICE_TYPES_IN_ORDER.toSet())
        assertEquals(DEVICE_TYPES_IN_ORDER.size, DEVICE_TYPES_IN_ORDER.distinct().size)
    }

    @Test
    fun virtualMachineComesWithItsHost() {
        val json = Json { ignoreUnknownKeys = true }
        val dto = json.decodeFromString(
            DeviceDto.serializer(),
            """{"id": "vm", "name": "VM-1C", "type": "VIRTUAL", "host_id": "srv", "router": 1}""",
        )
        val device = dto.toDomain()
        assertEquals(DeviceType.VIRTUAL, device.type)
        assertEquals("srv", device.hostId)
        // A type this app does not know yet still shows, as a generic device.
        assertEquals(DeviceType.UNKNOWN, DeviceType.parse("TOASTER"))
        assertEquals(DeviceType.UPS, DeviceType.parse("ups"))
    }

    @Test
    fun emptyGroupChangeIsNothingToApply() {
        assertTrue(DeviceBulkChange().isEmpty)
        assertFalse(DeviceBulkChange(type = DeviceType.PHONE).isEmpty)
    }
}
