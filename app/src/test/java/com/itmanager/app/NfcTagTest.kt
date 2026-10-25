package com.itmanager.app

import com.itmanager.app.core.nfc.TagPayload
import com.itmanager.app.core.nfc.formatTagUid
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcTagTest {
    private val device = Device(
        id = "e3b0c442-98fc-1c14-9af3-4c56987fbc31", name = "SW-CORE-01", type = DeviceType.SWITCH,
        vendor = "Cisco", model = "C9300-24P", ip = "10.99.0.1", mac = null, floorId = null,
        x = 0f, y = 0f, isOnline = true, vlanId = 99,
    )

    @Test
    fun writesSpecFormat() {
        val json = TagPayload.forDevice(device).encode().decodeToString()
        assertEquals(
            """{"app":"netadmin","v":1,"id":"e3b0c442-98fc-1c14-9af3-4c56987fbc31","type":"switch","name":"SW-CORE-01"}""",
            json,
        )
    }

    @Test
    fun withoutNameDropsTheField() {
        val json = TagPayload.forDevice(device, withName = false).encode().decodeToString()
        assertTrue(!json.contains("name"))
    }

    @Test
    fun fullPayloadFitsNtag213() {
        // NTAG213 offers 137 bytes for the NDEF message; a short MIME record adds 3 header bytes and the type.
        val size = 3 + TagPayload.MIME_TYPE.length + TagPayload.forDevice(device).encode().size
        assertTrue("message is $size bytes", size <= 137)
    }

    @Test
    fun readsSpecExampleAndIgnoresOthers() {
        val spec = """{"app":"netadmin","v":1,"id":"e3b0","type":"switch","name":"SW-CORE-01","extra":true}"""
        assertEquals("e3b0", TagPayload.parse(spec.encodeToByteArray())?.id)
        assertNull(TagPayload.parse("""{"app":"other","v":1,"id":"x"}""".encodeToByteArray()))
        assertNull(TagPayload.parse("""{"app":"netadmin","v":2,"id":"x"}""".encodeToByteArray()))
        assertNull(TagPayload.parse("not json".encodeToByteArray()))
    }

    @Test
    fun uidIsUppercaseHexWithColons() {
        assertEquals("04:A2:3B:FF:00", formatTagUid(byteArrayOf(0x04, 0xA2.toByte(), 0x3B, 0xFF.toByte(), 0x00)))
    }
}
