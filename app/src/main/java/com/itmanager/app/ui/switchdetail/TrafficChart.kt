package com.itmanager.app.ui.switchdetail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.itmanager.app.data.model.TrafficSeries
import com.itmanager.app.data.model.formatBps
import com.itmanager.app.ui.components.StatusDot
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// Two series checked for colour-blind separation against the sheet surface (Ink800).
private val InColor = Color(0xFF3D8BEB)
private val OutColor = Color(0xFFB08A1E)
private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** The port sheet's "load over the day" block: loading, an error, or the chart. */
@Composable
fun PortTrafficSection(traffic: PortTraffic?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("Загрузка за 24 часа", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        when {
            traffic == null || (traffic.series == null && traffic.error == null) -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Загружаю…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            traffic.error != null -> Text(traffic.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            traffic.series!!.points.isEmpty() -> Text(
                "Данных пока нет: сервер собирает их раз в 5 минут.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> TrafficChart(traffic.series)
        }
    }
}

/**
 * Incoming and outgoing throughput on one axis. Drag or tap to read a moment; otherwise the
 * header shows the latest values.
 */
@Composable
fun TrafficChart(series: TrafficSeries, modifier: Modifier = Modifier) {
    val points = series.points
    val scale = series.scaleBps.toFloat()
    var selected by remember(series) { mutableStateOf<Int?>(null) }
    val shown = points[selected ?: points.lastIndex]
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val crosshair = MaterialTheme.colorScheme.onSurfaceVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        // Legend and readout: identity comes from the dot and the word, values stay in text colour.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendValue(InColor, "Входящий", formatBps(shown.inBps))
            LegendValue(OutColor, "Исходящий", formatBps(shown.outBps))
            Spacer(Modifier.weight(1f))
            Text(if (selected == null) "сейчас" else clock.format(shown.at), style = MaterialTheme.typography.labelMedium, color = muted)
        }
        Spacer(Modifier.height(8.dp))
        Box {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .semantics {
                        contentDescription = "График загрузки: пик ${series.peakUtilization.roundToInt()}% от скорости порта, " +
                            "сейчас входящий ${formatBps(points.last().inBps)}, исходящий ${formatBps(points.last().outBps)}"
                    }
                    .pointerInput(series) {
                        fun pick(x: Float) {
                            selected = ((x / size.width) * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
                        }
                        detectTapGestures { pick(it.x) }
                    }
                    .pointerInput(series) {
                        // Horizontal only, so the sheet still scrolls when the finger moves up or down.
                        detectHorizontalDragGestures(
                            onDragStart = { selected = ((it.x / size.width) * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex) },
                            onDragEnd = { selected = null },
                            onDragCancel = { selected = null },
                        ) { change, _ ->
                            selected = ((change.position.x / size.width) * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                fun x(i: Int) = if (points.size == 1) w / 2 else w * i / (points.size - 1)
                fun y(v: Long) = h - (v / scale).coerceIn(0f, 1f) * h
                // Recessive grid: zero, half, top.
                listOf(0f, 0.5f, 1f).forEach { f ->
                    drawLine(grid, Offset(0f, h - f * h), Offset(w, h - f * h), strokeWidth = 1f)
                }
                val inPath = Path()
                val outPath = Path()
                points.forEachIndexed { i, p ->
                    if (i == 0) inPath.moveTo(x(i), y(p.inBps)) else inPath.lineTo(x(i), y(p.inBps))
                    if (i == 0) outPath.moveTo(x(i), y(p.outBps)) else outPath.lineTo(x(i), y(p.outBps))
                }
                val area = Path().apply {
                    addPath(inPath)
                    lineTo(x(points.lastIndex), h)
                    lineTo(x(0), h)
                    close()
                }
                drawPath(area, InColor.copy(alpha = 0.12f))
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(outPath, OutColor, style = stroke)
                drawPath(inPath, InColor, style = stroke)
                selected?.let { i ->
                    drawLine(crosshair, Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 1.dp.toPx())
                    drawCircle(InColor, 4.dp.toPx(), Offset(x(i), y(points[i].inBps)))
                    drawCircle(OutColor, 4.dp.toPx(), Offset(x(i), y(points[i].outBps)))
                }
            }
            Text(formatBps(series.scaleBps), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.align(Alignment.TopStart))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(clock.format(points.first().at), style = MaterialTheme.typography.labelSmall, color = muted)
            Spacer(Modifier.weight(1f))
            Text(clock.format(points.last().at), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Пик ${series.peakUtilization.roundToInt()}% от ${if (series.speedMbps >= 1000) "${series.speedMbps / 1000} Гбит/с" else "${series.speedMbps} Мбит/с"}",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}

@Composable
private fun LegendValue(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, size = 8.dp)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
    }
}
