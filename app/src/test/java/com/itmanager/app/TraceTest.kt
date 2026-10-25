package com.itmanager.app

import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.traceToSwitch
import org.junit.Assert.assertEquals
import org.junit.Test

class TraceTest {
    private fun device(id: String, type: DeviceType) =
        Device(id, id, type, null, null, null, null, null, 0f, 0f, isOnline = true, vlanId = null)

    private val devices = listOf(
        device("pc", DeviceType.WORKSTATION),
        device("socket", DeviceType.SOCKET),
        device("panel", DeviceType.PATCH_PANEL),
        device("access", DeviceType.SWITCH),
        device("core", DeviceType.SWITCH),
    ).associateBy { it.id }

    private val links = listOf(
        Connection("1", "pc", "socket", "Cat6", null),
        Connection("2", "socket", "panel", "Cat6", null),
        Connection("3", "panel", "access", "Cat6", null),
        Connection("4", "access", "core", "OM3", null),
    )

    @Test
    fun followsSocketAndPatchPanelToNearestSwitch() {
        assertEquals(listOf("1", "2", "3"), traceToSwitch("pc", devices, links))
    }

    @Test
    fun switchHasNoTrace() {
        assertEquals(emptyList<String>(), traceToSwitch("access", devices, links))
    }

    @Test
    fun uncabledDeviceHasNoTrace() {
        assertEquals(emptyList<String>(), traceToSwitch("pc", devices, emptyList()))
    }
}
