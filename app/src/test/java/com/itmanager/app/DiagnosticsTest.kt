package com.itmanager.app

import com.itmanager.app.data.model.isIpv4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun acceptsOnlyIpv4() {
        assertTrue(isIpv4("10.50.0.31"))
        assertTrue(isIpv4(" 192.168.1.1 "))
        assertFalse(isIpv4("10.50.0"))
        assertFalse(isIpv4("10.50.0.256"))
        assertFalse(isIpv4("srv-01"))
        assertFalse(isIpv4(""))
    }
}
