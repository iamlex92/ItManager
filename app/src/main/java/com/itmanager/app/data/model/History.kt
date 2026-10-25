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

/** The same wording the server uses, for demo mode. */
fun describePortChange(port: SwitchPort, change: PortChange): String {
    fun vlans(v: List<Int>) = if (v.isEmpty()) "—" else v.sorted().joinToString(",")
    val parts = buildList {
        change.mode?.takeIf { it != port.mode }?.let { add("режим ${port.mode.name.lowercase()} → ${it.name.lowercase()}") }
        change.accessVlan?.takeIf { it != port.accessVlan }?.let { add("VLAN ${port.accessVlan ?: "—"} → $it") }
        change.taggedVlans?.takeIf { it.sorted() != port.taggedVlans.sorted() }
            ?.let { add("trunk VLAN ${vlans(port.taggedVlans)} → ${vlans(it)}") }
        change.isAdminUp?.takeIf { it != port.isAdminUp }?.let { add(if (it) "включён" else "выключен (shutdown)") }
    }
    return "${port.name}: ${parts.joinToString().ifEmpty { "без изменений" }}"
}
