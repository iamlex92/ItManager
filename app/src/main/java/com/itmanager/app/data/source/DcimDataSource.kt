package com.itmanager.app.data.source

import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.ConfigDiff
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DiagnosticKind
import com.itmanager.app.data.model.DiagnosticResult
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortChangeResult
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import kotlinx.coroutines.flow.Flow

/** One place the app reads DCIM data from: built-in demo data or the NetAdmin server. */
interface DcimDataSource {
    suspend fun floorMap(): FloorMap
    suspend fun devices(): List<Device>
    suspend fun device(id: String): Device
    suspend fun ports(deviceId: String): List<SwitchPort>
    suspend fun printers(): List<Printer>

    suspend fun vlans(): List<Vlan>

    // Switch writes. The caller shows the commands from preview and gets explicit confirmation first.

    suspend fun previewPortChange(switchId: String, portId: String, change: PortChange): List<String>
    suspend fun applyPortChange(switchId: String, portId: String, change: PortChange, confirmUplink: Boolean): PortChangeResult
    suspend fun bouncePort(switchId: String, portId: String, confirmUplink: Boolean): List<String>
    suspend fun writeMemory(switchId: String): List<String>
    suspend fun backupConfig(switchId: String)
    suspend fun configDiff(switchId: String): ConfigDiff

    /** Ping or traceroute from the switch to [target] (an IPv4 address). Read-only. */
    suspend fun diagnostic(switchId: String, kind: DiagnosticKind, target: String): DiagnosticResult

    /** The device a scanned tag points to: the id written on the tag wins, else the tag's UID binding. */
    suspend fun deviceForTag(payloadId: String?, uid: String): Device

    /** Binds an NFC tag UID to a device in the inventory. [force] moves a tag bound to another device. */
    suspend fun bindNfc(deviceId: String, uid: String, force: Boolean): Device

    suspend fun alerts(): List<Alert>
    suspend fun resolveAlert(alertId: String): Alert
    suspend fun refreshPrinter(printerId: String): Printer

    /** Raw SVG of a floor plan. */
    suspend fun planSvg(url: String): String

    /** Live port and device status for one switch, or for every device with [com.itmanager.app.data.model.ALL_DEVICES]. */
    fun live(deviceId: String): Flow<LiveEvent>
}
