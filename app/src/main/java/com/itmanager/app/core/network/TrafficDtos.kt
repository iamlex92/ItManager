package com.itmanager.app.core.network

import com.itmanager.app.data.model.PortLoad
import com.itmanager.app.data.model.TrafficPoint
import com.itmanager.app.data.model.TrafficSeries
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Port utilization (server/API.md, "Traffic").

@Serializable
data class PortTrafficSummaryDto(
    @SerialName("port_id") val portId: String,
    @SerialName("port_name") val portName: String,
    @SerialName("avg_in_bps") val avgInBps: Long = 0,
    @SerialName("avg_out_bps") val avgOutBps: Long = 0,
    @SerialName("peak_in_bps") val peakInBps: Long = 0,
    @SerialName("peak_out_bps") val peakOutBps: Long = 0,
    @SerialName("peak_utilization") val peakUtilization: Float = 0f,
    @SerialName("last_in_bps") val lastInBps: Long? = null,
    @SerialName("last_out_bps") val lastOutBps: Long? = null,
) {
    fun toDomain() = PortLoad(portId, portName, peakUtilization, avgInBps, avgOutBps, peakInBps, peakOutBps, lastInBps, lastOutBps)
}

@Serializable
data class DeviceTrafficDto(val ports: List<PortTrafficSummaryDto> = emptyList())

@Serializable
data class TrafficPointDto(val at: String, @SerialName("in_bps") val inBps: Long, @SerialName("out_bps") val outBps: Long)

@Serializable
data class PortTrafficDto(
    @SerialName("port_id") val portId: String,
    @SerialName("speed_mbps") val speedMbps: Int = 0,
    val hours: Int = 24,
    @SerialName("peak_utilization") val peakUtilization: Float = 0f,
    val points: List<TrafficPointDto> = emptyList(),
) {
    fun toDomain() = TrafficSeries(
        portId = portId, speedMbps = speedMbps, hours = hours, peakUtilization = peakUtilization,
        points = points.mapNotNull { p -> parseInstant(p.at)?.let { TrafficPoint(it, p.inBps, p.outBps) } },
    )
}
