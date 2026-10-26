package com.itmanager.app.core.settings

import java.net.URLDecoder

/**
 * Contents of the connection QR code that `netadmin-agent info` draws:
 * netadmin://connect?u=<base url>&u=<another>&t=<API token>.
 * [urls] are the server's addresses, best first; the app keeps the first one that answers.
 */
data class ConnectQr(val urls: List<String>, val token: String) {
    companion object {
        private const val PREFIX = "netadmin://connect?"
        private const val MAX_URLS = 4
        private const val MAX_TOKEN = 256

        /** Null for anything that is not a NetAdmin connection code, so a random QR changes nothing. */
        fun parse(text: String): ConnectQr? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
            val urls = mutableListOf<String>()
            var token: String? = null
            for (part in trimmed.substring(PREFIX.length).split('&')) {
                val key = part.substringBefore('=')
                val value = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull() ?: return null
                when (key) {
                    "u" -> (ServerUrl.check(value) as? ServerUrlCheck.Valid)?.let { if (it.normalized !in urls) urls += it.normalized }
                    "t" -> token = value
                }
            }
            val t = token?.trim().orEmpty()
            if (urls.isEmpty() || t.isEmpty() || t.length > MAX_TOKEN || t.any { it.isWhitespace() }) return null
            return ConnectQr(urls.take(MAX_URLS), t)
        }
    }
}
