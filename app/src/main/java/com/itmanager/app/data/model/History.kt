package com.itmanager.app.data.model

import java.time.Instant

enum class HistoryKind(val wire: String) {
    /** Applied to the switch. */
    CHANGE("change"),
    /** Refused by the server: writes to real equipment are off. */
    BLOCKED("blocked"),
    FAILED("failed"),
    CONFIG_BACKUP("config_backup");

    companion object {
        fun parse(raw: String?): HistoryKind = entries.firstOrNull { it.wire == raw } ?: CHANGE
    }
}

/** One line of a switch's change history: a port write, a config save or a stored config copy. */
data class DeviceHistoryEntry(
    val at: Instant?,
    val kind: HistoryKind,
    val title: String,
    /** "gi1/0/2: VLAN 10 → 20". */
    val summary: String,
    val actor: String,
    val commands: List<String>,
)
