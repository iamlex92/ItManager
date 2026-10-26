package com.itmanager.app

import com.itmanager.app.core.network.DrawingDto
import com.itmanager.app.core.network.FloorDto
import com.itmanager.app.core.network.FloorPatch
import com.itmanager.app.core.network.toDomain
import com.itmanager.app.core.network.toPatch
import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.Drawing
import com.itmanager.app.data.model.PlanRect
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapEditorTest {
    @Test
    fun drawingColourGoesAsHexAndComesBackOpaque() {
        val dto = DrawingDto.of(Drawing(0xFFFF453A, 4f, listOf(1f, 2f, 3f, 4f)))
        assertEquals("#FF453A", dto.color)
        assertEquals(0xFFFF453A, dto.toDomain().colorArgb)
        assertEquals(0xFFFFD60A, DrawingDto(color = "not a colour").toDomain().colorArgb)
    }

    @Test
    fun placementGrowsTheFloorToHoldThePlan() {
        val patch = FloorPatch.placement(PlanRect(-100f, 50f, 2000f, 1200f), 1600f, 1000f)
        assertEquals(-100f, patch.planX)
        assertEquals(1900, patch.widthPx)
        assertEquals(1250, patch.heightPx)
        assertNull(patch.drawings)
    }

    @Test
    fun clearedFieldsAreSentAsNull() {
        val patch = DeviceDraft("SW-04", DeviceType.SWITCH, ip = null, vendor = "Eltex", model = null).toPatch()
        assertEquals(JsonPrimitive("SW-04"), patch["name"])
        assertEquals(JsonPrimitive("SWITCH"), patch["type"])
        assertEquals(JsonNull, patch["ip"])
        assertEquals(JsonPrimitive("Eltex"), patch["vendor"])
    }

    @Test
    fun floorCarriesPlacementAndDrawings() {
        val dto = FloorDto(
            "f1", "АБК", 1, planImageUrl = "/api/v1/floors/f1/plan-image?v=abc", widthPx = 2400f, heightPx = 1200f,
            planX = 10f, planY = 20f, planW = 1000f, planH = 500f, drawings = listOf(DrawingDto("#0A84FF", 3f, listOf(0f, 0f, 5f, 5f))),
        )
        val plan = dto.toDomain("http://10.0.0.5:8000", 0f, 0f)
        assertEquals(PlanRect(10f, 20f, 1000f, 500f), plan.imageRect)
        assertEquals(0xFF0A84FF, plan.drawings.single().colorArgb)
        assertEquals("http://10.0.0.5:8000/api/v1/floors/f1/plan-image?v=abc", plan.imageUrl)
    }
}
