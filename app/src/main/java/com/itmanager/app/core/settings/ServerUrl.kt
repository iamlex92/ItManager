package com.itmanager.app.core.settings

import java.net.URI

/** Result of checking a server address typed by the user. */
sealed interface ServerUrlCheck {
    data class Valid(val normalized: String) : ServerUrlCheck
    data class Invalid(val reason: String) : ServerUrlCheck
}

object ServerUrl {
    const val DEFAULT_PORT = 8000

    /**
     * Accepts what people actually type ("10.0.0.5", "10.0.0.5:8000", "http://netadmin.local/")
     * and returns a canonical base URL without a trailing slash.
     * A bare host gets http:// and the server's default port 8000.
     */
    fun check(input: String): ServerUrlCheck {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ServerUrlCheck.Invalid("Укажите адрес сервера")

        val hasScheme = trimmed.contains("://")
        val withScheme = if (hasScheme) trimmed else "http://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: return ServerUrlCheck.Invalid("Адрес записан с ошибкой")

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return ServerUrlCheck.Invalid("Адрес должен начинаться с http:// или https://")
        }
        val host = uri.host
        if (host.isNullOrBlank()) return ServerUrlCheck.Invalid("Не указан хост или IP-адрес")
        if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) {
            return ServerUrlCheck.Invalid("Уберите из адреса параметры после ? или #")
        }

        val port = when {
            uri.port != -1 -> uri.port
            hasScheme -> -1
            else -> DEFAULT_PORT
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val portPart = if (port == -1) "" else ":$port"
        return ServerUrlCheck.Valid("$scheme://$hostPart$portPart$path")
    }
}
