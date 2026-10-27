package com.itmanager.app.core.network

import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.AlertSeverity
import com.itmanager.app.data.model.Consumable
import com.itmanager.app.data.model.ConsumableKind
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FloorInfo
import com.itmanager.app.data.model.FloorPlan
import com.itmanager.app.data.model.PlanRect
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.ServerHealth
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.data.model.VlanDraft
import com.itmanager.app.data.model.Waypoint
import com.itmanager.app.data.model.parseColorHex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Wire format of the NetAdmin server (server/API.md). Unknown fields are ignored by the Json config.

@Serializable
data class HealthDto(
    val status: String,
    val version: String? = null,
    @SerialName("hal_mode") val halMode: String? = null,
    @SerialName("device_writes_enabled") val deviceWritesEnabled: Boolean? = null,
    @SerialName("secrets_encrypted") val secretsEncrypted: Boolean? = null,
    @SerialName("telegram_alerts") val telegramAlerts: Boolean? = null,
    @SerialName("runbook_mode") val runbookMode: String? = null,
    @SerialName("runbook_writes_enabled") val runbookWritesEnabled: Boolean? = null,
) {
    fun toDomain() = ServerHealth(
        status, version, halMode, deviceWritesEnabled, secretsEncrypted, telegramAlerts, runbookMode, runbookWritesEnabled,
    )
}

@Serializable
data class FloorDto(
    val id: String,
    @SerialName("building_name") val buildingName: String,
    @SerialName("floor_number") val floorNumber: Int,
    val name: String? = null,
    @SerialName("plan_image_url") val planImageUrl: String? = null,
    @SerialName("scale_pixels_per_meter") val scalePixelsPerMeter: Float? = null,
    @SerialName("width_px") val widthPx: Float? = null,
    @SerialName("height_px") val heightPx: Float? = null,
    @SerialName("plan_x") val planX: Float? = null,
    @SerialName("plan_y") val planY: Float? = null,
    @SerialName("plan_w") val planW: Float? = null,
    @SerialName("plan_h") val planH: Float? = null,
    val drawings: List<DrawingDto> = emptyList(),
    val zones: List<ZoneDto> = emptyList(),
) {
    fun info() = FloorInfo(id, buildingName, floorNumber, name)
}

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val type: String,
    val vendor: String? = null,
    val model: String? = null,
    val ip: String? = null,
    val mac: String? = null,
    @SerialName("floor_id") val floorId: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("vlan_id") val vlanId: Int? = null,
    @SerialName("host_id") val hostId: String? = null,
    val comment: String? = null,
    @SerialName("rack_id") val rackId: String? = null,
    @SerialName("rack_u") val rackU: Int? = null,
    @SerialName("rack_h") val rackH: Int? = null,
    @SerialName("rack_units") val rackUnits: Int? = null,
    @SerialName("switch_id") val switchId: String? = null,
    @SerialName("switch_port_id") val switchPortId: String? = null,
    @SerialName("switch_port_name") val switchPortName: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
) {
    fun toDomain() = Device(
        id = id,
        name = name,
        type = DeviceType.parse(type),
        vendor = vendor,
        model = model,
        ip = ip,
        mac = mac,
        floorId = floorId,
        x = x ?: 0f,
        y = y ?: 0f,
        isOnline = isOnline,
        vlanId = vlanId,
        hostId = hostId,
        comment = comment?.takeIf { it.isNotBlank() },
        rackId = rackId,
        rackU = rackU,
        rackH = rackH,
        rackUnits = rackUnits,
        switchId = switchId,
        switchPortId = switchPortId,
        switchPortName = switchPortName,
        metadata = metadata,
    )
}

@Serializable
data class PortDto(
    val id: String,
    val number: Int,
    val name: String,
    @SerialName("is_uplink") val isUplink: Boolean = false,
    val mode: String = "ACCESS",
    @SerialName("access_vlan") val accessVlan: Int? = null,
    @SerialName("tagged_vlans") val taggedVlans: List<Int> = emptyList(),
    @SerialName("is_admin_up") val isAdminUp: Boolean = true,
    @SerialName("is_oper_up") val isOperUp: Boolean = false,
    @SerialName("speed_mbps") val speedMbps: Int? = null,
    @SerialName("poe_enabled") val poeEnabled: Boolean = false,
    @SerialName("connected_device_name") val connectedDeviceName: String? = null,
) {
    fun toDomain() = SwitchPort(
        id = id,
        number = number,
        name = name,
        isUplink = isUplink,
        mode = if (mode.equals("TRUNK", ignoreCase = true)) PortMode.TRUNK else PortMode.ACCESS,
        accessVlan = accessVlan,
        taggedVlans = taggedVlans,
        isAdminUp = isAdminUp,
        isOperUp = isOperUp,
        speedMbps = speedMbps ?: 0,
        poeEnabled = poeEnabled,
        connectedDeviceName = connectedDeviceName,
    )
}

