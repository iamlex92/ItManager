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
)
