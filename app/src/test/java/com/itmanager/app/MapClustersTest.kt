package com.itmanager.app

import com.itmanager.app.data.model.Annotation
import com.itmanager.app.data.model.AnnotationKind
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.MapClusters
import com.itmanager.app.core.network.AnnotationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapClustersTest {
    private fun dev(id: String, x: Float, y: Float, type: DeviceType = DeviceType.WORKSTATION) =
        Device(id, id, type, null, null, null, null, null, x, y, isOnline = true, vlanId = null)

    @Test
    fun `close devices merge, far ones stay apart`() {
        val devices = listOf(dev("pc1", 100f, 100f), dev("pc2", 110f, 105f), dev("sw", 104f, 98f, DeviceType.SWITCH), dev("far", 500f, 500f))
        val clusters = MapClusters.cluster(devices, radius = 30f)
        assertEquals(2, clusters.size)
        val group = clusters.first { !it.isSingle }
        // The switch leads the group
        assertEquals("sw", group.deviceIds.first())
        assertEquals(setOf("pc1", "pc2", "sw"), group.deviceIds.toSet())
        assertEquals((100f + 110f + 104f) / 3f, group.x, 0.01f)
        // Zoomed in: everyone on its own
        assertEquals(4, MapClusters.cluster(devices, radius = 3f).size)
    }

    @Test
    fun `the selected device is never hidden in a group`() {
        val devices = listOf(dev("a", 0f, 0f), dev("b", 5f, 5f), dev("c", 8f, 0f))
        val clusters = MapClusters.cluster(devices, radius = 50f, keepApart = setOf("b"))
        assertTrue(clusters.any { it.deviceIds == listOf("b") })
        assertEquals(setOf("a", "c"), clusters.first { !it.isSingle }.deviceIds.toSet())
        // Every device appears exactly once
        assertEquals(3, clusters.sumOf { it.deviceIds.size })
    }

    @Test
    fun `annotations parse and move whole`() {
        val a = AnnotationDto("a1", "ARROW", 10f, 10f, x2 = 50f, y2 = 20f, text = "Выход", color = "#FF453A").toDomain()
        assertEquals(AnnotationKind.ARROW, a.kind)
        assertEquals(0xFFFF453A, a.colorArgb)
        val moved = a.movedBy(5f, -5f)
        assertEquals(55f, moved.x2!!, 0.001f)
        assertEquals(5f, moved.y, 0.001f)
        val back = AnnotationDto.of(Annotation("b", AnnotationKind.BADGE, 1f, 2f, "214", 0xFF0A84FF))
        assertEquals("BADGE", back.kind)
        assertEquals("#0A84FF", back.color)
    }
}
