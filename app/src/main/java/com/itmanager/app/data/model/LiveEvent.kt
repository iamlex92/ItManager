package com.itmanager.app.data.model

/** Live updates pushed by the server over the WebSocket. */
sealed interface LiveEvent {
    /** The socket is up; the following events are live. */
    data object Connected : LiveEvent

    /** The socket dropped; the client is waiting to reconnect. */
    data class Disconnected(val reason: String) : LiveEvent

    /** Full port list right after subscribing to a switch. */
    data class Snapshot(val deviceId: String, val isOnline: Boolean?, val ports: List<SwitchPort>) : LiveEvent

    data class PortChanged(val deviceId: String, val port: SwitchPort) : LiveEvent

    data class DeviceStatus(val deviceId: String, val isOnline: Boolean) : LiveEvent

    /** A new alert, or an update to one (resolved alerts arrive again with isResolved = true). */
    data class AlertRaised(val alert: Alert) : LiveEvent

    /** Fresh SNMP reading of a printer. */
    data class PrinterChanged(val printer: Printer) : LiveEvent

    /** Devices, cables or the floor plan changed on the server (in the web console or on another phone). */
    data object InventoryChanged : LiveEvent
}

/** Subscription key for every device at once. */
const val ALL_DEVICES = "*"
