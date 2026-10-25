package com.itmanager.app.data.source

import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.AuditEntry
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.ConfigDiff
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DiagnosticKind
import com.itmanager.app.data.model.DiagnosticResult
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.ImportReport
import com.itmanager.app.data.model.NetworkScan
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortChangeResult
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.Runbook
import com.itmanager.app.data.model.RunbookRun
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

    suspend fun runbooks(): List<Runbook>

    /**
     * Runs a runbook with [arguments]. A critical one is sent only after the operator confirmed it
     * on the phone ([authMethod]); a failed run comes back with a non-zero exit code, not an exception.
     */
    suspend fun runRunbook(runbook: Runbook, arguments: Map<String, String>, authMethod: AuthMethod): RunbookRun

    /** Runbook runs and switch write attempts, newest first; [kind] null means both. */
    suspend fun audit(kind: AuditKind?): List<AuditEntry>

    // Network discovery: read-only towards equipment, started only by the user with explicit ranges.

    /** Starts a scan of [subnets] (already checked with [com.itmanager.app.data.model.ScanRanges]). */
    suspend fun startScan(subnets: List<String>, community: String): NetworkScan
    suspend fun scan(id: String): NetworkScan
    /** The last scan, to show again after the screen was closed; null when there was none. */
    suspend fun latestScan(): NetworkScan?

    /** Adds the devices with [ips] and, with [withLinks], the links found to them to the inventory. */
    suspend fun importScan(id: String, ips: List<String>, withLinks: Boolean): ImportReport

    /** Raw SVG of a floor plan. */
    suspend fun planSvg(url: String): String

    /** Live port and device status for one switch, or for every device with [com.itmanager.app.data.model.ALL_DEVICES]. */
    fun live(deviceId: String): Flow<LiveEvent>
}
