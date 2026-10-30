package com.itmanager.app.core.network

import com.itmanager.app.data.model.Gpo
import com.itmanager.app.data.model.GpoApplied
import com.itmanager.app.data.model.GpoAppliedOnPc
import com.itmanager.app.data.model.GpoApplyJob
import com.itmanager.app.data.model.GpoApplyRow
import com.itmanager.app.data.model.GpoComputer
import com.itmanager.app.data.model.GpoDetail
import com.itmanager.app.data.model.GpoLink
import com.itmanager.app.data.model.GpoOu
import com.itmanager.app.data.model.GpoOverview
import com.itmanager.app.data.model.GpoRegistryValue
import com.itmanager.app.data.model.GpoScope
import com.itmanager.app.data.model.GpoStatus
import com.itmanager.app.data.model.GpoTemplate
import com.itmanager.app.data.model.GpoTemplateField
import com.itmanager.app.data.model.GpoTemplateState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// Wire format of /api/v1/gpo (server/API.md «Group Policy»).

@Serializable
data class GpoLinkDto(
    val target: String,
    @SerialName("target_name") val targetName: String? = null,
    val enabled: Boolean = true,
    val enforced: Boolean = false,
) {
    fun toDomain() = GpoLink(target, targetName ?: target, enabled, enforced)
}

@Serializable
data class GpoDto(
    val id: String,
    val name: String,
    val status: String,
    val description: String? = null,
    val modified: String? = null,
    @SerialName("wmi_filter") val wmiFilter: String? = null,
    val links: List<GpoLinkDto> = emptyList(),
) {
    fun toDomain() = Gpo(id, name, GpoStatus.parse(status), description?.takeIf { it.isNotBlank() }, modified, wmiFilter, links.map { it.toDomain() })
}

@Serializable
data class GpoListDto(
    val mode: String,
    val host: String = "",
    @SerialName("writes_enabled") val writesEnabled: Boolean = false,
    @SerialName("can_create") val canCreate: Boolean = true,
    val gpos: List<GpoDto> = emptyList(),
) {
    fun toDomain() = GpoOverview(mode == "real", host, writesEnabled, canCreate, gpos.map { it.toDomain() })
}

@Serializable
data class GpoRegistryDto(val key: String, val name: String, val type: String, val value: String? = null, val state: String? = null)

@Serializable
data class GpoTrusteeDto(val name: String? = null)

@Serializable
data class GpoDetailDto(
    val gpo: GpoDto,
    val registry: List<GpoRegistryDto> = emptyList(),
    @SerialName("security_filter") val securityFilter: List<GpoTrusteeDto> = emptyList(),
    val simulated: Boolean = false,
) {
    fun toDomain() = GpoDetail(
        gpo.toDomain(),
        registry.map { GpoRegistryValue(it.key, it.name, it.type, it.value, it.state == "Delete") },
        securityFilter.mapNotNull { it.name },
        simulated,
    )
}

@Serializable
data class GpoOuDto(val dn: String, val name: String) {
    fun toDomain() = GpoOu(dn, name)
}

@Serializable
data class GpoComputerDto(val name: String, val ou: String? = null, val enabled: Boolean? = null)

@Serializable
data class GpoScopeDto(val computers: List<GpoComputerDto> = emptyList(), val filtered: Boolean = false) {
    fun toDomain() = GpoScope(computers.map { GpoComputer(it.name, it.ou, it.enabled != false) }, filtered)
}

@Serializable
data class GpoAppliedDto(
    val host: String,
    @SerialName("read_at") val readAt: String? = null,
    val gpos: List<Map<String, JsonElement>> = emptyList(),
    val simulated: Boolean = false,
) {
    fun toDomain() = GpoAppliedOnPc(
        host,
        readAt,
        gpos.map { g ->
            fun text(k: String) = (g[k] as? JsonPrimitive)?.contentOrNull
            fun flag(k: String) = (g[k] as? JsonPrimitive)?.booleanOrNull == true
            val reason = when {
                flag("applied") -> null
                flag("denied") -> "нет доступа"
                flag("filtered") -> "отфильтрована"
                else -> "не применена"
            }
            GpoApplied(text("name").orEmpty(), flag("applied"), reason, text("link"))
        },
        simulated,
    )
}

@Serializable
data class GpoApplyRowDto(val host: String, val ok: Boolean, val error: String = "")

@Serializable
data class GpoApplyJobDto(
    val id: String,
    @SerialName("gpo_name") val gpoName: String = "",
    val status: String,
    val total: Int = 0,
    val done: Int = 0,
    val simulated: Boolean = false,
    val rows: List<GpoApplyRowDto> = emptyList(),
) {
    fun toDomain() = GpoApplyJob(id, gpoName, status == "running", total, done, simulated, rows.map { GpoApplyRow(it.host, it.ok, it.error) })
}

/** Every change carries the confirmation the phone took with the fingerprint. */
@Serializable
data class GpoChangeBody(
    val confirm: Boolean = true,
    @SerialName("auth_method") val authMethod: String? = null,
    val operator: String? = null,
    val status: String? = null,
    val target: String? = null,
    val enabled: Boolean? = null,
    val enforced: Boolean? = null,
    val key: String? = null,
    val name: String? = null,
    val type: String? = null,
    val value: String? = null,
    val comment: String? = null,
    val computers: List<String>? = null,
    val state: String? = null,
    val values: Map<String, JsonElement>? = null,
)

@Serializable
data class GpoTemplateChoiceDto(val value: String, val label: String)

@Serializable
data class GpoTemplateFieldDto(
    val id: String,
    val label: String,
    val kind: String,
    val default: JsonElement? = null,
    val help: String = "",
    val choices: List<GpoTemplateChoiceDto> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
)

@Serializable
data class GpoTemplateDto(
    val id: String,
    val title: String,
    val path: String = "",
    val help: String = "",
    val scope: String = "computer",
    @SerialName("can_disable") val canDisable: Boolean = true,
    val fields: List<GpoTemplateFieldDto> = emptyList(),
    val state: String? = null,
    val values: Map<String, JsonElement>? = null,
) {
    fun toDomain() = GpoTemplate(
        id, title, path, help, scope == "user", canDisable,
        fields.map { f -> GpoTemplateField(f.id, f.label, f.kind, f.default, f.help, f.choices.map { it.value to it.label }, f.min, f.max) },
        GpoTemplateState.parse(state) ?: GpoTemplateState.NOT_CONFIGURED,
        values.orEmpty(),
    )
}
