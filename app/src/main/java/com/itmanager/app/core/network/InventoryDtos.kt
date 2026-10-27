package com.itmanager.app.core.network

import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.Faceplate
import com.itmanager.app.data.model.FaceplatePort
import com.itmanager.app.data.model.Peripheral
import com.itmanager.app.data.model.PeripheralDraft
import com.itmanager.app.data.model.PeripheralKind
import com.itmanager.app.data.model.Topology
import com.itmanager.app.data.model.TopologyLink
import com.itmanager.app.data.model.TopologyLinkKind
import com.itmanager.app.data.model.TopologyNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Peripherals, topology and faceplates (server/API.md).

@Serializable
data class PeripheralDto(
    val id: String,
    val kind: String,
    val vendor: String? = null,
    val model: String? = null,
    val specs: String? = null,
    @SerialName("serial_number") val serialNumber: String? = null,
    @SerialName("inventory_number") val inventoryNumber: String? = null,
    val comment: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
) {
    fun toDomain() = Peripheral(
        id = id,
        kind = PeripheralKind.parse(kind),
        vendor = vendor,
        model = model,
        specs = specs,
        serialNumber = serialNumber,
        inventoryNumber = inventoryNumber,
        comment = comment?.takeIf { it.isNotBlank() },
        deviceId = deviceId,
        deviceName = deviceName,
    )
}

/** The whole form, nulls included: a field the user cleared is cleared on the server too. */
fun PeripheralDraft.toBody(): JsonObject = trimmed().let { d ->
    buildJsonObject {
        put("kind", d.kind.name)
        put("vendor", d.vendor.json())
        put("model", d.model.json())
        put("specs", d.specs.json())
        put("serial_number", d.serialNumber.json())
        put("inventory_number", d.inventoryNumber.json())
        put("comment", d.comment.json())
        put("device_id", d.deviceId.json())
    }
}

private fun String?.json() = this?.let { JsonPrimitive(it) } ?: JsonNull

/** Moves a peripheral to [deviceId], or back to stock with null. */
fun attachBody(deviceId: String?): JsonObject = buildJsonObject { put("device_id", deviceId.json()) }

@Serializable
data class TopologyNodeDto(
    val id: String,
    val name: String,
    val type: String,
    val vendor: String? = null,
    val model: String? = null,
    val ip: String? = null,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("floor_id") val floorId: String? = null,
    @SerialName("host_id") val hostId: String? = null,
) {
    fun toDomain() = TopologyNode(id, name, DeviceType.parse(type), vendor, model, ip, isOnline, floorId, hostId)
}

@Serializable
data class TopologyLinkDto(
    @SerialName("a_id") val aId: String,
    @SerialName("b_id") val bId: String,
    val kind: String = "CABLE",
    @SerialName("a_port") val aPort: String? = null,
    @SerialName("b_port") val bPort: String? = null,
    val via: List<String> = emptyList(),
    @SerialName("cable_types") val cableTypes: List<String> = emptyList(),
    @SerialName("is_up") val isUp: Boolean? = null,
    @SerialName("speed_mbps") val speedMbps: Int? = null,
) {
    fun toDomain() = TopologyLink(
        aId, bId,
        if (kind.equals("VIRTUAL", ignoreCase = true)) TopologyLinkKind.VIRTUAL else TopologyLinkKind.CABLE,
        aPort, bPort, via, cableTypes, isUp, speedMbps,
    )
}

@Serializable
data class TopologyDto(val nodes: List<TopologyNodeDto> = emptyList(), val links: List<TopologyLinkDto> = emptyList()) {
    fun toDomain() = Topology(nodes.map { it.toDomain() }, links.map { it.toDomain() })
}

@Serializable
data class FaceplatePortDto(
    @SerialName("port_id") val portId: String,
    @SerialName("port_name") val portName: String = "",
    @SerialName("port_number") val portNumber: Int = 0,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    fun toDomain() = FaceplatePort(portId, portName, portNumber, x, y, w, h)
}

@Serializable
data class FaceplateDto(
    @SerialName("device_id") val deviceId: String,
    val width: Float,
    val height: Float,
    val ports: List<FaceplatePortDto> = emptyList(),
) {
    fun toDomain() = Faceplate(deviceId, width, height, ports.map { it.toDomain() })
}

@Serializable
data class FaceplateMarkBody(
    @SerialName("port_id") val portId: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

@Serializable
data class FaceplatePortsBody(val ports: List<FaceplateMarkBody>) {
    companion object {
        fun of(marks: Collection<FaceplatePort>) = FaceplatePortsBody(marks.map { FaceplateMarkBody(it.portId, it.x, it.y, it.w, it.h) })
    }
}

@Serializable
data class FaceplateCopyBody(@SerialName("from_device_id") val fromDeviceId: String)
