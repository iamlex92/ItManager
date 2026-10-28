package com.itmanager.app.data.source

import com.itmanager.app.core.network.LiveSocket
import com.itmanager.app.core.network.NetAdminApi
import com.itmanager.app.core.network.runCatchingCancellable
import com.itmanager.app.core.settings.ServerSettings
import com.itmanager.app.core.settings.SettingsRepository
import com.itmanager.app.data.model.ALL_DEVICES
import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.Annotation
import com.itmanager.app.data.model.MapPoint
import com.itmanager.app.data.model.AuditEntry
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Credential
import com.itmanager.app.data.model.CredentialDraft
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceTemplateDraft
import com.itmanager.app.data.model.DeviceBulkChange
import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.DiagnosticKind
import com.itmanager.app.data.model.Drawing
import com.itmanager.app.data.model.Faceplate
import com.itmanager.app.data.model.FaceplatePort
import com.itmanager.app.data.model.Peripheral
import com.itmanager.app.data.model.PeripheralDraft
import com.itmanager.app.data.model.Topology
import com.itmanager.app.data.model.FloorDraft
import com.itmanager.app.data.model.FloorInfo
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.FloorPlan
import com.itmanager.app.data.model.ImportReport
import com.itmanager.app.data.model.NetworkScan
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.PlanFile
import com.itmanager.app.data.model.PlanRect
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortDraft
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.Runbook
import com.itmanager.app.data.model.RunbookRun
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.data.model.VlanDraft
import com.itmanager.app.data.model.Waypoint
import com.itmanager.app.data.model.Zone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the server source from the current settings on every call, so a new server address
 * or token takes effect on the next load without a restart.
 */
