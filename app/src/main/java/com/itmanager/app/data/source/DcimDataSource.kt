package com.itmanager.app.data.source

import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.Annotation
import com.itmanager.app.data.model.PcSweepHit
import com.itmanager.app.data.model.PcStatus
import com.itmanager.app.data.model.PcResult
import com.itmanager.app.data.model.PcKeyJob
import com.itmanager.app.data.model.PcAction
import com.itmanager.app.data.model.ServerConfig
import com.itmanager.app.data.model.GpoOverview
import com.itmanager.app.data.model.GpoOu
import com.itmanager.app.data.model.GpoDetail
import com.itmanager.app.data.model.GpoScope
import com.itmanager.app.data.model.GpoAppliedOnPc
import com.itmanager.app.data.model.GpoChange
import com.itmanager.app.data.model.GpoApplyJob
import kotlinx.serialization.json.JsonElement
import com.itmanager.app.data.model.MapPoint
import com.itmanager.app.data.model.AuditEntry
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.ConfigDiff
import com.itmanager.app.data.model.ConfigImportResult
import com.itmanager.app.data.model.DeviceTemplate
import com.itmanager.app.data.model.DeviceTemplateDraft
import com.itmanager.app.data.model.IpamCheck
import com.itmanager.app.data.model.IpamOverview
import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Credential
import com.itmanager.app.data.model.CredentialDraft
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceBulkChange
import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.DeviceHistoryEntry
import com.itmanager.app.data.model.DiagnosticKind
import com.itmanager.app.data.model.DiagnosticResult
import com.itmanager.app.data.model.Drawing
import com.itmanager.app.data.model.Faceplate
import com.itmanager.app.data.model.FaceplatePort
import com.itmanager.app.data.model.Peripheral
import com.itmanager.app.data.model.PeripheralDraft
import com.itmanager.app.data.model.Topology
import com.itmanager.app.data.model.PortDraft
import com.itmanager.app.data.model.Zone
import com.itmanager.app.data.model.FloorDraft
import com.itmanager.app.data.model.FloorInfo
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
import com.itmanager.app.data.model.VlanDraft
import com.itmanager.app.data.model.Waypoint
import kotlinx.coroutines.flow.Flow

/** One place the app reads DCIM data from: the NetAdmin server. */
interface DcimDataSource {
    /** The map of floor [floorId]; null or an unknown id: the lowest floor. */
    suspend fun floorMap(floorId: String? = null): FloorMap
    suspend fun devices(): List<Device>
    suspend fun device(id: String): Device
    suspend fun ports(deviceId: String): List<SwitchPort>
    suspend fun printers(): List<Printer>

    suspend fun vlans(): List<Vlan>
    /** Adds a VLAN when [isNew], otherwise changes the one with the draft's number. */
    suspend fun saveVlan(isNew: Boolean, draft: VlanDraft): Vlan
    /** Refused while ports are in the VLAN. */
    suspend fun deleteVlan(id: Int)

    // Ports as the inventory records them. Never sent to the switch.

    suspend fun createPort(deviceId: String, draft: PortDraft): SwitchPort
    suspend fun updatePortRecord(portId: String, draft: PortDraft): SwitchPort
    suspend fun deletePort(portId: String)

    // Switch writes. The caller shows the commands from preview and gets explicit confirmation first.

    suspend fun previewPortChange(switchId: String, portId: String, change: PortChange): List<String>
    suspend fun applyPortChange(switchId: String, portId: String, change: PortChange, confirmUplink: Boolean): PortChangeResult
    suspend fun bouncePort(switchId: String, portId: String, confirmUplink: Boolean): List<String>
    suspend fun writeMemory(switchId: String): List<String>
    /** Takes a copy of the running-config; returns its text. */
    suspend fun backupConfig(switchId: String): String
    suspend fun configDiff(switchId: String): ConfigDiff

    /** Reads pasted config text into the inventory (apply=false only previews). Never writes to the switch. */
    /** Subnet usage and IP conflicts; reads the inventory only. */
    suspend fun ipam(): IpamOverview

    suspend fun ipamCheck(ip: String): IpamCheck

    suspend fun deviceTemplates(): List<DeviceTemplate>

    suspend fun saveDeviceTemplate(draft: DeviceTemplateDraft): DeviceTemplate

    suspend fun deleteDeviceTemplate(id: String)

    /** Adds a device to the inventory with the template's type, vendor, model and ports; [at] puts it on a floor. */
    suspend fun createDeviceFromTemplate(templateId: String, name: String, ip: String?, at: MapPoint? = null): Device

