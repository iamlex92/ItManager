package com.itmanager.app

import com.itmanager.app.data.model.isIpAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpAddressTest {
    @Test
    fun ipv4AndIpv6AreAccepted() {
        listOf("10.10.20.11", "192.168.0.1", "fd00::1", "2001:db8::ff00:42:8329", "::1", "::", "fe80:0:0:0:0:0:0:1", "::ffff:10.0.0.1")
            .forEach { assertTrue(it, isIpAddress(it)) }
    }

    @Test
    fun theRestIsNot() {
        listOf("", "10.10.20", "10.10.20.256", "host-1", "1::2::3", "12345::1", "1:2:3:4:5:6:7:8:9", "fd00::g", "10.0.0.1/24", "fe80::1%eth0")
            .forEach { assertFalse(it, isIpAddress(it)) }
    }
}
