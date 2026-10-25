package com.itmanager.app.data.source

import com.itmanager.app.data.model.PortLoad
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.TrafficPoint
import com.itmanager.app.data.model.TrafficSeries
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.max
import kotlin.math.min

/**
 * Made-up but believable port load for demo mode: quiet at night and at weekends, busy in working hours,
 * a dip at lunch. The same curve the server's emulator uses, so demo and emulated server look alike.
 */
internal object DemoTraffic {
    private val plantZone = ZoneOffset.ofHours(3)
    private const val STEP_SECONDS = 300L

    fun workdayLoad(at: Instant): Double {
        val local = at.atOffset(plantZone)
        val h = local.hour + local.minute / 60.0
        return when {
            local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY -> 0.08
            h < 7 || h >= 20 -> 0.04
            h < 9 -> 0.04 + 0.96 * (h - 7) / 2
            h >= 12 && h < 13 -> 0.55
            h >= 17 -> 1.0 - 0.96 * (h - 17) / 3
            else -> 1.0
        }
    }

    /** Stable number in [0, 1) for the same inputs. */
    private fun noise(vararg parts: Any): Double {
        var x = parts.joinToString(":").hashCode().toLong() * -0x61c8864680b583ebL
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
        x = x xor (x ushr 31)
        return (x ushr 11).toDouble() / (1L shl 53)
    }

    fun rate(port: SwitchPort, at: Instant): Pair<Long, Long> {
        if (!port.isAdminUp || !port.isOperUp) return 0L to 0L
        val bucket = at.epochSecond / STEP_SECONDS
        val load = workdayLoad(at)
        val peakMbps = if (port.isUplink) {
            150 + 350 * noise(port.id, "peak")
        } else {
            3 + (if (noise(port.id, "heavy") > 0.85) 80 else 25) * noise(port.id, "peak")
        }
        val down = peakMbps * load * (0.6 + 0.8 * noise(port.id, bucket, "in"))
        val up = down * (0.15 + 0.35 * noise(port.id, bucket, "out"))
        val cap = port.speedMbps * 1_000_000L
        return min((down * 1_000_000).toLong(), cap) to min((up * 1_000_000).toLong(), cap)
    }

    private fun samples(port: SwitchPort, hours: Int, now: Instant): List<TrafficPoint> {
        val end = now.epochSecond / STEP_SECONDS * STEP_SECONDS
        val count = hours * 3600 / STEP_SECONDS
        return (count - 1 downTo 0).map { k ->
            val at = Instant.ofEpochSecond(end - k * STEP_SECONDS)
            val (i, o) = rate(port, at)
            TrafficPoint(at, i, o)
        }
    }

    private fun utilization(bps: Long, speedMbps: Int): Float =
        if (speedMbps > 0) (100.0 * bps / (speedMbps * 1_000_000.0)).toFloat() else 0f

    fun summary(ports: List<SwitchPort>, hours: Int, now: Instant = Instant.now()): List<PortLoad> = ports.map { port ->
        val s = samples(port, hours, now)
        val peakIn = s.maxOf { it.inBps }
        val peakOut = s.maxOf { it.outBps }
        PortLoad(
            portId = port.id, portName = port.name,
            peakUtilization = utilization(max(peakIn, peakOut), port.speedMbps),
            avgInBps = s.sumOf { it.inBps } / s.size, avgOutBps = s.sumOf { it.outBps } / s.size,
            peakInBps = peakIn, peakOutBps = peakOut,
            lastInBps = s.last().inBps, lastOutBps = s.last().outBps,
        )
    }

    fun series(port: SwitchPort, hours: Int, points: Int, now: Instant = Instant.now()): TrafficSeries {
        val s = samples(port, hours, now)
        val start = now.minus(Duration.ofHours(hours.toLong()))
        val bucket = max(hours * 3600.0 / points, 1.0)
        val grouped = s.groupBy { ((it.at.epochSecond - start.epochSecond) / bucket).toInt() }.toSortedMap()
        val series = grouped.values.map { g -> TrafficPoint(g.last().at, g.sumOf { it.inBps } / g.size, g.sumOf { it.outBps } / g.size) }
        val peak = s.maxOf { max(it.inBps, it.outBps) }
        return TrafficSeries(port.id, port.speedMbps, hours, utilization(peak, port.speedMbps), series)
    }
}
