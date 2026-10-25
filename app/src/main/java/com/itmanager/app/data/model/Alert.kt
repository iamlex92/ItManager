package com.itmanager.app.data.model

import java.time.Instant

enum class AlertSeverity(val label: String) {
    CRITICAL("Критично"),
    WARNING("Внимание"),
    INFO("Инфо");

    companion object {
        fun parse(raw: String?): AlertSeverity = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: INFO
    }
}

/** An incident from the server's monitoring: a host that stopped answering, a link down, low toner. */
data class Alert(
    val id: String,
    val deviceId: String,
    val portId: String?,
    val severity: AlertSeverity,
    /** HOST_DOWN, LINK_DOWN, TONER_LOW, CPU_OVERLOAD and whatever the server adds later. */
    val type: String,
    val message: String,
    val isResolved: Boolean,
    val createdAt: Instant?,
    val resolvedAt: Instant?,
) {
    val typeLabel: String
        get() = when (type) {
            "HOST_DOWN" -> "Узел недоступен"
            "LINK_DOWN" -> "Линк упал"
            "TONER_LOW" -> "Заканчивается тонер"
            "CPU_OVERLOAD" -> "Перегрузка CPU"
            else -> type
        }
}

/** Newest unresolved first, then resolved ones by time; the order of the alerts screen. */
fun List<Alert>.sortedForDisplay(): List<Alert> =
    sortedWith(compareBy<Alert>({ it.isResolved }, { it.severity.ordinal }).thenByDescending { it.createdAt })

/** Adds a new alert or replaces the stored copy, as the server sends the same alert again when it resolves. */
fun List<Alert>.upsert(alert: Alert): List<Alert> =
    if (any { it.id == alert.id }) map { if (it.id == alert.id) alert else it } else this + alert
