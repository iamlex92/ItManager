package com.itmanager.app

import com.itmanager.app.core.network.DeviceDto
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.isDown
import com.itmanager.app.data.model.isSwitchedOff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTest {
    private fun dev(type: DeviceType, online: Boolean, alarm: Boolean) =
        Device("d", "d", type, null, null, null, null, null, 0f, 0f, isOnline = online, vlanId = null, alarm = alarm)

    @Test
    fun `a computer that is switched off is not down but a switch is`() {
        val pc = dev(DeviceType.WORKSTATION, online = false, alarm = false)
        assertFalse(pc.isDown)
        assertTrue(pc.isSwitchedOff)
        val sw = dev(DeviceType.SWITCH, online = false, alarm = true)
        assertTrue(sw.isDown)
        assertFalse(sw.isSwitchedOff)
    }

    @Test
    fun `an online device is neither`() {
        val d = dev(DeviceType.SERVER, online = true, alarm = true)
        assertFalse(d.isDown)
        assertFalse(d.isSwitchedOff)
    }

    @Test
    fun `the server's alarm flag and pause reach the model`() {
        val d = DeviceDto(id = "x", name = "x", type = "SERVER", alarm = false, snoozedUntil = "2026-12-01").toDomain()
        assertFalse(d.alarm)
        assertEquals("2026-12-01", d.snoozedUntil)
        assertTrue(DeviceDto(id = "y", name = "y", type = "SWITCH").toDomain().alarm)
    }
}
