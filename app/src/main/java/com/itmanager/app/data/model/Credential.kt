package com.itmanager.app.data.model

import java.time.Instant

enum class CredentialKind(val wire: String, val label: String, val secretLabel: String) {
    SSH("ssh", "SSH", "Пароль или ключ"),
    SNMP_V3("snmp_v3", "SNMPv3", "Ключ auth/priv"),
    WINRM("winrm", "WinRM", "Пароль"),
    OTHER("other", "Другое", "Секрет");

    companion object {
        fun parse(raw: String?): CredentialKind = entries.firstOrNull { it.wire == raw } ?: OTHER
    }
}

/**
 * A stored login. The secret itself never comes back from the server: it is encrypted there
 * and the app only learns whether one is set.
 */
data class Credential(
    val id: String,
    val name: String,
    val kind: CredentialKind,
    val username: String?,
    val hasSecret: Boolean,
    val notes: String?,
    /** Devices that use this login. */
    val devices: Int,
    val updatedAt: Instant?,
)

/**
 * What the operator typed. On an edit a blank [secret] keeps the stored one, so the form never has to
 * show or reload it.
 */
data class CredentialDraft(
    val name: String,
    val kind: CredentialKind,
    val username: String,
    val secret: String,
    val notes: String,
) {
    /** Null when the draft can be saved; otherwise what to fix. */
    fun problem(isNew: Boolean): String? = when {
        name.isBlank() -> "Укажите название"
        name.trim().length > 100 -> "Название длиннее 100 символов"
        username.trim().length > 100 -> "Логин длиннее 100 символов"
        secret.length > 4096 -> "Секрет длиннее 4096 символов"
        isNew && secret.isEmpty() -> "Укажите ${kind.secretLabel.lowercase()}"
        else -> null
    }
}
