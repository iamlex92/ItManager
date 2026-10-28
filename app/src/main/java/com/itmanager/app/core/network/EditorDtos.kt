package com.itmanager.app.core.network

import com.itmanager.app.data.model.Annotation
import com.itmanager.app.data.model.AnnotationKind
import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.Drawing
import com.itmanager.app.data.model.PlanRect
import com.itmanager.app.data.model.PortDraft
import com.itmanager.app.data.model.parseColorHex
import com.itmanager.app.data.model.Zone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
data class ZoneDto(
    val id: String,
    val name: String = "",
    @SerialName("is_room") val isRoom: Boolean = true,
    val color: String = "#0A84FF",
    val points: List<Float> = emptyList(),
) {
    fun toDomain() = Zone(id, name, isRoom, parseColorHex(color, 0xFF0A84FF), points)

    companion object {
        fun of(z: Zone) = ZoneDto(z.id, z.name, z.isRoom, "#%06X".format(z.colorArgb and 0xFFFFFF), z.points)
    }
}

@Serializable
data class AnnotationDto(
    val id: String,
    val kind: String = "TEXT",
    val x: Float,
    val y: Float,
    val x2: Float? = null,
    val y2: Float? = null,
    val text: String = "",
    val color: String = "#FFD60A",
    val size: Float = 1f,
) {
    fun toDomain() = Annotation(id, AnnotationKind.parse(kind), x, y, text, parseColorHex(color, 0xFFFFD60A), x2, y2, size)

    companion object {
        fun of(a: Annotation) =
            AnnotationDto(a.id, a.kind.name, a.x, a.y, a.x2, a.y2, a.text.take(100), "#%06X".format(a.colorArgb and 0xFFFFFF), a.size)
    }
}

@Serializable
data class FloorCreateBody(
    @SerialName("building_name") val buildingName: String,
    @SerialName("floor_number") val floorNumber: Int,
    @SerialName("width_px") val widthPx: Int,
    @SerialName("height_px") val heightPx: Int,
    val name: String? = null,
)

/** Name and number of a floor; an empty name goes as "" so the server clears it. */
@Serializable
data class FloorRename(
    @SerialName("building_name") val buildingName: String,
    @SerialName("floor_number") val floorNumber: Int,
    val name: String,
)

@Serializable
data class RouteBody(val waypoints: List<WaypointDto>)

@Serializable
data class FloorPatch(
    @SerialName("plan_x") val planX: Float? = null,
    @SerialName("plan_y") val planY: Float? = null,
    @SerialName("plan_w") val planW: Float? = null,
    @SerialName("plan_h") val planH: Float? = null,
    @SerialName("width_px") val widthPx: Int? = null,
    @SerialName("height_px") val heightPx: Int? = null,
    val drawings: List<DrawingDto>? = null,
    val zones: List<ZoneDto>? = null,
    val annotations: List<AnnotationDto>? = null,
) {
    companion object {
        /** The plan's place on the map; the floor grows to keep it inside. */
        fun placement(rect: PlanRect, floorWidth: Float, floorHeight: Float) = FloorPatch(
            planX = rect.x, planY = rect.y, planW = rect.width, planH = rect.height,
            widthPx = maxOf(floorWidth, rect.right).toInt(), heightPx = maxOf(floorHeight, rect.bottom).toInt(),
        )

        fun drawings(list: List<Drawing>) = FloorPatch(drawings = list.map(DrawingDto::of))

        fun zones(list: List<Zone>) = FloorPatch(zones = list.map(ZoneDto::of))

        fun annotations(list: List<Annotation>) = FloorPatch(annotations = list.map(AnnotationDto::of))
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
    @SerialName("host_id") val hostId: String? = null,
    val comment: String? = null,
    @SerialName("rack_id") val rackId: String? = null,
    @SerialName("rack_u") val rackU: Int? = null,
    @SerialName("rack_h") val rackH: Int? = null,
    @SerialName("rack_units") val rackUnits: Int? = null,
    val mac: String? = null,
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
    put("host_id", hostId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("comment", comment?.let { JsonPrimitive(it) } ?: JsonNull)
    put("rack_id", rackId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("rack_u", rackU?.let { JsonPrimitive(it) } ?: JsonNull)
    put("rack_h", rackH?.let { JsonPrimitive(it) } ?: JsonNull)
    put("rack_units", rackUnits?.let { JsonPrimitive(it) } ?: JsonNull)
    put("mac", mac?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull)
    metadata?.let { put("metadata", JsonObject(it)) }
}

@Serializable
data class BulkSetBody(
    val type: String? = null,
    @SerialName("floor_id") val floorId: String? = null,
    val vendor: String? = null,
    @SerialName("host_id") val hostId: String? = null,
)

@Serializable
data class BulkUpdateBody(val ids: List<String>, val set: BulkSetBody)

@Serializable
data class BulkDeleteBody(val ids: List<String>)

@Serializable
data class BulkResultDto(val changed: Int = 0, val missing: Int = 0)

@Serializable
data class LinkBody(
    @SerialName("device_a_id") val deviceAId: String,
    @SerialName("device_b_id") val deviceBId: String,
    @SerialName("cable_type") val cableType: String,
    /** A chosen port on either end; null lets the server take a free one. */
    @SerialName("port_a_id") val portAId: String? = null,
    @SerialName("port_b_id") val portBId: String? = null,
)

/** A port record from the form, nulls included: a cleared access VLAN is cleared on the server too. */
fun PortDraft.toPatch(deviceId: String? = null): JsonObject = buildJsonObject {
    deviceId?.let { put("device_id", it) }
    put("number", number)
    put("name", name)
    put("is_uplink", isUplink)
    put("mode", mode.name)
    put("access_vlan", accessVlan?.let { JsonPrimitive(it) } ?: JsonNull)
    put("tagged_vlans", kotlinx.serialization.json.JsonArray(taggedVlans.map { JsonPrimitive(it) }))
    put("speed_mbps", speedMbps)
    put("poe_enabled", poeEnabled)
}

/** The cable colour as the server stores it; null clears it. */
fun cableColorPatch(colorArgb: Long?): JsonObject = buildJsonObject {
    put("color", colorArgb?.let { JsonPrimitive("#%06X".format(it and 0xFFFFFF)) } ?: JsonNull)
}
