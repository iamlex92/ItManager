package com.itmanager.app.ui.map

import android.graphics.Picture
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.Drawing
import com.itmanager.app.data.model.MapLayer
import com.itmanager.app.data.model.PlanRect
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
import kotlin.math.hypot
import kotlin.math.roundToInt

private val PASSIVE = setOf(DeviceType.SOCKET, DeviceType.PATCH_PANEL)

/** Wide enough for a device name under the marker; only the circle itself takes touches. */
private val MARKER_SLOT_WIDTH = 120.dp

/** Pen lines look this wide at the zoom they were drawn at. */
private val PEN_WIDTH = 3.dp

/** What the map needs from the open editor. */
class MapEditing(
    val tool: EditTool,
    /** First end of a cable being made, shown as selected. */
    val linkFrom: String?,
    val penColor: Color,
    /** Positions dropped but not yet reloaded from the server. */
    val moved: Map<String, Offset>,
    val onTapDevice: (String) -> Unit,
    val onMoveDevice: (id: String, x: Float, y: Float) -> Unit,
    val onTapLink: (Connection) -> Unit,
    /** A finished pen line: x, y pairs and its width, in map pixels. */
    val onStroke: (List<Float>, Float) -> Unit,
    val onPlanMoved: (PlanRect) -> Unit,
)

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
    editor: MapEditing? = null,
) {
    // A marker being dragged, with its position in map pixels; a pen line in progress; a plan being moved.
    var drag by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    var stroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var planShift by remember { mutableStateOf(Offset.Zero) }
    val positions = editor?.moved.orEmpty().let { moved -> drag?.let { moved + it } ?: moved }
    @Suppress("NAME_SHADOWING")
    val content = remember(content, positions) { content.withPositions(positions) }
    val plan = content.map.plan
    val planRect = plan.imageUrl?.let {
        val r = plan.imageRect ?: PlanRect(0f, 0f, plan.widthPx, plan.heightPx)
        if (planShift == Offset.Zero) r else r.copy(x = r.x + planShift.x, y = r.y + planShift.y)
    }
    val currentEditor by rememberUpdatedState(editor)
    val currentContent by rememberUpdatedState(content)
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
                    onTap = { at ->
                        val e = currentEditor
                        when {
                            e == null -> currentOnSelect(null)
                            e.tool == EditTool.LINK -> linkAt(currentContent, camera, at, 16.dp.toPx())?.let(e.onTapLink)
                        }
                    },
                    onDoubleTap = { camera.toggleZoom(it) },
                )
            },
    ) {
        val penColor = editor?.penColor ?: Color.Transparent
        Canvas(Modifier.fillMaxSize()) {
            drawPlan(content, planPicture, planRect, editor?.tool == EditTool.PLAN, camera, labelStyle, textMeasurer)
            drawDrawings(plan.drawings, camera)
            if (stroke.size > 1) drawPolyline(stroke.map { camera.toScreen(it.x, it.y) }, penColor, PEN_WIDTH.toPx())
            if (layer == MapLayer.L3) drawSubnets(content, vlans, vlanFilter, camera, textMeasurer, labelStyle)
            if (layer != MapLayer.L3) drawCables(content, layer, vlans, vlanFilter, tracedLinks, camera)
        }

        val showLabels by remember(camera) { derivedStateOf { camera.scale >= camera.fitScale * 1.8f } }
        content.map.devices.forEach { device ->
            if (layer == MapLayer.L3 && device.type in PASSIVE) return@forEach
            val dimmed = vlanFilter != null && device.vlanId != vlanFilter
            val selected = if (editor != null) device.id == editor.linkFrom || device.id == drag?.first else device.id == selectedId
            key(device.id) {
                DeviceMarker(
                    device = device,
                    vlanColor = if (layer == MapLayer.L1) null else device.vlanId?.let { vlans[it] }?.color,
                    selected = selected,
                    dimmed = dimmed,
                    showLabel = showLabels || selected || editor != null,
                    camera = camera,
                    onClick = { if (editor != null) editor.onTapDevice(device.id) else onSelect(device.id) },
                    draggable = editor?.tool == EditTool.MOVE,
                    onDrag = { delta ->
                        val from = drag?.second ?: Offset(device.x, device.y)
                        drag = device.id to Offset(
                            (from.x + delta.x / camera.scale).coerceIn(0f, plan.widthPx),
                            (from.y + delta.y / camera.scale).coerceIn(0f, plan.heightPx),
                        )
                    },
                    onDragEnd = {
                        drag?.let { (id, at) -> currentEditor?.onMoveDevice(id, at.x, at.y) }
                        drag = null
                    },
                )
            }
        }

        // Pen and plan tools take one-finger drags over the whole map; two fingers still pan and zoom it.
        when (editor?.tool) {
            EditTool.DRAW -> Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    val minStep = 2.dp.toPx()
                    detectOneFingerDrag(
                        onStart = { stroke = listOf(camera.toPlan(it)) },
                        onDrag = { at, _ ->
                            val last = stroke.lastOrNull()?.let { camera.toScreen(it.x, it.y) }
                            if (last == null || (at - last).getDistance() >= minStep) {
                                if (stroke.size < MAX_STROKE_POINTS) stroke = stroke + camera.toPlan(at)
                            }
                        },
                        onEnd = {
                            val points = stroke
                            stroke = emptyList()
                            if (points.size > 1) {
                                currentEditor?.onStroke(points.flatMap { listOf(it.x, it.y) }, PEN_WIDTH.toPx() / camera.scale)
                            }
                        },
                    )
                },
            )
            EditTool.PLAN -> if (planRect != null) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectOneFingerDrag(
                            onStart = {},
                            onDrag = { _, delta -> planShift += delta / camera.scale },
                            onEnd = {
                                val p = currentContent.map.plan
                                val r = p.imageRect ?: PlanRect(0f, 0f, p.widthPx, p.heightPx)
                                if (planShift != Offset.Zero) currentEditor?.onPlanMoved(r.copy(x = r.x + planShift.x, y = r.y + planShift.y))
                                planShift = Offset.Zero
                            },
                        )
                    },
                )
            }
            else -> Unit
        }
    }
}

