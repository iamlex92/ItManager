package com.itmanager.app.data.model

import kotlinx.serialization.json.JsonElement

/**
 * «Настройки сервера»: what used to be edited in netadmin.env. The server describes every setting itself,
 * so the screen only draws them; saved values act after the server restarts.
 */
data class ServerConfig(val groups: List<ServerConfigGroup>, val restartPending: Boolean, val canRestart: Boolean)

data class ServerConfigGroup(val title: String, val note: String, val fields: List<ServerConfigField>)

data class ServerConfigChoice(val value: String, val label: String)

data class ServerConfigField(
    val key: String,
    val label: String,
    val help: String,
    /** bool, int, float, text, choice, secret, credential. */
    val kind: String,
    val choices: List<ServerConfigChoice>,
    /** For kind = credential: ssh or winrm. */
    val credentialKind: String?,
    /** Null for secrets: they never come back, only [isSet]. */
    val value: JsonElement?,
    val isSet: Boolean,
    /** ui: saved here; file: netadmin.env; default: built-in. */
    val source: String,
    val restartRequired: Boolean,
) {
    val sourceText: String
        get() = when (source) {
            "ui" -> "задано здесь"
            "file" -> "из netadmin.env"
            else -> "по умолчанию"
        }
}
