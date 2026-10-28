package com.itmanager.app.core.network

import com.itmanager.app.data.model.DeviceTemplate
import com.itmanager.app.data.model.DeviceType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceTemplateDto(
    val id: String,
    val name: String,
    @SerialName("device_type") val deviceType: String = "",
    val vendor: String? = null,
    val model: String? = null,
    @SerialName("port_count") val portCount: Int = 0,
    @SerialName("port_prefix") val portPrefix: String = "Port",
    @SerialName("port_speed_mbps") val portSpeedMbps: Int = 1000,
    @SerialName("rack_h") val rackH: Int? = null,
    val comment: String? = null,
) {
    fun toDomain() = DeviceTemplate(
        id, name, DeviceType.parse(deviceType), vendor, model, portCount, portPrefix, portSpeedMbps, rackH, comment,
    )
}

@Serializable
data class DeviceTemplateBody(
    val name: String,
    @SerialName("device_type") val deviceType: String,
    val vendor: String?,
    val model: String?,
    @SerialName("port_count") val portCount: Int,
    @SerialName("port_prefix") val portPrefix: String,
    @SerialName("rack_h") val rackH: Int?,
)

@Serializable
data class DeviceFromTemplateBody(
    val name: String,
    val ip: String? = null,
    @SerialName("floor_id") val floorId: String? = null,
    val x: Float? = null,
    val y: Float? = null,
)
