package com.itmanager.app.core.vpn

import java.net.URI
import java.net.URLEncoder

/**
 * The factory's VPN through Cisco Secure Client (AnyConnect): the app asks it to connect with its URI handler
 * («Внешнее управление» must be on in the client's settings) and comes back through itmanager://vpn/….
 * The tunnel is AnyConnect's own; the app never sees the VPN password.
 */
data class VpnSettings(
    val enabled: Boolean = false,
    /** The connection's name in AnyConnect («Завод»); made there with [host] when it does not exist yet. */
    val name: String = "",
    /** The ASA: vpn.zavod.ru or vpn.zavod.ru/group. */
    val host: String = "",
    /** Filled into the login form; the password stays in AnyConnect. */
    val username: String = "",
) {
    val isConfigured: Boolean get() = name.isNotBlank() || host.isNotBlank()
}

enum class VpnResult { CONNECTED, FAILED, DISCONNECTED }

object AnyConnect {
    /** Cisco Secure Client on Google Play. */
    const val PACKAGE = "com.cisco.anyconnect.vpn.android.avf"
    const val CALLBACK_SCHEME = "itmanager"
    const val CALLBACK_HOST = "vpn"
    private const val CALLBACK = "$CALLBACK_SCHEME://$CALLBACK_HOST"

    fun connectUri(s: VpnSettings): String = uri(
        "connect",
        "name" to s.name.trim(),
        "host" to s.host.trim(),
        "prefill_username" to s.username.trim(),
        "onsuccess" to "$CALLBACK/connected",
        "onerror" to "$CALLBACK/error",
    )

    fun disconnectUri(): String = uri("disconnect", "onsuccess" to "$CALLBACK/disconnected")

    /** What AnyConnect's call back means; null for anything that is not one. */
    fun result(uri: String): VpnResult? {
        val u = runCatching { URI(uri) }.getOrNull() ?: return null
        if (u.scheme != CALLBACK_SCHEME || u.host != CALLBACK_HOST) return null
        return when (u.path.orEmpty().trim('/')) {
            "connected" -> VpnResult.CONNECTED
            "error" -> VpnResult.FAILED
            "disconnected" -> VpnResult.DISCONNECTED
            else -> null
        }
    }

    private fun uri(command: String, vararg params: Pair<String, String>): String =
        "anyconnect://$command/?" + params.filter { it.second.isNotEmpty() }
            .joinToString("&") { (k, v) -> "$k=${encode(v)}" }

    // AnyConnect reads %20, not the + of form encoding.
    private fun encode(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}

/** Host and port of the NetAdmin server address, to see whether it answers before asking for the VPN. */
fun serverEndpoint(baseUrl: String): Pair<String, Int>? {
    val u = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
    val host = u.host?.trim('[', ']') ?: return null
    val port = when {
        u.port != -1 -> u.port
        u.scheme == "https" -> 443
        else -> 80
    }
    return host to port
}
