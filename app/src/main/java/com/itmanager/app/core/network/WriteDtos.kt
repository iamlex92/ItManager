package com.itmanager.app.core.network

import com.itmanager.app.data.model.ConfigDiff
import com.itmanager.app.data.model.ConfigImportChange
import com.itmanager.app.data.model.ConfigImportPort
import com.itmanager.app.data.model.ConfigImportResult
import com.itmanager.app.data.model.DiagnosticResult
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortChangeResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// Bodies of the guarded write endpoints (server/API.md, "Talking to switches").

@Serializable
data class PortConfigBody(
    val mode: String? = null,
    @SerialName("access_vlan") val accessVlan: Int? = null,
    @SerialName("tagged_vlans") val taggedVlans: List<Int>? = null,
    @SerialName("is_admin_up") val isAdminUp: Boolean? = null,
    val confirm: Boolean? = null,
    @SerialName("confirm_uplink") val confirmUplink: Boolean? = null,
)

fun PortChange.toBody(confirm: Boolean? = null, confirmUplink: Boolean? = null) = PortConfigBody(
    mode = mode?.name,
    accessVlan = accessVlan,
    taggedVlans = taggedVlans,
    isAdminUp = isAdminUp,
    confirm = confirm,
    confirmUplink = confirmUplink,
)

@Serializable
data class ConfirmBody(
    val confirm: Boolean,
    @SerialName("confirm_uplink") val confirmUplink: Boolean? = null,
)

@Serializable
data class CommandsDto(val commands: List<String> = emptyList())

@Serializable
data class PortConfigResultDto(val port: PortDto, val commands: List<String> = emptyList()) {
    fun toDomain() = PortChangeResult(port.toDomain(), commands)
}

@Serializable
data class ConfigDiffDto(val changed: Boolean, val diff: String = "") {
    fun toDomain() = ConfigDiff(changed, diff)
}

@Serializable
data class ConfigImportBody(val text: String, val apply: Boolean)

@Serializable
data class ConfigImportPortDto(
    val port: String,
    val matched: Boolean = false,
    val changes: Map<String, List<JsonElement>> = emptyMap(),
)

@Serializable
data class ConfigImportDto(
    val kind: String = "",
    val hostname: String? = null,
    val applied: Boolean = false,
    val ports: List<ConfigImportPortDto> = emptyList(),
    @SerialName("unmatched_interfaces") val unmatchedInterfaces: List<String> = emptyList(),
    @SerialName("unknown_vlans") val unknownVlans: Map<Int, String> = emptyMap(),
    @SerialName("changed_ports") val changedPorts: Int = 0,
    @SerialName("new_vlans") val newVlans: Map<Int, String> = emptyMap(),
    @SerialName("new_ports") val newPorts: List<String> = emptyList(),
) {
    fun toDomain() = ConfigImportResult(
        kind, hostname, applied,
        ports.map { p ->
            ConfigImportPort(
                p.port, p.matched,
                p.changes.map { (field, v) -> ConfigImportChange(field, v.getOrNull(0).shown(), v.getOrNull(1).shown()) },
            )
        },
        unmatchedInterfaces, unknownVlans, changedPorts, newVlans, newPorts,
    )

    private fun JsonElement?.shown(): String = when (this) {
        null, JsonNull -> "—"
        is JsonPrimitive -> content
        else -> toString()
    }
}

@Serializable
data class DiagnosticBody(val target: String, val count: Int? = null)

@Serializable
data class DiagnosticDto(val command: String = "", val output: String = "", val success: Boolean = false) {
    fun toDomain() = DiagnosticResult(command, output, success)
}

@Serializable
internal data class ErrorDto(val detail: ErrorDetail? = null)

@Serializable
internal data class ErrorDetail(
    val code: String? = null,
    val message: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
)

/** Russian text for the server's guarded-write error codes. */
internal fun writeErrorMessage(code: String?, status: Int): String = when (code) {
    "device_writes_disabled" ->
        "Сервер работает с реальным оборудованием и изменения на нём выключены (ALLOW_DEVICE_WRITES). Ничего не изменено."
    "uplink_port_protected" -> "Это аплинк. Подтвердите изменение аплинка явно."
    "confirmation_required" -> "Сервер ждёт подтверждения изменения."
    "unknown_vlan" -> "Такого VLAN нет в базе сервера."
    "empty_change" -> "Изменений нет: порт уже настроен так."
    "not_a_managed_switch" -> "Это устройство не управляется: нужен коммутатор Cisco или Eltex."
    "not_implemented" -> "Для реального оборудования эта операция на сервере ещё не написана."
    "scan_running" -> "Уже идёт сканирование, дождитесь его окончания."
    "unrecognised_text" -> "Не похоже на running-config или вывод show vlan brief (Cisco, Eltex)."
    "scan_not_done" -> "Сканирование ещё не закончилось."
    else -> when (status) {
        404 -> "На сервере нет сохранённой копии конфигурации."
        422 -> "Сервер не принял запрос (код 422): проверьте введённые данные."
        else -> "Сервер отклонил изменение (код $status)."
    }
}
