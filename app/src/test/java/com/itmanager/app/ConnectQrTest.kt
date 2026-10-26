package com.itmanager.app

import com.itmanager.app.core.settings.ConnectQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectQrTest {
    @Test
    fun readsWhatTheServerDraws() {
        // The exact string netadmin-agent info encodes (server tests pin the same one).
        val qr = ConnectQr.parse("netadmin://connect?u=http%3A%2F%2F10.0.0.5%3A8000&u=http%3A%2F%2F192.168.1.2%3A8000&t=a%2Bb%2Fc%3D")
        assertEquals(ConnectQr(listOf("http://10.0.0.5:8000", "http://192.168.1.2:8000"), "a+b/c="), qr)
    }

    @Test
    fun otherCodesAreIgnored() {
        assertNull(ConnectQr.parse("https://example.com/?u=http://10.0.0.5&t=x"))
        assertNull(ConnectQr.parse("WIFI:S:factory;T:WPA;P:secret;;"))
    }

    @Test
    fun needsAnAddressAndAToken() {
        assertNull(ConnectQr.parse("netadmin://connect?t=abc"))
        assertNull(ConnectQr.parse("netadmin://connect?u=http%3A%2F%2F10.0.0.5%3A8000"))
        assertNull(ConnectQr.parse("netadmin://connect?u=ftp%3A%2F%2F10.0.0.5&t=abc"))
        assertNull(ConnectQr.parse("netadmin://connect?u=10.0.0.5&t=a%20b"))
    }

    @Test
    fun badAddressesAreSkippedAndDuplicatesDropped() {
        val qr = ConnectQr.parse("netadmin://connect?u=ftp%3A%2F%2Fx&u=10.0.0.5&u=http%3A%2F%2F10.0.0.5%3A8000&t=tok")
        assertEquals(listOf("http://10.0.0.5:8000"), qr?.urls)
    }
}
