package com.itmanager.app.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnyConnectTest {
    @Test
    fun connectAsksBackToTheApp() {
        val uri = AnyConnect.connectUri(VpnSettings(enabled = true, name = "Завод ЭТС", host = "vpn.zavod.ru", username = "kharca01"))
        assertEquals(
            "anyconnect://connect/?name=%D0%97%D0%B0%D0%B2%D0%BE%D0%B4%20%D0%AD%D0%A2%D0%A1&host=vpn.zavod.ru" +
                "&prefill_username=kharca01&onsuccess=itmanager%3A%2F%2Fvpn%2Fconnected&onerror=itmanager%3A%2F%2Fvpn%2Ferror",
            uri,
        )
        // Empty fields are left out rather than sent blank.
        assertEquals(
            "anyconnect://connect/?host=vpn.zavod.ru&onsuccess=itmanager%3A%2F%2Fvpn%2Fconnected&onerror=itmanager%3A%2F%2Fvpn%2Ferror",
            AnyConnect.connectUri(VpnSettings(host = " vpn.zavod.ru ")),
        )
        assertEquals("anyconnect://disconnect/?onsuccess=itmanager%3A%2F%2Fvpn%2Fdisconnected", AnyConnect.disconnectUri())
    }

    @Test
    fun callbacksAreRecognised() {
        assertEquals(VpnResult.CONNECTED, AnyConnect.result("itmanager://vpn/connected"))
        assertEquals(VpnResult.FAILED, AnyConnect.result("itmanager://vpn/error?reason=x"))
        assertEquals(VpnResult.DISCONNECTED, AnyConnect.result("itmanager://vpn/disconnected"))
        assertEquals(VpnResult.LEFT, AnyConnect.result("itmanager://vpn/left"))
        assertEquals("anyconnect://disconnect/?onsuccess=itmanager%3A%2F%2Fvpn%2Fleft", AnyConnect.disconnectUri(leaving = true))
        assertNull(AnyConnect.result("itmanager://other/connected"))
        assertNull(AnyConnect.result("https://vpn/connected"))
    }

    @Test
    fun serverEndpointFromTheAddress() {
        assertEquals("10.0.0.5" to 8000, serverEndpoint("http://10.0.0.5:8000"))
        assertEquals("netadmin.zavod.ru" to 443, serverEndpoint("https://netadmin.zavod.ru"))
        assertEquals("fe80::1" to 80, serverEndpoint("http://[fe80::1]"))
        assertNull(serverEndpoint(""))
    }
}
