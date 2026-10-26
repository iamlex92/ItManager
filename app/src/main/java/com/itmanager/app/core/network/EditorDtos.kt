package com.itmanager.app.core.network

import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.Drawing
import com.itmanager.app.data.model.PlanRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Request bodies of the map editor. The client omits nulls, so each body carries only what it sets.

@Serializable
data class DrawingDto(
    val color: String = "#FFD60A",
    val width: Float = 4f,
    val points: List<Float> = emptyList(),
) {
    fun toDomain() = Drawing(parseColor(color), width, points)

    companion object {
        fun of(d: Drawing) = DrawingDto("#%06X".format(d.colorArgb and 0xFFFFFF), d.width, d.points)

        private fun parseColor(hex: String): Long =
            hex.removePrefix("#").toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0xFFFFD60A
    }
}

@Serializable
data class FloorCreateBody(
    @SerialName("building_name") val buildingName: String,
    @SerialName("floor_number") val floorNumber: Int,
    @SerialName("width_px") val widthPx: Int,
    @SerialName("height_px") val heightPx: Int,
)

@Serializable
data class FloorPatch(
    @SerialName("plan_x") val planX: Float? = null,
    @SerialName("plan_y") val planY: Float? = null,
    @SerialName("plan_w") val planW: Float? = null,
    @SerialName("plan_h") val planH: Float? = null,
    @SerialName("width_px") val widthPx: Int? = null,
    @SerialName("height_px") val heightPx: Int? = null,
    val drawings: List<DrawingDto>? = null,
) {
    companion object {
        /** The plan's place on the map; the floor grows to keep it inside. */
        fun placement(rect: PlanRect, floorWidth: Float, floorHeight: Float) = FloorPatch(
            planX = rect.x, planY = rect.y, planW = rect.width, planH = rect.height,
            widthPx = maxOf(floorWidth, rect.right).toInt(), heightPx = maxOf(floorHeight, rect.bottom).toInt(),
        )

        fun drawings(list: List<Drawing>) = FloorPatch(drawings = list.map(DrawingDto::of))
    }
}

@Serializable
data class DeviceBody(
    val name: String,
    val type: String,
    @SerialName("floor_id") val floorId: String?,
    val x: Float,
    val y: Float,
    val ip: String?,
    val vendor: String?,
    val model: String?,
)

@Serializable
data class DeviceMove(val x: Float, val y: Float, @SerialName("floor_id") val floorId: String? = null)

/** The device form, nulls included: a field the user cleared is cleared on the server too. */
fun DeviceDraft.toPatch(): JsonObject = buildJsonObject {
    put("name", name)
    put("type", type.name)
    put("ip", ip?.let { JsonPrimitive(it) } ?: JsonNull)
    put("vendor", vendor?.let { JsonPrimitive(it) } ?: JsonNull)
    put("model", model?.let { JsonPrimitive(it) } ?: JsonNull)
}

@Serializable
data class LinkBody(
    @SerialName("device_a_id") val deviceAId: String,
    @SerialName("device_b_id") val deviceBId: String,
    @SerialName("cable_type") val cableType: String,
)
