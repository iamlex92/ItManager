package com.itmanager.app

import com.itmanager.app.data.model.isIpv4
import com.itmanager.app.data.source.CommandTranslator
import org.junit.Assert.assertEquals
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

    @Test
    fun ciscoPingMatchesServerCommand() {
        val ok = CommandTranslator.ping("Cisco", "10.50.0.31", 5, reachable = true)
        assertEquals("ping 10.50.0.31 repeat 5", ok.command)
        assertTrue(ok.success)
        assertTrue(ok.output.contains("!!!!!"))
        assertTrue(ok.output.contains("Success rate is 100 percent (5/5)"))

        val down = CommandTranslator.ping("Cisco", "10.50.0.31", 5, reachable = false)
        assertFalse(down.success)
        assertTrue(down.output.contains("Success rate is 0 percent (0/5)"))
    }

    @Test
    fun eltexPingReportsLoss() {
        val down = CommandTranslator.ping("Eltex", "10.20.0.11", 3, reachable = false)
        assertEquals("ping 10.20.0.11 count 3", down.command)
        assertTrue(down.output.contains("3 packets transmitted, 0 packets received, 100% packet loss"))
    }

    @Test
    fun tracerouteStopsAtTimeoutsWhenUnreachable() {
        val ok = CommandTranslator.traceroute("Cisco", "10.50.0.31", listOf("10.99.0.1", "10.50.0.31"), reachable = true)
        assertTrue(ok.output.contains("  2 10.50.0.31"))
        val down = CommandTranslator.traceroute("Cisco", "10.50.0.31", listOf("10.99.0.1", "10.50.0.31"), reachable = false)
        assertFalse(down.output.contains("10.50.0.31 1 msec"))
        assertTrue(down.output.contains("  2  *  *  *"))
    }
}
