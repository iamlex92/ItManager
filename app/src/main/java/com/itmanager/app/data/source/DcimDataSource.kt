package com.itmanager.app.data.source

import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.AuditEntry
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.ConfigDiff
import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Credential
import com.itmanager.app.data.model.CredentialDraft
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.DeviceHistoryEntry
import com.itmanager.app.data.model.DiagnosticKind
import com.itmanager.app.data.model.DiagnosticResult
import com.itmanager.app.data.model.Drawing
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.FloorPlan
import com.itmanager.app.data.model.ImportReport
import com.itmanager.app.data.model.NetworkScan
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortChangeResult
import com.itmanager.app.data.model.PortLoad
import com.itmanager.app.data.model.PlanFile
import com.itmanager.app.data.model.PlanRect
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.Runbook
import com.itmanager.app.data.model.RunbookRun
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.TrafficSeries
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

    /** What changed on the switch: port writes (applied, blocked or failed), config saves and copies. */
    suspend fun deviceHistory(switchId: String): List<DeviceHistoryEntry>

    // Port utilization from the server's throughput samples. Read-only.

    /** Per-port average, peak and latest throughput over the last [hours]; ports without samples are left out. */
    suspend fun switchTraffic(switchId: String, hours: Int): List<PortLoad>
    suspend fun portTraffic(switchId: String, portId: String, hours: Int, points: Int): TrafficSeries

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

    // Stored logins. Secrets go one way: the app can set or replace one but never read it back.

    suspend fun credentials(): List<Credential>
    /** Creates a login when [id] is null; otherwise updates it, keeping the stored secret if the draft's is blank. */
    suspend fun saveCredential(id: String?, draft: CredentialDraft): Credential
    suspend fun deleteCredential(id: String)

    /** A floor plan file: SVG, PNG or JPEG. */
    suspend fun planBytes(url: String): ByteArray

    // Map editor. Inventory only: nothing here reaches a device.

    /** [plan] itself when it is stored; otherwise a new floor with its size, for a server that had none. */
    suspend fun ensureFloor(plan: FloorPlan): FloorPlan
    suspend fun createDevice(floorId: String, draft: DeviceDraft, x: Float, y: Float): Device
    suspend fun updateDevice(id: String, draft: DeviceDraft): Device
    /** Also puts a device that had no floor on [floorId]. */
    suspend fun moveDevice(id: String, floorId: String, x: Float, y: Float)
    suspend fun deleteDevice(id: String)
    /** A cable between two devices; the server picks or adds free ports on each. */
    suspend fun linkDevices(deviceAId: String, deviceBId: String, cableType: String): Connection
    suspend fun deleteConnection(id: String)
    suspend fun uploadPlan(plan: FloorPlan, file: PlanFile): FloorPlan
    suspend fun removePlan(plan: FloorPlan): FloorPlan
    suspend fun placePlan(plan: FloorPlan, rect: PlanRect): FloorPlan
    suspend fun saveDrawings(plan: FloorPlan, drawings: List<Drawing>): FloorPlan

    /** Live port and device status for one switch, or for every device with [com.itmanager.app.data.model.ALL_DEVICES]. */
    fun live(deviceId: String): Flow<LiveEvent>
}
