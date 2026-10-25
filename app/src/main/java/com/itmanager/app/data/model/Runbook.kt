package com.itmanager.app.data.model

import java.time.Instant

enum class RunbookParamKind {
    TEXT, CHOICE;

    companion object {
        fun parse(raw: String?): RunbookParamKind = if (raw.equals("choice", ignoreCase = true)) CHOICE else TEXT
    }
}

data class RunbookChoice(val value: String, val label: String)

data class RunbookParam(
    val name: String,
    val label: String,
    val kind: RunbookParamKind = RunbookParamKind.TEXT,
    val required: Boolean = true,
    val choices: List<RunbookChoice> = emptyList(),
    val placeholder: String? = null,
)

/**
 * A routine operation from the catalog (AD, file shares, infrastructure).
 * [critical] runbooks change something and are confirmed with biometrics before they are sent.
 */
data class Runbook(
    val id: String,
    val category: String,
    val title: String,
    val description: String,
    val critical: Boolean,
    val params: List<RunbookParam>,
) {
    /** The first required field left empty, or null when the form can be sent. */
    fun firstMissing(values: Map<String, String>): RunbookParam? =
        params.firstOrNull { it.required && values[it.name].isNullOrBlank() }

    /** Trimmed values of known fields, without empty optional ones: what goes to the server. */
    fun arguments(values: Map<String, String>): Map<String, String> =
        params.mapNotNull { p -> values[p.name]?.trim()?.takeIf { it.isNotEmpty() }?.let { p.name to it } }.toMap()
}

/** How the operator confirmed a run on the phone; written to the audit log. */
enum class AuthMethod(val wire: String, val label: String) {
    BIOMETRIC("biometric", "биометрия"),
    DEVICE_CREDENTIAL("device_credential", "PIN-код или пароль телефона"),
    NOT_REQUIRED("not_required", "не требовалось");

    companion object {
        fun parse(raw: String?): AuthMethod? = entries.firstOrNull { it.wire == raw }
    }
}

data class RunbookRun(
    val auditId: String,
    val runbookId: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** A temporary password: shown once, never stored by the app or the server's audit log. */
    val secret: String?,
    val startedAt: Instant?,
    val durationMs: Long,
    val simulated: Boolean,
) {
    val succeeded: Boolean get() = exitCode == 0
}

enum class AuditKind(val wire: String) {
    RUNBOOK("runbook"), SWITCH("switch"), NETWORK("network"), SECURITY("security");

    companion object {
        fun parse(raw: String?): AuditKind = entries.firstOrNull { it.wire == raw } ?: SWITCH
    }
}

/** One row of the audit log: a runbook run, a switch write attempt or a network scan. */
data class AuditEntry(
    val id: String,
    /** Server action name: "runbook:<id>" for runbooks, e.g. "port_config" for switch writes. */
    val action: String,
    val kind: AuditKind,
    val title: String,
    val triggeredBy: String,
    val arguments: List<Pair<String, String>>,
    val authMethod: AuthMethod?,
    val executedAt: Instant?,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val durationMs: Long?,
) {
    val succeeded: Boolean get() = exitCode == 0
}