@Singleton
class DcimRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val api: NetAdminApi,
    private val liveSocket: LiveSocket,
) {
    /** Bumped when the inventory changes from the app (devices added from a scan or on the map). */
    private val inventoryVersion = MutableStateFlow(0)

    /** Screens reload when this emits: on new settings, and after the inventory changed. */
    val settings: Flow<ServerSettings> = combine(settingsRepository.settings, inventoryVersion) { s, _ -> s }

    /** Bumped when a peripheral is added, changed or moved from this phone. */
    private val peripheralVersion = MutableStateFlow(0)

    /** Peripheral lists reload when this emits: with [settings], and after a peripheral changed here. */
    val peripheralChanges: Flow<ServerSettings> = combine(settings, peripheralVersion) { s, _ -> s }

    suspend fun floorMap(floorId: String? = null): Result<FloorMap> = load { floorMap(floorId) }
    suspend fun devices(): Result<List<Device>> = load { devices() }
    suspend fun device(id: String): Result<Device> = load { device(id) }
    suspend fun ports(deviceId: String): Result<List<SwitchPort>> = load { ports(deviceId) }
    suspend fun printers(): Result<List<Printer>> = load { printers() }

    suspend fun vlans(): Result<List<Vlan>> = load { vlans() }
    suspend fun saveVlan(isNew: Boolean, draft: VlanDraft): Result<Vlan> = edit { saveVlan(isNew, draft) }
    suspend fun deleteVlan(id: Int): Result<Unit> = edit { deleteVlan(id) }
    suspend fun previewPortChange(switchId: String, portId: String, change: PortChange) =
        load { previewPortChange(switchId, portId, change) }
    suspend fun applyPortChange(switchId: String, portId: String, change: PortChange, confirmUplink: Boolean) =
        load { applyPortChange(switchId, portId, change, confirmUplink) }
    suspend fun bouncePort(switchId: String, portId: String, confirmUplink: Boolean) =
        load { bouncePort(switchId, portId, confirmUplink) }
    suspend fun writeMemory(switchId: String) = load { writeMemory(switchId) }
    suspend fun backupConfig(switchId: String) = load { backupConfig(switchId) }
    suspend fun configDiff(switchId: String) = load { configDiff(switchId) }
    suspend fun ipam() = load { ipam() }
    suspend fun ipamCheck(ip: String) = load { ipamCheck(ip) }
    suspend fun deviceTemplates() = load { deviceTemplates() }
    suspend fun saveDeviceTemplate(draft: DeviceTemplateDraft) = load { saveDeviceTemplate(draft) }
    suspend fun deleteDeviceTemplate(id: String) = load { deleteDeviceTemplate(id) }
    suspend fun createDeviceFromTemplate(templateId: String, name: String, ip: String?, at: MapPoint? = null): Result<Device> =
        edit { createDeviceFromTemplate(templateId, name, ip, at) }
    suspend fun importConfig(switchId: String, text: String, apply: Boolean) =
        load { importConfig(switchId, text, apply) }

    suspend fun diagnostic(switchId: String, kind: DiagnosticKind, target: String) =
        load { diagnostic(switchId, kind, target) }

    suspend fun deviceForTag(payloadId: String?, uid: String) = load { deviceForTag(payloadId, uid) }
    suspend fun bindNfc(deviceId: String, uid: String, force: Boolean) = load { bindNfc(deviceId, uid, force) }
    suspend fun alerts(): Result<List<Alert>> = load { alerts() }
    suspend fun resolveAlert(alertId: String) = load { resolveAlert(alertId) }
    suspend fun refreshPrinter(printerId: String) = load { refreshPrinter(printerId) }

    suspend fun runbooks(): Result<List<Runbook>> = load { runbooks() }
    suspend fun runRunbook(runbook: Runbook, arguments: Map<String, String>, authMethod: AuthMethod): Result<RunbookRun> =
        load { runRunbook(runbook, arguments, authMethod) }
    suspend fun audit(kind: AuditKind?): Result<List<AuditEntry>> = load { audit(kind) }

    suspend fun startScan(subnets: List<String>, community: String): Result<NetworkScan> =
        load { startScan(subnets, community) }
    suspend fun scan(id: String): Result<NetworkScan> = load { scan(id) }
    suspend fun latestScan(): Result<NetworkScan?> = load { latestScan() }
    suspend fun importScan(id: String, ips: List<String>, withLinks: Boolean): Result<ImportReport> =
        load { importScan(id, ips, withLinks) }.onSuccess { inventoryVersion.value++ }

    suspend fun deviceHistory(switchId: String) = load { deviceHistory(switchId) }
    suspend fun switchTraffic(switchId: String, hours: Int) = load { switchTraffic(switchId, hours) }
    suspend fun portTraffic(switchId: String, portId: String, hours: Int, points: Int) =
        load { portTraffic(switchId, portId, hours, points) }

    suspend fun credentials(): Result<List<Credential>> = load { credentials() }
    suspend fun saveCredential(id: String?, draft: CredentialDraft): Result<Credential> = load { saveCredential(id, draft) }
    suspend fun deleteCredential(id: String): Result<Unit> = load { deleteCredential(id) }

    suspend fun planBytes(url: String): Result<ByteArray> = load { planBytes(url) }

    // Map editor. Device and cable changes reload every screen; plan changes come back as the new plan.

    suspend fun ensureFloor(plan: FloorPlan): Result<FloorPlan> = load { ensureFloor(plan) }
    suspend fun createDevice(floorId: String, draft: DeviceDraft, x: Float, y: Float): Result<Device> =
        edit { createDevice(floorId, draft, x, y) }
    suspend fun updateDevice(id: String, draft: DeviceDraft): Result<Device> = edit { updateDevice(id, draft) }
    suspend fun moveDevice(id: String, floorId: String, x: Float, y: Float): Result<Unit> =
        edit { moveDevice(id, floorId, x, y) }
    suspend fun deleteDevice(id: String): Result<Unit> = edit { deleteDevice(id) }
    suspend fun bulkUpdateDevices(ids: List<String>, change: DeviceBulkChange): Result<Int> =
        edit { bulkUpdateDevices(ids, change) }
    suspend fun bulkDeleteDevices(ids: List<String>): Result<Int> = edit { bulkDeleteDevices(ids) }
    suspend fun linkDevices(a: String, b: String, cableType: String): Result<Connection> =
        edit { linkDevices(a, b, cableType) }
    suspend fun deleteConnection(id: String): Result<Unit> = edit { deleteConnection(id) }
    suspend fun recolorConnection(id: String, colorArgb: Long?): Result<Connection> = edit { recolorConnection(id, colorArgb) }
    suspend fun createPort(deviceId: String, draft: PortDraft): Result<SwitchPort> = edit { createPort(deviceId, draft) }
    suspend fun updatePortRecord(portId: String, draft: PortDraft): Result<SwitchPort> = edit { updatePortRecord(portId, draft) }
    suspend fun deletePort(portId: String): Result<Unit> = edit { deletePort(portId) }
    suspend fun routeConnection(id: String, waypoints: List<Waypoint>): Result<Connection> =
        edit { routeConnection(id, waypoints) }
    suspend fun createFloor(draft: FloorDraft): Result<FloorInfo> = edit { createFloor(draft) }
    suspend fun renameFloor(id: String, draft: FloorDraft): Result<FloorInfo> = edit { renameFloor(id, draft) }
    suspend fun deleteFloor(id: String): Result<Unit> = edit { deleteFloor(id) }
    suspend fun uploadPlan(plan: FloorPlan, file: PlanFile): Result<FloorPlan> = load { uploadPlan(plan, file) }
    suspend fun removePlan(plan: FloorPlan): Result<FloorPlan> = load { removePlan(plan) }
    suspend fun placePlan(plan: FloorPlan, rect: PlanRect): Result<FloorPlan> = load { placePlan(plan, rect) }
    suspend fun saveDrawings(plan: FloorPlan, drawings: List<Drawing>): Result<FloorPlan> =
        load { saveDrawings(plan, drawings) }
    suspend fun saveZones(plan: FloorPlan, zones: List<Zone>): Result<FloorPlan> = load { saveZones(plan, zones) }
    suspend fun saveAnnotations(plan: FloorPlan, annotations: List<Annotation>): Result<FloorPlan> =
        load { saveAnnotations(plan, annotations) }

    // Peripherals change only their own list; the map and the device list stay as they are.
    suspend fun peripherals(): Result<List<Peripheral>> = load { peripherals() }
    suspend fun savePeripheral(id: String?, draft: PeripheralDraft): Result<Peripheral> =
        load { savePeripheral(id, draft) }.onSuccess { peripheralVersion.value++ }
    suspend fun attachPeripheral(id: String, deviceId: String?): Result<Peripheral> =
        load { attachPeripheral(id, deviceId) }.onSuccess { peripheralVersion.value++ }
    suspend fun deletePeripheral(id: String): Result<Unit> = load { deletePeripheral(id) }.onSuccess { peripheralVersion.value++ }

    suspend fun topology(): Result<Topology> = load { topology() }

    suspend fun faceplate(deviceId: String): Result<Faceplate?> = load { faceplate(deviceId) }
    suspend fun faceplateSvg(deviceId: String): Result<ByteArray> = load { faceplateSvg(deviceId) }
    suspend fun uploadFaceplate(deviceId: String, svg: ByteArray): Result<Faceplate> = load { uploadFaceplate(deviceId, svg) }
    suspend fun saveFaceplatePorts(deviceId: String, marks: Collection<FaceplatePort>): Result<Faceplate> =
        load { saveFaceplatePorts(deviceId, marks) }
    suspend fun copyFaceplate(deviceId: String, fromDeviceId: String): Result<Faceplate> =
        load { copyFaceplate(deviceId, fromDeviceId) }
    suspend fun deleteFaceplate(deviceId: String): Result<Unit> = load { deleteFaceplate(deviceId) }

    /** The server says the inventory changed elsewhere (the web console, another phone). */
    fun inventoryChanged() {
        inventoryVersion.value++
    }

    private suspend fun <T> edit(block: suspend DcimDataSource.() -> T): Result<T> =
        load(block).onSuccess { inventoryVersion.value++ }

    /** Restarts the stream whenever settings change, so a new server address takes effect at once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun live(deviceId: String): Flow<LiveEvent> =
        if (deviceId == ALL_DEVICES) allDevices else liveFor(deviceId)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun liveFor(deviceId: String): Flow<LiveEvent> =
        settingsRepository.settings.flatMapLatest { source(it).live(deviceId) }

    /**
     * The map, the printers and the alert journal all follow every device; they share one socket,
     * which closes a few seconds after the last of them stops listening.
     */
    private val allDevices: Flow<LiveEvent> = liveFor(ALL_DEVICES)
        .shareIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.WhileSubscribed(5_000))

    private fun source(settings: ServerSettings): DcimDataSource =
        RemoteDataSource(api, liveSocket, settings)

    private suspend fun <T> load(block: suspend DcimDataSource.() -> T): Result<T> {
        val source = source(settingsRepository.current())
        return runCatchingCancellable { source.block() }
    }
}
