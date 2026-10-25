package com.itmanager.app.data.model

import java.time.Instant
import java.util.Locale
import kotlin.math.max

/** One port's throughput over the chosen window, from the server's 5-minute samples. */
data class PortLoad(
    val portId: String,
    val portName: String,
    /** Busiest direction at its peak, percent of the port speed. */
    val peakUtilization: Float,
    val avgInBps: Long,
    val avgOutBps: Long,
    val peakInBps: Long,
    val peakOutBps: Long,
    val lastInBps: Long?,
    val lastOutBps: Long?,
) {
    val level: LoadLevel get() = LoadLevel.of(peakUtilization)
}

data class TrafficPoint(val at: Instant, val inBps: Long, val outBps: Long)

data class TrafficSeries(
    val portId: String,
    val speedMbps: Int,
    val hours: Int,
    val peakUtilization: Float,
    val points: List<TrafficPoint>,
) {
    /** Top of the chart: the highest value rounded up, so the line never touches the edge. */
    val scaleBps: Long
        get() = niceCeiling(max(points.maxOfOrNull { max(it.inBps, it.outBps) } ?: 0L, 1_000L))
}

enum class LoadLevel(val label: String) {
    LOW("до 30%"),
    MEDIUM("30–70%"),
    HIGH("больше 70%");

    companion object {
        fun of(percent: Float): LoadLevel = when {
            percent >= 70f -> HIGH
            percent >= 30f -> MEDIUM
            else -> LOW
        }
    }
}

/** 1, 2 or 5 times a power of ten, at least [value]. */
fun niceCeiling(value: Long): Long {
    var magnitude = 1L
    while (magnitude * 10 <= value) magnitude *= 10
    return listOf(1L, 2L, 5L, 10L).map { it * magnitude }.first { it >= value }
}

/** "0 бит/с", "850 Кбит/с", "12,5 Мбит/с", "1,2 Гбит/с". */
fun formatBps(bps: Long): String {
    val units = listOf(1_000_000_000L to "Гбит/с", 1_000_000L to "Мбит/с", 1_000L to "Кбит/с")
    val (div, unit) = units.firstOrNull { bps >= it.first } ?: return "$bps бит/с"
    val v = bps.toDouble() / div
    val text = if (v >= 100 || v == Math.floor(v)) "%.0f".format(Locale.ROOT, v) else "%.1f".format(Locale.ROOT, v).replace('.', ',')
    return "$text $unit"
}
