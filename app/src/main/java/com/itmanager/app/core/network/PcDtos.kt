package com.itmanager.app.core.network

import com.itmanager.app.data.model.OneCLicence
import com.itmanager.app.data.model.PcAction
import com.itmanager.app.data.model.PcDisk
import com.itmanager.app.data.model.PcFolder
import com.itmanager.app.data.model.PcInfo
import com.itmanager.app.data.model.PcKeyJob
import com.itmanager.app.data.model.PcKeyRow
import com.itmanager.app.data.model.PcOutcome
import com.itmanager.app.data.model.PcProcess
import com.itmanager.app.data.model.PcResult
import com.itmanager.app.data.model.PcStatus
import com.itmanager.app.data.model.PcSweepHit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

// Wire format of /api/v1/pcs (server/API.md, «Domain computers»).

private val pcJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

@Serializable
data class PcStatusDto(
    val mode: String,
    @SerialName("writes_enabled") val writesEnabled: Boolean = false,
    @SerialName("login_configured") val loginConfigured: Boolean = false,
    @SerialName("ad_configured") val adConfigured: Boolean = false,
) {
    fun toDomain() = PcStatus(mode, writesEnabled, loginConfigured, adConfigured)
}

@Serializable
data class PcSweepBody(val prefix: String)

@Serializable
data class PcSweepHitDto(
    val ip: String,
    val name: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
) {
    fun toDomain() = PcSweepHit(ip, name, deviceId, deviceName)
}

@Serializable
data class PcActionBody(
    val arg: String? = null,
    val confirm: Boolean = false,
    @SerialName("auth_method") val authMethod: String? = null,
    val operator: String? = null,
)

@Serializable
data class OneCDto(val path: String? = null, val name: String? = null, val date: String? = null)

@Serializable
data class PcInfoDto(
    val computer: String? = null,
    val os: String? = null,
    val architecture: String? = null,
    val build: String? = null,
    @SerialName("uptime_minutes") val uptimeMinutes: Long? = null,
    val cpu: String? = null,
    @SerialName("ram_gb") val ramGb: Double? = null,
    val user: String? = null,
    @SerialName("windows_bios_key") val windowsBiosKey: String? = null,
    @SerialName("windows_registry_key") val windowsRegistryKey: String? = null,
    @SerialName("office_installed") val officeInstalled: Boolean = false,
    @SerialName("office_keys") val officeKeys: List<String> = emptyList(),
    @SerialName("one_c") val oneC: List<OneCDto> = emptyList(),
) {
    fun toDomain() = PcInfo(
        computer, os, architecture, build, uptimeMinutes, cpu, ramGb, user?.takeIf { it.isNotBlank() },
        windowsBiosKey?.takeIf { it.isNotBlank() }, windowsRegistryKey?.takeIf { it.isNotBlank() },
        officeInstalled, officeKeys, oneC.map { OneCLicence(it.path ?: it.name.orEmpty(), it.date) },
    )
}

@Serializable
data class PcDiskDto(
    val drive: String = "",
    val label: String? = null,
    @SerialName("free_gb") val freeGb: Double = 0.0,
    @SerialName("total_gb") val totalGb: Double = 0.0,
)

@Serializable
data class PcFolderDto(val name: String = "", @SerialName("size_gb") val sizeGb: Double = 0.0)

@Serializable
data class PcProcessDto(val id: Int = 0, val name: String = "", @SerialName("ram_mb") val ramMb: Double = 0.0)

@Serializable
data class PcActionDto(
    val host: String,
    val action: String,
    val ok: Boolean,
    val data: JsonElement? = null,
    val error: String = "",
    val simulated: Boolean = false,
) {
    fun toDomain(kind: PcAction): PcResult {
        val outcome = data?.takeIf { ok }?.let { d ->
            when (kind) {
                PcAction.INFO -> PcOutcome.Info(pcJson.decodeFromJsonElement<PcInfoDto>(d).toDomain())
                PcAction.DISKS -> PcOutcome.Disks(
                    pcJson.decodeFromJsonElement<List<PcDiskDto>>(d).map { PcDisk(it.drive, it.label, it.freeGb, it.totalGb) },
                )
                PcAction.FOLDERS -> PcOutcome.Folders(
                    pcJson.decodeFromJsonElement<List<PcFolderDto>>(d).map { PcFolder(it.name, it.sizeGb) },
                )
                PcAction.PROCESSES -> PcOutcome.Processes(
                    pcJson.decodeFromJsonElement<List<PcProcessDto>>(d).map { PcProcess(it.id, it.name, it.ramMb) },
                )
                PcAction.CLEANUP -> {
                    val c = pcJson.decodeFromJsonElement<CleanupDto>(d)
                    PcOutcome.Cleaned(c.freedMb, c.freeGb)
                }
                PcAction.KILL -> {
                    val k = pcJson.decodeFromJsonElement<KillDto>(d)
                    PcOutcome.Stopped(k.name, k.stopped)
                }
                else -> PcOutcome.Text((d as? JsonObject)?.let { pcJson.decodeFromJsonElement<TextDto>(it).output }.orEmpty())
            }
        }
        return PcResult(host, kind, ok, error, simulated, outcome)
    }
}

@Serializable
private data class CleanupDto(@SerialName("freed_mb") val freedMb: Double = 0.0, @SerialName("free_gb") val freeGb: Double = 0.0)

@Serializable
private data class KillDto(val stopped: Int = 0, val name: String = "")

@Serializable
private data class TextDto(val output: String = "")

@Serializable
data class PcKeysBody(val source: String, val prefix: String? = null, val operator: String? = null)

@Serializable
data class PcKeyRowDto(
    val host: String,
    val ok: Boolean,
    val error: String = "",
    val computer: String? = null,
    val user: String? = null,
    val os: String? = null,
    @SerialName("windows_bios_key") val windowsBiosKey: String? = null,
    @SerialName("windows_registry_key") val windowsRegistryKey: String? = null,
    val office: String = "",
    @SerialName("one_c") val oneC: String = "",
)

@Serializable
data class PcKeyJobDto(
    val id: String,
    val source: String,
    val status: String,
    val stage: String = "",
    val total: Int = 0,
    val online: Int = 0,
    val done: Int = 0,
    val error: String = "",
    val simulated: Boolean = false,
    val rows: List<PcKeyRowDto> = emptyList(),
) {
    fun toDomain() = PcKeyJob(
        id, source, status, stage, total, online, done, error, simulated,
        rows.map {
            PcKeyRow(it.host, it.ok, it.error, it.computer, it.user, it.os, it.windowsBiosKey, it.windowsRegistryKey, it.office, it.oneC)
        },
    )
}
