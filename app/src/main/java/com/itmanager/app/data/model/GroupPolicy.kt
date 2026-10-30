package com.itmanager.app.data.model

import kotlinx.serialization.json.JsonElement

/** Group Policy: what GPMC shows and changes, through the server (WinRM on a domain controller). */
enum class GpoStatus(val wire: String, val title: String) {
    ENABLED("AllSettingsEnabled", "Включена"),
    USER_OFF("UserSettingsDisabled", "Без параметров пользователя"),
    COMPUTER_OFF("ComputerSettingsDisabled", "Без параметров компьютера"),
    DISABLED("AllSettingsDisabled", "Отключена");

    companion object {
        fun parse(raw: String?): GpoStatus = entries.firstOrNull { it.wire == raw } ?: ENABLED
    }
}

data class GpoLink(val target: String, val targetName: String, val enabled: Boolean, val enforced: Boolean)

data class Gpo(
    val id: String,
    val name: String,
    val status: GpoStatus,
    val description: String?,
    val modified: String?,
    val wmiFilter: String?,
    val links: List<GpoLink>,
) {
    /** Takes effect somewhere: not switched off and linked with at least one enabled link. */
    val active: Boolean get() = status != GpoStatus.DISABLED && links.any { it.enabled }
}

/** [canCreate]: false only from older servers that could not create GPOs straight over LDAP and SYSVOL. */
data class GpoOverview(val real: Boolean, val host: String, val writesEnabled: Boolean, val canCreate: Boolean, val gpos: List<Gpo>)

val GPO_TYPES = listOf("String", "ExpandString", "DWord", "QWord", "MultiString")

data class GpoRegistryValue(val key: String, val name: String, val type: String, val value: String?, val deleting: Boolean)

data class GpoDetail(val gpo: Gpo, val registry: List<GpoRegistryValue>, val securityFilter: List<String>, val simulated: Boolean)

data class GpoOu(val dn: String, val name: String)

data class GpoComputer(val name: String, val ou: String?, val enabled: Boolean)

data class GpoScope(val computers: List<GpoComputer>, val filtered: Boolean)

/** One GPO as a computer applied it (gpresult). */
data class GpoApplied(val name: String, val applied: Boolean, val reason: String?, val link: String?)

data class GpoAppliedOnPc(val host: String, val readAt: String?, val gpos: List<GpoApplied>, val simulated: Boolean)

data class GpoApplyRow(val host: String, val ok: Boolean, val error: String)

data class GpoApplyJob(val id: String, val gpoName: String, val running: Boolean, val total: Int, val done: Int, val simulated: Boolean, val rows: List<GpoApplyRow>)

/** The fields of one change; the path of the call says which change it is. */
data class GpoChange(
    val status: String? = null,
    val target: String? = null,
    val enabled: Boolean? = null,
    val enforced: Boolean? = null,
    val key: String? = null,
    val name: String? = null,
    val type: String? = null,
    val value: String? = null,
    val comment: String? = null,
    /** A template («Шаблоны»): its state and form values. */
    val state: String? = null,
    val values: Map<String, JsonElement>? = null,
)

/** A ready-made setting: an Administrative Templates policy as the registry values gpedit writes for it. */
enum class GpoTemplateState(val wire: String, val title: String) {
    NOT_CONFIGURED("not_configured", "Не задано"),
    ENABLED("enabled", "Включено"),
    DISABLED("disabled", "Отключено");

    companion object {
        fun parse(raw: String?): GpoTemplateState? = entries.firstOrNull { it.wire == raw }
    }
}

/** [kind]: bool, int, choice, list (one per line) or text. */
data class GpoTemplateField(
    val id: String,
    val label: String,
    val kind: String,
    val default: JsonElement?,
    val help: String,
    val choices: List<Pair<String, String>>,
    val min: Int?,
    val max: Int?,
)

/** [state] and [values]: what the GPO holds now. */
data class GpoTemplate(
    val id: String,
    val title: String,
    val path: String,
    val help: String,
    val forUsers: Boolean,
    val canDisable: Boolean,
    val fields: List<GpoTemplateField>,
    val state: GpoTemplateState,
    val values: Map<String, JsonElement>,
)
