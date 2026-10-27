package com.itmanager.app.data.model

/** One interface of a pasted config: what would change on the matching port of the inventory. */
data class ConfigImportPort(val port: String, val matched: Boolean, val changes: List<ConfigImportChange>)

data class ConfigImportChange(val field: String, val current: String, val imported: String)

/** Preview or outcome of reading a pasted running-config or `show vlan brief` into the inventory. */
data class ConfigImportResult(
    val kind: String,
    val hostname: String?,
    val applied: Boolean,
    val ports: List<ConfigImportPort>,
    val unmatchedInterfaces: List<String>,
    val unknownVlans: Map<Int, String>,
    val changedPorts: Int,
    /** VLANs from the text the inventory does not have: added on apply. */
    val newVlans: Map<Int, String> = emptyMap(),
    /** Interfaces with no port yet: added on apply. */
    val newPorts: List<String> = emptyList(),
) {
    /** Something to write: changed or new ports, or new VLANs. */
    val hasChanges: Boolean get() = changedPorts > 0 || newVlans.isNotEmpty()
}

/** How a port field is called on screen. */
fun importFieldLabel(field: String): String = when (field) {
    "port_name" -> "имя"
    "mode" -> "режим"
    "access_vlan" -> "VLAN"
    "tagged_vlans" -> "VLAN trunk"
    "is_admin_up" -> "включён"
    "speed_mbps" -> "скорость, Мбит/с"
    "is_uplink" -> "аплинк"
    else -> field
}

/** A value from the import as text: «да»/«нет» for flags, «—» for nothing. */
fun importValueLabel(value: String): String = when (value) {
    "true" -> "да"
    "false" -> "нет"
    else -> value
}
