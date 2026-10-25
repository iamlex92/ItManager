package com.itmanager.app

import com.itmanager.app.ui.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MotionTest {
    @Test
    fun projectionMatchesAppleFormula() {
        // 1000 px/s with 0.998 deceleration travels about 499 px.
        assertEquals(499f, Motion.project(1000f), 0.5f)
        assertEquals(-499f, Motion.project(-1000f), 0.5f)
    }

    @Test
    fun rubberbandResistsMoreTheFurtherYouPull() {
        val near = Motion.rubberband(50f, 1000f)
        val far = Motion.rubberband(500f, 1000f)
        assertTrue(near < 50f)
        assertTrue(far < 500f)
        assertTrue(far / 500f < near / 50f)
        assertTrue(Motion.rubberband(-50f, 1000f) < 0f)
        assertEquals(abs(Motion.rubberband(-50f, 1000f)), near, 0.001f)
    }
}