private fun MapContent.withPositions(positions: Map<String, Offset>): MapContent {
    if (positions.isEmpty()) return this
    val devices = map.devices.map { d -> positions[d.id]?.let { d.copy(x = it.x, y = it.y) } ?: d }
    return copy(map = map.copy(devices = devices), devicesById = devices.associateBy { it.id })
}

/** The cable nearest to a tap, if it passes within [slop] screen pixels. */
private fun linkAt(content: MapContent, camera: MapCamera, at: Offset, slop: Float): Connection? =
    content.map.connections
        .mapNotNull { link ->
            val a = content.devicesById[link.deviceAId] ?: return@mapNotNull null
            val b = content.devicesById[link.deviceBId] ?: return@mapNotNull null
            link to distanceToSegment(at, camera.toScreen(a.x, a.y), camera.toScreen(b.x, b.y))
        }
        .filter { it.second <= slop }
        .minByOrNull { it.second }
        ?.first

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    val t = if (len2 == 0f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    val q = a + ab * t
    return hypot(p.x - q.x, p.y - q.y)
}

/**
 * One-finger drag that leaves two-finger gestures to the map: a second finger before the touch slop
 * gives the gesture up, and the drag ends if one lands later. Consumes what it uses, so the map stays still.
 */
private suspend fun PointerInputScope.detectOneFingerDrag(
    onStart: (Offset) -> Unit,
    onDrag: (position: Offset, delta: Offset) -> Unit,
    onEnd: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    var travelled = Offset.Zero
    var dragging = false
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id }
        val fingers = event.changes.count { it.pressed }
        if (change == null || !change.pressed || fingers > 1) {
            if (dragging) onEnd()
            return@awaitEachGesture
        }
        if (!dragging) {
            travelled += change.positionChange()
            if (travelled.getDistance() <= viewConfiguration.touchSlop) continue
            dragging = true
            onStart(down.position)
            onDrag(change.position, travelled)
        } else {
            onDrag(change.position, change.positionChange())
        }
        change.consume()
    }
}

