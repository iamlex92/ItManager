package com.itmanager.app

import com.itmanager.app.core.network.FloorDto
import com.itmanager.app.core.network.toDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloorDtoTest {
    private fun floor(url: String?) = FloorDto("f1", "АБК", 1, planImageUrl = url, widthPx = 2400f, heightPx = 1200f)

    @Test
    fun relativePlanUrlIsResolvedAgainstServer() {
        val plan = floor("/static/plans/floor-1.svg").toDomain("http://10.0.0.5:8000", 0f, 0f)
        assertEquals("http://10.0.0.5:8000/static/plans/floor-1.svg", plan.imageUrl)
        assertEquals(2400f, plan.widthPx)
    }

    @Test
    fun absolutePlanUrlIsKept() {
        val plan = floor("https://cdn.local/p.svg").toDomain("http://10.0.0.5:8000", 0f, 0f)
        assertEquals("https://cdn.local/p.svg", plan.imageUrl)
    }

    @Test
    fun missingPlanUrlMeansNoImage() {
        assertNull(floor(null).toDomain("http://10.0.0.5:8000", 0f, 0f).imageUrl)
        assertNull(floor(" ").toDomain("http://10.0.0.5:8000", 0f, 0f).imageUrl)
    }
}
