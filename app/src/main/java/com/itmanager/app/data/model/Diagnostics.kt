package com.itmanager.app.data.model

enum class DiagnosticKind(val title: String) {
    PING("Ping"),
    TRACEROUTE("Traceroute"),
}

/** Result of a ping or traceroute run on the switch itself, as the switch printed it. */
data class DiagnosticResult(
    val command: String,
    val output: String,
    val success: Boolean,
)

private val ipv4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

/** The server only takes an IPv4 address as the target, so the app checks before sending. */
fun isIpv4(target: String): Boolean = ipv4.matches(target.trim())