private fun DrawScope.drawPolyline(points: List<Offset>, color: Color, width: Float) {
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Pen lines keep their width in map pixels, so they scale with the plan like walls do. */
private fun DrawScope.drawDrawings(drawings: List<Drawing>, camera: MapCamera) {
    for (d in drawings) {
        if (d.points.size < 4) continue
        val points = (0 until d.points.size / 2).map { camera.toScreen(d.points[2 * it], d.points[2 * it + 1]) }
        drawPolyline(points, Color(d.colorArgb), d.width * camera.scale)
    }
}

private val Vlan.color get() = Color(colorArgb)

private fun DrawScope.drawPlan(
    content: MapContent,
    planPicture: Picture?,
    planRect: PlanRect?,
    highlightPlan: Boolean,
    camera: MapCamera,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
) {
    val plan = content.map.plan
    val topLeft = camera.toScreen(0f, 0f)
    val size = Size(plan.widthPx * camera.scale, plan.heightPx * camera.scale)
    val corner = CornerRadius(12.dp.toPx())
    drawRoundRect(Ink850, topLeft, size, corner)
    if (planPicture != null && planPicture.width > 0 && planRect != null) {
        val fillsFloor = plan.imageRect == null
        if (!fillsFloor) drawGrid(plan.pixelsPerMeter, topLeft, size, camera)
        val rectTopLeft = camera.toScreen(planRect.x, planRect.y)
        val rectSize = Size(planRect.width * camera.scale, planRect.height * camera.scale)
        drawPlanPicture(planPicture, rectTopLeft, rectSize)
        if (highlightPlan) {
            drawRect(
                Accent, rectTopLeft, rectSize,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))),
            )
        }
        drawRoundRect(Ink700, topLeft, size, corner, style = Stroke(1.dp.toPx()))
        return
    }

    drawGrid(plan.pixelsPerMeter, topLeft, size, camera)
    drawRoundRect(Ink700, topLeft, size, corner, style = Stroke(1.dp.toPx()))

    val wall = Stroke(1.5.dp.toPx())
    content.map.plan.rooms.forEach { room ->
        val tl = camera.toScreen(room.left, room.top)
        val br = camera.toScreen(room.right, room.bottom)
        drawRoundRect(Ink600, tl, Size(br.x - tl.x, br.y - tl.y), CornerRadius(6.dp.toPx()), style = wall)
        if (br.x - tl.x > 72.dp.toPx()) {
            drawLabel(textMeasurer, room.name, Offset(tl.x + 8.dp.toPx(), tl.y + 6.dp.toPx()), labelStyle)
        }
    }
}

/** Metre grid, coarsened when lines would crowd closer than ~14dp. */
private fun DrawScope.drawGrid(pixelsPerMeter: Float, topLeft: Offset, size: Size, camera: MapCamera) {
    var step = pixelsPerMeter.coerceAtLeast(1f) * camera.scale
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
}

/**
 * Draws a one-line label at any position, including off-canvas. drawText(textMeasurer, text, topLeft)
 * limits the layout to the space right of topLeft and throws when that is negative, which happens
 * as soon as a room or VLAN label is panned or zoomed past the right or bottom edge.
 */
private fun DrawScope.drawLabel(textMeasurer: TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    if (topLeft.x >= size.width || topLeft.y >= size.height) return
    drawText(textMeasurer.measure(text, style, maxLines = 1, softWrap = false), topLeft = topLeft)
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

/** Draws the plan scaled into its screen rectangle; a vector plan stays sharp at any zoom. */
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
            drawLabel(
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
    draggable: Boolean = false,
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val passive = device.type in PASSIVE
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
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
                .then(
                    if (!draggable) Modifier else Modifier.pointerInput(Unit) {
                        // Consuming past the touch slop takes the drag from the map, which then stays still.
                        detectDragGestures(
                            onDrag = { change, delta ->
                                change.consume()
                                currentOnDrag(delta)
                            },
                            onDragEnd = { currentOnDragEnd() },
                            onDragCancel = { currentOnDragEnd() },
                        )
                    },
                )
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
