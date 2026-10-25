package com.itmanager.app

import com.itmanager.app.core.network.parseInstant
import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.AlertSeverity
import com.itmanager.app.data.model.sortedForDisplay
import com.itmanager.app.data.model.upsert
import com.itmanager.app.ui.components.formatAgo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class AlertsTest {
    private val now = Instant.parse("2026-10-25T12:00:00Z")

    private fun alert(id: String, severity: AlertSeverity, minutesAgo: Long, resolved: Boolean = false) = Alert(
        id, "d", null, severity, "LINK_DOWN", "", resolved, now.minusSeconds(minutesAgo * 60), null,
    )

    @Test
    fun openFirstThenSeverityThenNewest() {
        val list = listOf(
            alert("old-info", AlertSeverity.INFO, 50),
            alert("resolved-critical", AlertSeverity.CRITICAL, 1, resolved = true),
            alert("warning", AlertSeverity.WARNING, 5),
            alert("critical-old", AlertSeverity.CRITICAL, 30),
            alert("critical-new", AlertSeverity.CRITICAL, 2),
        )
        assertEquals(
            listOf("critical-new", "critical-old", "warning", "old-info", "resolved-critical"),
            list.sortedForDisplay().map { it.id },
        )
    }

    @Test
    fun resolvedAlertReplacesTheOpenOne() {
        val open = alert("a", AlertSeverity.WARNING, 5)
        val list = listOf(open).upsert(open.copy(isResolved = true))
        assertEquals(1, list.size)
        assertEquals(true, list.single().isResolved)
        assertEquals(2, list.upsert(alert("b", AlertSeverity.INFO, 1)).size)
    }

    @Test
    fun serverTimesParse() {
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), parseInstant("2026-10-25T12:00:00.123456+03:00")?.minusNanos(123_456_000))
        assertEquals(Instant.parse("2026-10-25T12:00:00Z"), parseInstant("2026-10-25T12:00:00Z"))
        assertEquals(Instant.parse("2026-10-25T12:00:00Z"), parseInstant("2026-10-25T12:00:00"))
        assertNull(parseInstant(null))
        assertNull(parseInstant("вчера"))
    }

    @Test
    fun agoReadsNaturally() {
        assertEquals("только что", formatAgo(now.minusSeconds(20), now))
        assertEquals("12 мин назад", formatAgo(now.minusSeconds(12 * 60), now))
        assertEquals("3 ч назад", formatAgo(now.minusSeconds(3 * 3600 + 100), now))
        assertEquals("23.10 12:00", formatAgo(now.minusSeconds(2 * 86_400), now, ZoneOffset.UTC))
    }
}
