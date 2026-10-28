package com.itmanager.app.core.network

import com.itmanager.app.data.model.ServerConfig
import com.itmanager.app.data.model.ServerConfigChoice
import com.itmanager.app.data.model.ServerConfigField
import com.itmanager.app.data.model.ServerConfigGroup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire format of /api/v1/server-settings.

@Serializable
data class ServerConfigChoiceDto(val value: String, val label: String)

@Serializable
data class ServerConfigFieldDto(
    val key: String,
    val label: String,
    val help: String = "",
    val kind: String,
    val choices: List<ServerConfigChoiceDto> = emptyList(),
    @SerialName("credential_kind") val credentialKind: String? = null,
    val value: JsonElement? = null,
    @SerialName("is_set") val isSet: Boolean? = null,
    val source: String = "default",
    @SerialName("restart_required") val restartRequired: Boolean = false,
) {
    fun toDomain() = ServerConfigField(
        key, label, help, kind, choices.map { ServerConfigChoice(it.value, it.label) }, credentialKind, value,
        isSet == true, source, restartRequired,
    )
}

@Serializable
data class ServerConfigGroupDto(val title: String, val note: String = "", val fields: List<ServerConfigFieldDto>)

@Serializable
data class ServerConfigDto(
    val groups: List<ServerConfigGroupDto>,
    @SerialName("restart_pending") val restartPending: Boolean = false,
    @SerialName("can_restart") val canRestart: Boolean = false,
) {
    fun toDomain() = ServerConfig(
        groups.map { g -> ServerConfigGroup(g.title, g.note, g.fields.map { it.toDomain() }) },
        restartPending,
        canRestart,
    )
}

@Serializable
data class ServerConfigBody(
    val values: Map<String, JsonElement> = emptyMap(),
    val reset: List<String> = emptyList(),
    val operator: String? = null,
)

@Serializable
data class RestartDto(val restarting: Boolean = false)

@Serializable
class EmptyBody
