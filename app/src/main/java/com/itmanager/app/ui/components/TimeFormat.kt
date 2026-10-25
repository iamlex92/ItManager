package com.itmanager.app.ui.components

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/** "только что", "12 мин назад", "3 ч назад", then a date: how long ago matters more than the clock time. */
fun formatAgo(then: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val elapsed = Duration.between(then, now)
    return when {
        elapsed.seconds < 60 -> "только что"
        elapsed.toMinutes() < 60 -> "${elapsed.toMinutes()} мин назад"
        elapsed.toHours() < 24 -> "${elapsed.toHours()} ч назад"
        else -> dateTime.format(then.atZone(zone))
    }
}
