package com.itmanager.app.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import com.itmanager.app.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pan and zoom state of the floor plan, in screen pixels.
 *
 * A plan point p is drawn at `p * scale + offset`. Gestures move the content 1:1 under the fingers,
 * any running animation is stopped on touch-down and continues from the on-screen value, and on release
 * the camera projects the fling forward and settles there with a spring that inherits the finger's velocity.
 * Past the zoom and pan limits the content resists progressively instead of hitting a wall.
 */
@Stable
class MapCamera internal constructor(private val scope: CoroutineScope) {

    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Scale at which the whole plan fits the visible area; labels and limits are relative to it. */
    var fitScale by mutableFloatStateOf(1f)
        private set

    private var viewport = Size.Zero
    private var content = Size.Zero
    private var padding = Rect.Zero
    /** False until the first layout, so the plan never flashes at an unfitted scale. */
    var initialized by mutableStateOf(false)
        private set

    private val minScale get() = fitScale * 0.85f
    private val maxScale get() = max(fitScale * 8f, 2.5f)

    // Un-resisted values while a gesture is running; what is drawn is these after rubber-banding.
    private var rawScale = 1f
    private var rawOffset = Offset.Zero
    private var pivot = Offset.Zero
    private var animation: Job? = null
    private var pendingFocus: Offset? = null

    /**
     * [padding] is the part of the viewport covered by floating controls (top panel, bottom bar),
     * so "fit" frames the plan in the area that is actually visible.
     */
    fun setGeometry(viewport: Size, content: Size, padding: Rect) {
        if (viewport.width <= 0f || content.width <= 0f) return
        val changed = viewport != this.viewport || content != this.content || padding != this.padding
        this.viewport = viewport
        this.content = content
        this.padding = padding
        val visibleW = viewport.width - padding.left - padding.right
        val visibleH = viewport.height - padding.top - padding.bottom
        fitScale = min(visibleW / content.width, visibleH / content.height).coerceAtLeast(0.01f)
        if (!initialized || (changed && animation == null && scale < minScale)) {
            initialized = true
            scale = fitScale
            offset = centeredOffset(fitScale)
            pendingFocus?.let { focusOn(it.x, it.y) }
            pendingFocus = null
        }
    }

    fun toScreen(x: Float, y: Float) = Offset(x * scale + offset.x, y * scale + offset.y)

    fun toPlan(screen: Offset) = Offset((screen.x - offset.x) / scale, (screen.y - offset.y) / scale)

    /** Touch-down: freeze any motion where it is on screen, so the user can grab the plan mid-flight. */
    fun stop() {
        animation?.cancel()
        animation = null
        rawScale = scale
        rawOffset = offset
    }

    fun onGesture(centroid: Offset, pan: Offset, zoom: Float) {
        pivot = centroid
        val nextRawScale = rawScale * zoom
        rawOffset = centroid - (centroid - rawOffset) * (nextRawScale / rawScale) + pan
        rawScale = nextRawScale

        val shownScale = resistScale(rawScale)
        val anchored = centroid - (centroid - rawOffset) * (shownScale / rawScale)
        scale = shownScale
        offset = resistOffset(anchored, shownScale)
    }

    /** Release after a drag or pinch: project where the fling is heading, clamp that into bounds and settle with the finger's velocity. */
    fun onGestureEnd(velocity: Velocity) {
        val targetScale = scale.coerceIn(minScale, maxScale)
        val anchored = pivot - (pivot - offset) * (targetScale / scale)
        val projected = Offset(anchored.x + Motion.project(velocity.x), anchored.y + Motion.project(velocity.y))
        val targetOffset = clampOffset(projected, targetScale)
        runAnimation {
            coroutineScope {
                if (targetScale != scale) {
                    launch {
                        animate(scale, targetScale, animationSpec = Motion.smooth(Motion.RESPONSE_DEFAULT)) { v, _ -> scale = v }
                    }
                }
                launch {
                    animate(
                        typeConverter = Offset.VectorConverter,
                        initialValue = offset,
                        targetValue = targetOffset,
                        initialVelocity = Offset(velocity.x, velocity.y),
                        animationSpec = Motion.smooth(0.55f),
                    ) { v, _ -> offset = v }
                }
            }
        }
    }

    /** Double tap: zoom in around the finger, or back out to the whole plan when already close. */
    fun toggleZoom(at: Offset) {
        if (scale > fitScale * 2.2f) {
            animateTo(fitScale, centeredOffset(fitScale))
        } else {
            val target = (scale * 2.5f).coerceAtMost(maxScale)
            val anchored = at - (at - offset) * (target / scale)
            animateTo(target, clampOffset(anchored, target))
        }
    }

