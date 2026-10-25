package com.itmanager.app.core.network

import com.itmanager.app.data.model.AuditEntry
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.Runbook
import com.itmanager.app.data.model.RunbookChoice
import com.itmanager.app.data.model.RunbookParam
import com.itmanager.app.data.model.RunbookParamKind
import com.itmanager.app.data.model.RunbookRun
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Runbooks and the audit log (server/API.md, "Runbooks and audit").

@Serializable
data class RunbookChoiceDto(val value: String, val label: String = value)

@Serializable
data class RunbookParamDto(
    val name: String,
    val label: String = name,
    val kind: String = "text",
    val required: Boolean = true,
    val choices: List<RunbookChoiceDto> = emptyList(),
    val placeholder: String? = null,
)

@Serializable
data class RunbookDto(
    val id: String,
    val category: String = "",
    val title: String = id,
    val description: String = "",
    val critical: Boolean = true,
    val params: List<RunbookParamDto> = emptyList(),
) {
    fun toDomain() = Runbook(
        id = id,
        category = category,
        title = title,
        description = description,
        critical = critical,
        params = params.map { p ->
            RunbookParam(
                name = p.name,
                label = p.label,
                kind = RunbookParamKind.parse(p.kind),
                required = p.required,
                choices = p.choices.map { RunbookChoice(it.value, it.label) },
                placeholder = p.placeholder,
            )
        },
    )
}

@Serializable
data class RunbookRunBody(
    val params: Map<String, String>,
    val confirm: Boolean,
    @SerialName("auth_method") val authMethod: String? = null,
    val operator: String? = null,
)

@Serializable
data class RunbookRunDto(
    @SerialName("audit_id") val auditId: String,
    @SerialName("runbook_id") val runbookId: String,
    @SerialName("exit_code") val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val secret: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val simulated: Boolean = false,
) {
    fun toDomain() = RunbookRun(
        auditId = auditId,
        runbookId = runbookId,
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        secret = secret,
        startedAt = parseInstant(startedAt),
        durationMs = durationMs,
        simulated = simulated,
    )
}

@Serializable
data class AuditDto(
    val id: String,
    val action: String = "",
    val kind: String = "switch",
    val title: String = action,
    @SerialName("triggered_by") val triggeredBy: String = "",
    val parameters: JsonObject? = null,
    @SerialName("executed_at") val executedAt: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
) {
    fun toDomain(): AuditEntry {
        // Runbook rows keep their arguments under "params" next to bookkeeping fields;
        // switch write rows store the arguments flat.
        val nested = parameters?.get("params") as? JsonObject
        val arguments = (nested ?: parameters).orEmpty().map { (key, value) -> key to value.display() }
        return AuditEntry(
            id = id,
            action = action,
            kind = AuditKind.parse(kind),
            title = title,
            triggeredBy = triggeredBy,
            arguments = arguments,
            authMethod = AuthMethod.parse((parameters?.get("auth_method") as? JsonPrimitive)?.contentOrNull),
            executedAt = parseInstant(executedAt),
            exitCode = exitCode,
            stdout = stdout.orEmpty(),
            stderr = stderr.orEmpty(),
            durationMs = durationMs,
        )
    }
}

private fun JsonElement.display(): String = when (this) {
    is JsonPrimitive -> contentOrNull ?: "—"
    is JsonArray -> joinToString { it.display() }
    is JsonObject -> entries.joinToString { (k, v) -> "$k=${v.display()}" }
}
