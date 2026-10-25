package com.itmanager.app.data.model

/** A requested change to one switch port. Null fields stay as they are. */
data class PortChange(
    val mode: PortMode? = null,
    val accessVlan: Int? = null,
    val taggedVlans: List<Int>? = null,
    val isAdminUp: Boolean? = null,
) {
    val isEmpty: Boolean get() = mode == null && accessVlan == null && taggedVlans == null && isAdminUp == null

    companion object {
        /** Only the fields that differ from [port], so the switch gets the shortest possible command list. */
        fun between(
            port: SwitchPort,
            mode: PortMode,
            accessVlan: Int?,
            taggedVlans: List<Int>,
            isAdminUp: Boolean,
        ): PortChange {
            val modeChanged = mode != port.mode
            return PortChange(
                mode = mode.takeIf { modeChanged },
                accessVlan = accessVlan.takeIf { mode == PortMode.ACCESS && (modeChanged || it != port.accessVlan) },
                taggedVlans = taggedVlans.sorted()
                    .takeIf { mode == PortMode.TRUNK && (modeChanged || it != port.taggedVlans.sorted()) },
                isAdminUp = isAdminUp.takeIf { it != port.isAdminUp },
            )
        }
    }
}

data class PortChangeResult(val port: SwitchPort, val commands: List<String>)

data class ConfigDiff(
    val changed: Boolean,
    /** Unified diff of the stored copy against the live running-config. */
    val diff: String,
)
