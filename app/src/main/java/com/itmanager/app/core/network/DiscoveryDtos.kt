package com.itmanager.app.core.network

import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FoundDevice
import com.itmanager.app.data.model.FoundLink
import com.itmanager.app.data.model.ImportReport
import com.itmanager.app.data.model.LinkEnd
import com.itmanager.app.data.model.LinkSource
import com.itmanager.app.data.model.NetworkScan
import com.itmanager.app.data.model.ScanStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Network discovery (server/API.md, "Network discovery").

@Serializable
data class ScanBody(val subnets: List<String>, val community: String, val operator: String? = null)

@Serializable
data class ImportBody(val ips: List<String>, val links: Boolean, val operator: String? = null)

@Serializable
data class FoundDeviceDto(
    val ip: String,
    val name: String? = null,
    val mac: String? = null,
    val vendor: String? = null,
    val model: String? = null,
    val type: String = "UNKNOWN",
    val description: String? = null,
    val snmp: Boolean = false,
    @SerialName("inventory_id") val inventoryId: String? = null,
    @SerialName("inventory_name") val inventoryName: String? = null,
) {
    fun toDomain() = FoundDevice(
        ip = ip, name = name, mac = mac, vendor = vendor, model = model, type = DeviceType.parse(type),
        description = description, snmp = snmp, inventoryId = inventoryId, inventoryName = inventoryName,
    )
}

@Serializable
data class LinkEndDto(
    val name: String = "",
    val port: String = "",
    val ip: String? = null,
    @SerialName("inventory_id") val inventoryId: String? = null,
) {
    fun toDomain() = LinkEnd(name.ifBlank { ip ?: "?" }, port, ip, inventoryId)
}

@Serializable
data class FoundLinkDto(val a: LinkEndDto, val b: LinkEndDto, val source: String = "fdb", val known: Boolean = false) {
    fun toDomain() = FoundLink(a.toDomain(), b.toDomain(), LinkSource.parse(source), known)
}

@Serializable
data class ScanDto(
    val id: String,
    val status: String = "running",
    val mode: String = "emulator",
    val subnets: List<String> = emptyList(),
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val total: Int = 0,
    val probed: Int = 0,
    val devices: List<FoundDeviceDto> = emptyList(),
    val links: List<FoundLinkDto> = emptyList(),
    val error: String? = null,
) {
    fun toDomain() = NetworkScan(
        id = id,
        status = ScanStatus.parse(status),
        emulated = mode != "real",
        subnets = subnets,
        startedAt = parseInstant(startedAt),
        finishedAt = parseInstant(finishedAt),
        total = total,
        probed = probed,
        devices = devices.map { it.toDomain() },
        links = links.map { it.toDomain() },
        error = error,
    )
}

@Serializable
data class ImportedDeviceDto(val id: String, val name: String, val ip: String = "")

@Serializable
data class ImportDto(
    val created: List<ImportedDeviceDto> = emptyList(),
    val links: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
) {
    fun toDomain() = ImportReport(created.map { it.name }, links, skipped)
}