    fun fit() = animateTo(fitScale, centeredOffset(fitScale))

    /** Brings a plan point to the middle of the visible area, zooming in so its neighbourhood is readable. */
    fun focusOn(planX: Float, planY: Float) {
        if (!initialized) {
            pendingFocus = Offset(planX, planY)
            return
        }
        val target = max(scale, fitScale * 2.5f).coerceAtMost(maxScale)
        val centre = visibleCentre()
        animateTo(target, clampOffset(Offset(centre.x - planX * target, centre.y - planY * target), target))
    }

    private fun animateTo(targetScale: Float, targetOffset: Offset) {
        val startScale = scale
        val startOffset = offset
        runAnimation {
            val progress = Animatable(0f)
            progress.animateTo(1f, Motion.smooth(Motion.RESPONSE_DEFAULT)) {
                scale = startScale + (targetScale - startScale) * value
                offset = lerp(startOffset, targetOffset, value)
            }
        }
    }

    private fun runAnimation(block: suspend CoroutineScope.() -> Unit) {
        animation?.cancel()
        val job = scope.launch(block = block)
        animation = job
        job.invokeOnCompletion { if (animation === job) animation = null }
    }

    private fun visibleCentre() = Offset(
        padding.left + (viewport.width - padding.left - padding.right) / 2f,
        padding.top + (viewport.height - padding.top - padding.bottom) / 2f,
    )

    private fun centeredOffset(s: Float): Offset {
        val centre = visibleCentre()
        return Offset(centre.x - content.width * s / 2f, centre.y - content.height * s / 2f)
    }

    /**
     * The plan may move freely while smaller than the visible area, and may not leave a gap
     * at an edge once it is larger.
     */
    private fun offsetBounds(s: Float): Rect {
        val a = viewport.width - padding.right - content.width * s
        val b = padding.left
        val c = viewport.height - padding.bottom - content.height * s
        val d = padding.top
        return Rect(min(a, b), min(c, d), max(a, b), max(c, d))
    }

    private fun clampOffset(o: Offset, s: Float): Offset {
        val r = offsetBounds(s)
        return Offset(o.x.coerceIn(r.left, r.right), o.y.coerceIn(r.top, r.bottom))
    }

    private fun resistOffset(o: Offset, s: Float): Offset {
        val r = offsetBounds(s)
        fun axis(v: Float, lo: Float, hi: Float, dim: Float) = when {
            v < lo -> lo + Motion.rubberband(v - lo, dim)
            v > hi -> hi + Motion.rubberband(v - hi, dim)
            else -> v
        }
        return Offset(axis(o.x, r.left, r.right, viewport.width), axis(o.y, r.top, r.bottom, viewport.height))
    }

    private fun resistScale(s: Float): Float = when {
        s > maxScale -> maxScale * (1f + Motion.rubberband(s / maxScale - 1f, 0.5f))
        s < minScale -> minScale / (1f + Motion.rubberband(minScale / s - 1f, 0.5f))
        else -> s
    }
}

@Composable
fun rememberMapCamera(): MapCamera {
    val scope = rememberCoroutineScope()
    return remember { MapCamera(scope) }
}

/**
 * Pan and pinch in one recogniser, reporting every move so the plan tracks the fingers continuously.
 * Velocity is measured on the centroid and reset whenever a finger is added or lifted,
 * so changing grip does not read as a fling.
 */
internal suspend fun PointerInputScope.detectMapGestures(
    onStart: () -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    /** Called only when the fingers actually moved the plan; a plain tap leaves it alone. */
    onEnd: (Velocity) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onStart()
        val tracker = VelocityTracker()
        var pointerCount = 1
        var zoomSoFar = 1f
        var panSoFar = Offset.Zero
        var pastSlop = false
        var canceled = false

        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) {
                canceled = true
                break
            }
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            if (pressed != pointerCount) {
                pointerCount = pressed
                tracker.resetTracking()
            }

            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            if (!pastSlop) {
                zoomSoFar *= zoom
                panSoFar += pan
                val zoomMotion = abs(1 - zoomSoFar) * event.calculateCentroidSize(useCurrent = false)
                pastSlop = zoomMotion > viewConfiguration.touchSlop || panSoFar.getDistance() > viewConfiguration.touchSlop
            }
            val centroid = event.calculateCentroid(useCurrent = true)
            if (centroid != Offset.Unspecified) {
                tracker.addPosition(event.changes.first().uptimeMillis, centroid)
            }
            if (pastSlop) {
                if (zoom != 1f || pan != Offset.Zero) onGesture(centroid, pan, zoom)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
        if (pastSlop) onEnd(if (canceled) Velocity.Zero else tracker.calculateVelocity())
    }
}