@Serializable
data class VlanDto(
    @SerialName("vlan_id") val vlanId: Int,
    val name: String,
    @SerialName("subnet_cidr") val subnetCidr: String = "",
    @SerialName("color_hex") val colorHex: String? = null,
    val description: String? = null,
) {
    fun toDomain() = Vlan(vlanId, name, subnetCidr, parseColorHex(colorHex), description?.takeIf { it.isNotBlank() })
}

/** The VLAN form, nulls included: a cleared subnet or description is cleared on the server too. */
fun VlanDraft.toBody(withId: Boolean): JsonObject =
    buildJsonObject {
        if (withId) put("vlan_id", JsonPrimitive(id))
        put("name", JsonPrimitive(name.trim()))
        put(
            "subnet_cidr",
            subnet.trim().takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull,
        )
        put("color_hex", JsonPrimitive("#%06X".format(colorArgb and 0xFFFFFF)))
        put(
            "description",
            description?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull,
        )
    }

@Serializable
data class ConnectionDto(
    val id: String,
    @SerialName("device_a_id") val deviceAId: String? = null,
    @SerialName("device_b_id") val deviceBId: String? = null,
    @SerialName("cable_type") val cableType: String = "Cat5e",
    val label: String? = null,
    val waypoints: List<WaypointDto> = emptyList(),
    val color: String? = null,
) {
    fun toDomain(): Connection? {
        val a = deviceAId ?: return null
        val b = deviceBId ?: return null
        return Connection(id, a, b, cableType, label, waypoints.map { it.toDomain() }, color?.let { parseColorHex(it) })
    }
}

@Serializable
data class WaypointDto(val x: Float, val y: Float, @SerialName("floor_id") val floorId: String? = null) {
    fun toDomain() = Waypoint(x, y, floorId)

    companion object {
        fun of(p: Waypoint) = WaypointDto(p.x, p.y, p.floorId)
    }
}

@Serializable
data class ConsumableDto(
    val name: String,
    @SerialName("color_hex") val colorHex: String? = null,
    @SerialName("level_percent") val levelPercent: Int = 0,
    val kind: String? = null,
) {
    fun toDomain() = Consumable(
        name = name,
        colorArgb = parseColorHex(colorHex, fallback = 0xFF8E8E93),
        levelPercent = levelPercent.coerceIn(0, 100),
        kind = ConsumableKind.parse(kind),
    )
}

@Serializable
data class PrinterDto(
    val id: String,
    val name: String,
    val ip: String? = null,
    val model: String? = null,
    @SerialName("is_online") val isOnline: Boolean = true,
    @SerialName("total_pages") val totalPages: Long = 0,
    @SerialName("polled_at") val polledAt: String? = null,
    val consumables: List<ConsumableDto> = emptyList(),
) {
    fun toDomain() = Printer(
        id = id,
        name = name,
        ip = ip,
        totalPages = totalPages,
        consumables = consumables.map { it.toDomain() },
        model = model,
        isOnline = isOnline,
        polledAt = parseInstant(polledAt),
    )
}

@Serializable
data class AlertDto(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("port_id") val portId: String? = null,
    val severity: String = "INFO",
    @SerialName("alert_type") val alertType: String = "",
    val message: String = "",
    @SerialName("is_resolved") val isResolved: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
) {
    fun toDomain() = Alert(
        id = id,
        deviceId = deviceId,
        portId = portId,
        severity = AlertSeverity.parse(severity),
        type = alertType,
        message = message,
        isResolved = isResolved,
        createdAt = parseInstant(createdAt),
        resolvedAt = parseInstant(resolvedAt),
    )
}

@Serializable
data class NfcBindBody(val uid: String, val force: Boolean = false)

/** Server times are ISO 8601, usually with an offset; a bare local time is taken as UTC. */
fun parseInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(raw).toInstant() }
        .recoverCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }
        .getOrNull()
}

fun FloorDto.toDomain(baseUrl: String, fallbackWidth: Float, fallbackHeight: Float) = FloorPlan(
    id = id,
    title = name?.takeIf { it.isNotBlank() } ?: "$buildingName · этаж $floorNumber",
    widthPx = widthPx ?: fallbackWidth,
    heightPx = heightPx ?: fallbackHeight,
    pixelsPerMeter = scalePixelsPerMeter ?: 50f,
    rooms = emptyList(),
    imageUrl = planImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
        // The server returns paths like /static/plans/floor-1.svg relative to itself.
        if (url.startsWith("http://") || url.startsWith("https://")) url else baseUrl + "/" + url.trimStart('/')
    },
    imageRect = if (planW != null && planH != null) PlanRect(planX ?: 0f, planY ?: 0f, planW, planH) else null,
    drawings = drawings.map { it.toDomain() },
    zones = zones.map { it.toDomain() },
)
