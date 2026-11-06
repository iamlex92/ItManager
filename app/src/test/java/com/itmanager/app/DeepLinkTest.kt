package com.itmanager.app

import com.itmanager.app.core.nfc.DeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    private val id = "5c3e0f0a-8a3b-4b8e-9d1e-0b6f4a1e2c01"

    @Test
    fun labelLinksAndConnectionCodes() {
        assertEquals(DeepLink.Device(id), DeepLink.parse("itmanager://device/$id"))
        assertEquals(DeepLink.Device(id), DeepLink.parse(" ITMANAGER://device/$id/ "))
        val code = "netadmin://connect?u=http%3A%2F%2F10.0.0.5%3A8000&t=abc"
        assertEquals(DeepLink.Connect(code), DeepLink.parse(code))
        assertNull(DeepLink.parse("itmanager://vpn/connected"))
        assertNull(DeepLink.parse("https://example.com/device/$id"))
        assertNull(DeepLink.parse(null))
    }

    @Test
    fun oldLabelsWithTheTagJsonStillOpenTheirDevice() {
        assertEquals(id, DeepLink.deviceId("itmanager://device/$id"))
        assertEquals(id, DeepLink.deviceId("""{"app":"netadmin","v":1,"id":"$id","type":"switch","name":"SW"}"""))
        assertNull(DeepLink.deviceId("""{"app":"other","v":1,"id":"$id"}"""))
        assertNull(DeepLink.deviceId("hello"))
    }
}
