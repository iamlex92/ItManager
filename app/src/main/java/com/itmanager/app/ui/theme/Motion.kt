package com.itmanager.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.PI
import kotlin.math.abs

/**
 * Motion vocabulary for the whole app, after Apple's "Designing Fluid Interfaces":
 * springs described by damping ratio and response (seconds), not by duration.
 *
 * - [smooth]: critically damped, no overshoot. The default for anything that just appears or moves.
 * - [momentum]: slight bounce, only after a gesture that carried momentum (a fling or a drag release).
 * - [press]: very fast, for touch-down feedback that must feel instant.
 */
object Motion {
    const val RESPONSE_DEFAULT = 0.4f
    const val RESPONSE_SHEET = 0.3f
    const val RESPONSE_PRESS = 0.12f

    /** Converts Apple-style response (period in seconds, unit mass) to Compose stiffness. */
    fun stiffness(response: Float): Float {
        val omega = 2 * PI.toFloat() / response
        return omega * omega
    }

    fun <T> smooth(response: Float = RESPONSE_DEFAULT, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(Spring.DampingRatioNoBouncy, stiffness(response), visibilityThreshold)

    fun <T> momentum(response: Float = RESPONSE_DEFAULT, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(0.8f, stiffness(response), visibilityThreshold)

    fun <T> press(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(Spring.DampingRatioNoBouncy, stiffness(RESPONSE_PRESS), visibilityThreshold)

    /**
     * Where a flick would come to rest with scroll-like deceleration (Apple's projection function).
     * [velocity] is in px/s; a rate of 0.998 matches normal scrolling.
     */
    fun project(velocity: Float, decelerationRate: Float = 0.998f): Float =
        (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

    /**
     * Progressive resistance past a boundary: the further the overshoot, the less the content follows.
     * Returns the displayed overshoot for a raw [overshoot] within a viewport of [dimension].
     */
    fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        return (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))
    }
}