    suspend fun importConfig(switchId: String, text: String, apply: Boolean): ConfigImportResult

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
    /** One change on many devices; returns how many were changed. */
    suspend fun bulkUpdateDevices(ids: List<String>, change: DeviceBulkChange): Int
    suspend fun bulkDeleteDevices(ids: List<String>): Int
    /** A cable between two devices; the server picks or adds free ports on each. */
    suspend fun linkDevices(
        deviceAId: String,
        deviceBId: String,
        cableType: String,
        portAId: String? = null,
        portBId: String? = null,
    ): Connection
    suspend fun deleteConnection(id: String)
    /** The cable's colour on the map; null gives the automatic colour back. */
    suspend fun recolorConnection(id: String, colorArgb: Long?): Connection
    /** The cable's bends on the map, all floors at once. */
    suspend fun routeConnection(id: String, waypoints: List<Waypoint>): Connection

    // Floors of the building.

    suspend fun createFloor(draft: FloorDraft): FloorInfo
    suspend fun renameFloor(id: String, draft: FloorDraft): FloorInfo
    /** Refused while devices stand on the floor: they would lose their place. */
    suspend fun deleteFloor(id: String)
    suspend fun uploadPlan(plan: FloorPlan, file: PlanFile): FloorPlan
    suspend fun removePlan(plan: FloorPlan): FloorPlan
    suspend fun placePlan(plan: FloorPlan, rect: PlanRect): FloorPlan
    suspend fun saveDrawings(plan: FloorPlan, drawings: List<Drawing>): FloorPlan
    suspend fun saveZones(plan: FloorPlan, zones: List<Zone>): FloorPlan
    suspend fun saveAnnotations(plan: FloorPlan, annotations: List<Annotation>): FloorPlan

    // Peripherals: equipment without a network port attached to devices. Inventory only.

    suspend fun peripherals(): List<Peripheral>
    /** Creates a peripheral when [id] is null; otherwise replaces its fields with the draft's. */
    suspend fun savePeripheral(id: String?, draft: PeripheralDraft): Peripheral
    /** Attaches the peripheral to [deviceId], or puts it back in stock with null. */
    suspend fun attachPeripheral(id: String, deviceId: String?): Peripheral
    suspend fun deletePeripheral(id: String)

    /** Active devices and the cables between them, traced through sockets and patch panels. */
    suspend fun topology(): Topology

    // Front panel drawings in the rack view.

    /** The device's panel drawing with its port marks, or null when it has none. */
    suspend fun faceplate(deviceId: String): Faceplate?
    suspend fun faceplateSvg(deviceId: String): ByteArray
    suspend fun uploadFaceplate(deviceId: String, svg: ByteArray): Faceplate
    suspend fun saveFaceplatePorts(deviceId: String, marks: Collection<FaceplatePort>): Faceplate
    /** Takes the drawing and marks of [fromDeviceId]; ports are matched by name, then number. */
    suspend fun copyFaceplate(deviceId: String, fromDeviceId: String): Faceplate
    suspend fun deleteFaceplate(deviceId: String)

    /** Live port and device status for one switch, or for every device with [com.itmanager.app.data.model.ALL_DEVICES]. */
    fun live(deviceId: String): Flow<LiveEvent>

    // Domain computers: the admin script's tools, run by the server over WinRM.
    suspend fun pcStatus(): PcStatus
    suspend fun pcSweep(prefix: String): List<PcSweepHit>
    suspend fun pcAction(host: String, action: PcAction, arg: String?, authMethod: AuthMethod): PcResult
    suspend fun pcKeysStart(source: String, prefix: String?): PcKeyJob
    suspend fun pcKeys(id: String): PcKeyJob
    suspend fun pcKeysCsv(id: String): ByteArray

    // --- server settings ---
    suspend fun serverConfig(): ServerConfig
    suspend fun saveServerConfig(values: Map<String, JsonElement>, reset: List<String>): ServerConfig
    suspend fun restartServer()

    // --- Group Policy ---
    suspend fun gpos(): GpoOverview
    suspend fun gpoOus(): List<GpoOu>
    suspend fun gpo(id: String): GpoDetail
    suspend fun gpoComputers(id: String): GpoScope
    suspend fun gpoApplied(host: String): GpoAppliedOnPc
    suspend fun gpoChange(method: String, path: String, change: GpoChange, authMethod: AuthMethod): GpoDetail
    suspend fun gpoDelete(id: String, authMethod: AuthMethod)
    suspend fun gpoApply(id: String, computers: List<String>?, authMethod: AuthMethod): GpoApplyJob
    suspend fun gpoApplyJob(id: String): GpoApplyJob
}
