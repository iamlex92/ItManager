package com.itmanager.app.ui.map

import android.graphics.Picture
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.MapLayer
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.theme.Accent
import com.itmanager.app.ui.theme.Ink400
import com.itmanager.app.ui.theme.Ink600
import com.itmanager.app.ui.theme.Ink700
import com.itmanager.app.ui.theme.Ink800
import com.itmanager.app.ui.theme.Ink850
import com.itmanager.app.ui.theme.Ink950
import com.itmanager.app.ui.theme.Motion
import com.itmanager.app.ui.theme.StatusColors
import kotlin.math.roundToInt

private val PASSIVE = setOf(DeviceType.SOCKET, DeviceType.PATCH_PANEL)

/** Wide enough for a device name under the marker; only the circle itself takes touches. */
private val MARKER_SLOT_WIDTH = 120.dp

/**
 * The interactive floor plan. Everything is positioned in screen pixels from one [MapCamera],
 * so markers and cables stay glued together at any zoom and on any screen density.
 */
@Composable
fun FloorPlanView(
    content: MapContent,
    layer: MapLayer,
    vlanFilter: Int?,
    selectedId: String?,
    tracedLinks: Set<String>,
    planPicture: Picture?,
    camera: MapCamera,
    visiblePadding: Rect,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan = content.map.plan
    val vlans = remember(content) { content.map.vlans.associateBy { it.id } }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = Ink400)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Re-run when the floating panels change size too, not only the view itself.
    LaunchedEffect(viewSize, visiblePadding, plan) {
        camera.setGeometry(
            viewport = Size(viewSize.width.toFloat(), viewSize.height.toFloat()),
            content = Size(plan.widthPx, plan.heightPx),
            padding = visiblePadding,
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Ink950)
            .onSizeChanged { viewSize = it }
            .graphicsLayer { alpha = if (camera.initialized) 1f else 0f }
            .pointerInput(camera) {
                detectMapGestures(
                    onStart = camera::stop,
                    onGesture = camera::onGesture,
                    onEnd = camera::onGestureEnd,
                )
            }
            .pointerInput(camera) {
                detectTapGestures(
                    onTap = { currentOnSelect(null) },
                    onDoubleTap = { camera.toggleZoom(it) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawPlan(content, planPicture, camera, labelStyle, textMeasurer)
            if (layer == MapLayer.L3) drawSubnets(content, vlans, vlanFilter, camera, textMeasurer, labelStyle)
            if (layer != MapLayer.L3) drawCables(content, layer, vlans, vlanFilter, tracedLinks, camera)
        }

        val showLabels by remember(camera) { derivedStateOf { camera.scale >= camera.fitScale * 1.8f } }
        content.map.devices.forEach { device ->
            if (layer == MapLayer.L3 && device.type in PASSIVE) return@forEach
            val dimmed = vlanFilter != null && device.vlanId != vlanFilter
            DeviceMarker(
                device = device,
                vlanColor = if (layer == MapLayer.L1) null else device.vlanId?.let { vlans[it] }?.color,
                selected = device.id == selectedId,
                dimmed = dimmed,
                showLabel = showLabels || device.id == selectedId,
                camera = camera,
                onClick = { onSelect(device.id) },
            )
        }
    }
}

private val Vlan.color get() = Color(colorArgb)

private fun DrawScope.drawPlan(
    content: MapContent,
    planPicture: Picture?,
    camera: MapCamera,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
) {
    val plan = content.map.plan
    val topLeft = camera.toScreen(0f, 0f)
    val size = Size(plan.widthPx * camera.scale, plan.heightPx * camera.scale)
    val corner = CornerRadius(12.dp.toPx())
    drawRoundRect(Ink850, topLeft, size, corner)
    if (planPicture != null && planPicture.width > 0) {
        drawPlanPicture(planPicture, topLeft, size)
        drawRoundRect(Ink700, topLeft, size, corner, style = Stroke(1.dp.toPx()))
        return
    }

    // Metre grid, coarsened when lines would crowd closer than ~14dp.
    var step = plan.pixelsPerMeter.coerceAtLeast(1f) * camera.scale
    while (step < 14.dp.toPx()) step *= 5f
    val gridColor = Color.White.copy(alpha = 0.035f)
    var x = topLeft.x + step
    while (x < topLeft.x + size.width) {
        if (x >= 0f && x <= this.size.width) drawLine(gridColor, Offset(x, topLeft.y), Offset(x, topLeft.y + size.height), 1f)
        x += step
    }
    var y = topLeft.y + step
    while (y < topLeft.y + size.height) {
        if (y >= 0f && y <= this.size.height) drawLine(gridColor, Offset(topLeft.x, y), Offset(topLeft.x + size.width, y), 1f)
        y += step
    }
    drawRoundRect(Ink700, topLeft, size, corner, style = Stroke(1.dp.toPx()))

    val wall = Stroke(1.5.dp.toPx())
    content.map.plan.rooms.forEach { room ->
        val tl = camera.toScreen(room.left, room.top)
        val br = camera.toScreen(room.right, room.bottom)
        drawRoundRect(Ink600, tl, Size(br.x - tl.x, br.y - tl.y), CornerRadius(6.dp.toPx()), style = wall)
        if (br.x - tl.x > 72.dp.toPx()) {
            drawText(textMeasurer, room.name, Offset(tl.x + 8.dp.toPx(), tl.y + 6.dp.toPx()), labelStyle)
        }
    }
}

/**
 * Plans are drawn light-on-white for printing; this matrix inverts and dims them so the plan
 * sits in the dark UI as a quiet background: paper becomes near-black, walls become mid-grey.
 */
private val darkPlanPaint = android.graphics.Paint().apply {
    colorFilter = android.graphics.ColorMatrixColorFilter(
        floatArrayOf(
            -0.55f, 0f, 0f, 0f, 150f,
            0f, -0.55f, 0f, 0f, 155f,
            0f, 0f, -0.55f, 0f, 165f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/** Draws the vector plan scaled into the plan's screen rectangle; stays sharp at any zoom. */
private fun DrawScope.drawPlanPicture(picture: Picture, topLeft: Offset, size: Size) {
    // Only the visible part needs an offscreen layer.
    val left = maxOf(topLeft.x, 0f)
    val top = maxOf(topLeft.y, 0f)
    val right = minOf(topLeft.x + size.width, this.size.width)
    val bottom = minOf(topLeft.y + size.height, this.size.height)
    if (right <= left || bottom <= top) return
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val layer = native.saveLayer(left, top, right, bottom, darkPlanPaint)
        native.translate(topLeft.x, topLeft.y)
        native.scale(size.width / picture.width, size.height / picture.height)
        native.drawPicture(picture)
        native.restoreToCount(layer)
    }
}

private fun DrawScope.drawCables(
    content: MapContent,
    layer: MapLayer,
    vlans: Map<Int, Vlan>,
    vlanFilter: Int?,
    tracedLinks: Set<String>,
    camera: MapCamera,
) {
    val devices = content.devicesById
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
    val thin = 1.5.dp.toPx()
    val thick = 3.dp.toPx()

    content.map.connections.forEach { link ->
        if (link.id in tracedLinks) return@forEach
        val a = devices[link.deviceAId] ?: return@forEach
        val b = devices[link.deviceBId] ?: return@forEach
        val start = camera.toScreen(a.x, a.y)
        val end = camera.toScreen(b.x, b.y)
        val isTrunk = a.type == DeviceType.SWITCH && b.type == DeviceType.SWITCH
        val isFiber = link.cableType.startsWith("OM", ignoreCase = true) || link.cableType.startsWith("OS", ignoreCase = true)
        val vlan = content.linkVlan[link.id]

        when (layer) {
            MapLayer.L1 -> drawLine(
                color = if (isFiber) Color(0xFF5AC8FA).copy(alpha = 0.7f) else Ink400.copy(alpha = 0.7f),
                start = start, end = end, strokeWidth = if (isFiber) thick * 0.75f else thin,
                pathEffect = if (isFiber) null else dash, cap = StrokeCap.Round,
            )
            else -> {
                val base = when {
                    isTrunk -> Color.White.copy(alpha = 0.55f)
                    vlan != null -> vlans[vlan]?.color ?: Ink400
                    else -> Ink400
                }
                val faded = vlanFilter != null && !isTrunk && vlan != vlanFilter
                drawLine(
                    color = if (faded) base.copy(alpha = 0.12f) else base,
                    start = start, end = end, strokeWidth = if (isTrunk) thick else thin * 1.3f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }

    // The traced path goes last so it is never hidden under other cables, with a soft glow beneath.
    content.map.connections.forEach { link ->
        if (link.id !in tracedLinks) return@forEach
        val a = devices[link.deviceAId] ?: return@forEach
        val b = devices[link.deviceBId] ?: return@forEach
        val start = camera.toScreen(a.x, a.y)
        val end = camera.toScreen(b.x, b.y)
        drawLine(Accent.copy(alpha = 0.25f), start, end, strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Accent, start, end, strokeWidth = thick, cap = StrokeCap.Round)
    }
}

/** L3: each subnet as a translucent region around its hosts. */
private fun DrawScope.drawSubnets(
    content: MapContent,
    vlans: Map<Int, Vlan>,
    vlanFilter: Int?,
    camera: MapCamera,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val inset = 26.dp.toPx()
    val corner = CornerRadius(14.dp.toPx())
    content.map.devices
        .filter { it.vlanId != null && it.type !in PASSIVE }
        .groupBy { it.vlanId!! }
        .forEach { (vlanId, hosts) ->
            val vlan = vlans[vlanId] ?: return@forEach
            val faded = vlanFilter != null && vlanFilter != vlanId
            val points = hosts.map { camera.toScreen(it.x, it.y) }
            val left = points.minOf { it.x } - inset
            val top = points.minOf { it.y } - inset
            val right = points.maxOf { it.x } + inset
            val bottom = points.maxOf { it.y } + inset
            val alpha = if (faded) 0.25f else 1f
            drawRoundRect(vlan.color.copy(alpha = 0.10f * alpha), Offset(left, top), Size(right - left, bottom - top), corner)
            drawRoundRect(
                vlan.color.copy(alpha = 0.55f * alpha), Offset(left, top), Size(right - left, bottom - top), corner,
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
            drawText(
                textMeasurer, "VLAN ${vlan.id} · ${vlan.subnet}",
                Offset(left + 6.dp.toPx(), top - 16.dp.toPx()),
                labelStyle.copy(color = vlan.color.copy(alpha = alpha), fontSize = 10.sp),
            )
        }
}

@Composable
private fun DeviceMarker(
    device: Device,
    vlanColor: Color?,
    selected: Boolean,
    dimmed: Boolean,
    showLabel: Boolean,
    camera: MapCamera,
    onClick: () -> Unit,
) {
    val passive = device.type in PASSIVE
    val size: Dp = if (passive) 22.dp else 34.dp
    val emphasis by animateFloatAsState(if (selected) 1.18f else 1f, Motion.momentum(Motion.RESPONSE_SHEET), label = "markerScale")
    val alpha by animateFloatAsState(if (dimmed) 0.22f else 1f, Motion.smooth(Motion.RESPONSE_SHEET), label = "markerAlpha")
    val interaction = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(MARKER_SLOT_WIDTH)
            .offset {
                // Centre the circle on the device's plan point, in pixels, so density never shifts it.
                val p = camera.toScreen(device.x, device.y)
                IntOffset(
                    p.x.roundToInt() - MARKER_SLOT_WIDTH.roundToPx() / 2,
                    p.y.roundToInt() - size.roundToPx() / 2,
                )
            }
            .graphicsLayer { this.alpha = alpha },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = emphasis
                    scaleY = emphasis
                }
                .background(if (selected) Accent else Ink800, CircleShape)
                .border(
                    width = if (vlanColor != null) 2.dp else 1.dp,
                    color = vlanColor ?: Ink600,
                    shape = CircleShape,
                )
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        ) {
            Icon(
                device.type.icon,
                contentDescription = device.name,
                tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(if (passive) 13.dp else 18.dp),
            )
            if (!passive) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(9.dp)
                        .background(if (device.isOnline) StatusColors.Online else StatusColors.Offline, CircleShape)
                        .border(1.5.dp, Ink950, CircleShape),
                )
            }
        }
        if (showLabel) {
            Text(
                device.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .background(Ink950.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
