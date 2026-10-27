package com.itmanager.app

import com.itmanager.app.core.network.DeviceTrafficDto
import com.itmanager.app.core.network.PortTrafficDto
import com.itmanager.app.data.model.LoadLevel
import com.itmanager.app.data.model.formatBps
import com.itmanager.app.data.model.niceCeiling
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test
    fun `bits per second read like a network engineer writes them`() {
        assertEquals("850 бит/с", formatBps(850))
        assertEquals("12,5 Мбит/с", formatBps(12_500_000))
        assertEquals("1 Гбит/с", formatBps(1_000_000_000))
        assertEquals("250 Мбит/с", formatBps(250_400_000))
    }

    @Test
    fun `chart top and load levels`() {
        assertEquals(50_000_000L, niceCeiling(43_000_000))
        assertEquals(100L, niceCeiling(100))
        assertEquals(LoadLevel.LOW, LoadLevel.of(29.9f))
        assertEquals(LoadLevel.MEDIUM, LoadLevel.of(30f))
        assertEquals(LoadLevel.HIGH, LoadLevel.of(85f))
    }

    @Test
    fun `server traffic parses`() {
        val summary = json.decodeFromString(
            DeviceTrafficDto.serializer(),
            """{"device_id":"d","hours":24,"interval_seconds":300,"ports":[{"port_id":"p","port_number":2,"port_name":"gi1/0/2",
               "is_uplink":false,"speed_mbps":1000,"avg_in_bps":1000,"avg_out_bps":500,"peak_in_bps":9000000,
               "peak_out_bps":100,"peak_utilization":0.9,"last_in_bps":null,"last_out_bps":null}]}""",
        ).ports.single().toDomain()
        assertEquals(0.9f, summary.peakUtilization)
        assertEquals(null, summary.lastInBps)
        val series = json.decodeFromString(
            PortTrafficDto.serializer(),
            """{"port_id":"p","port_name":"gi1/0/2","speed_mbps":100,"hours":24,"peak_utilization":12.5,
               "points":[{"at":"2026-10-25T10:00:00Z","in_bps":5,"out_bps":1}]}""",
        ).toDomain()
        assertEquals(1, series.points.size)
        assertEquals(1_000L, series.scaleBps)
    }
}
