package com.itmanager.app

import com.itmanager.app.core.settings.ServerUrl
import com.itmanager.app.core.settings.ServerUrlCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    private fun valid(input: String) = (ServerUrl.check(input) as ServerUrlCheck.Valid).normalized

    @Test
    fun bareIpGetsSchemeAndDefaultPort() {
        assertEquals("http://10.0.0.5:8000", valid("10.0.0.5"))
    }

    @Test
    fun explicitPortAndTrailingSlashAreKept() {
        assertEquals("http://10.0.0.5:9000", valid(" 10.0.0.5:9000/ "))
    }

    @Test
    fun explicitSchemeWithoutPortStaysWithoutPort() {
        assertEquals("https://netadmin.factory.local", valid("https://netadmin.factory.local/"))
    }

    @Test
    fun pathPrefixIsKept() {
        assertEquals("http://10.0.0.5:8000/netadmin", valid("http://10.0.0.5:8000/netadmin/"))
    }

    @Test
    fun rejectsEmptyAndForeignSchemes() {
        assertTrue(ServerUrl.check("") is ServerUrlCheck.Invalid)
        assertTrue(ServerUrl.check("ftp://10.0.0.5") is ServerUrlCheck.Invalid)
        assertTrue(ServerUrl.check("http://10.0.0.5:8000/?x=1") is ServerUrlCheck.Invalid)
    }
}
