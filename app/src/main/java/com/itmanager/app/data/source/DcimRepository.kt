package com.itmanager.app.data.source

import com.itmanager.app.core.network.LiveSocket
import com.itmanager.app.core.network.NetAdminApi
import com.itmanager.app.core.network.runCatchingCancellable
import com.itmanager.app.core.settings.ServerSettings
import com.itmanager.app.core.settings.SettingsRepository
import com.itmanager.app.data.model.ALL_DEVICES
import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.AuditEntry
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.Credential
import com.itmanager.app.data.model.CredentialDraft
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DiagnosticKind
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.ImportReport
import com.itmanager.app.data.model.NetworkScan
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.Runbook
import com.itmanager.app.data.model.RunbookRun
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
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
 * Picks the data source from the current settings on every call, so switching demo mode
 * or the server address in settings takes effect on the next load without a restart.
 */
@Singleton
class DcimRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val api: NetAdminApi,
    private val liveSocket: LiveSocket,
) {
    /** Bumped when the inventory changes from the app (devices added from a scan). */
    private val inventoryVersion = MutableStateFlow(0)

    /** Screens reload when this emits: on new settings, and after the inventory changed. */
    val settings: Flow<ServerSettings> = combine(settingsRepository.settings, inventoryVersion) { s, _ -> s }

    suspend fun floorMap(): Result<FloorMap> = load { floorMap() }
    suspend fun devices(): Result<List<Device>> = load { devices() }
    suspend fun device(id: String): Result<Device> = load { device(id) }
    suspend fun ports(deviceId: String): Result<List<SwitchPort>> = load { ports(deviceId) }
    suspend fun printers(): Result<List<Printer>> = load { printers() }

    suspend fun vlans(): Result<List<Vlan>> = load { vlans() }
    suspend fun previewPortChange(switchId: String, portId: String, change: PortChange) =
        load { previewPortChange(switchId, portId, change) }
    suspend fun applyPortChange(switchId: String, portId: String, change: PortChange, confirmUplink: Boolean) =
        load { applyPortChange(switchId, portId, change, confirmUplink) }
    suspend fun bouncePort(switchId: String, portId: String, confirmUplink: Boolean) =
        load { bouncePort(switchId, portId, confirmUplink) }
    suspend fun writeMemory(switchId: String) = load { writeMemory(switchId) }
    suspend fun backupConfig(switchId: String) = load { backupConfig(switchId) }
    suspend fun configDiff(switchId: String) = load { configDiff(switchId) }

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

    suspend fun credentials(): Result<List<Credential>> = load { credentials() }
    suspend fun saveCredential(id: String?, draft: CredentialDraft): Result<Credential> = load { saveCredential(id, draft) }
    suspend fun deleteCredential(id: String): Result<Unit> = load { deleteCredential(id) }

    suspend fun planSvg(url: String): Result<String> = load { planSvg(url) }

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
        if (settings.demoMode) DemoDataSource else RemoteDataSource(api, liveSocket, settings)

    private suspend fun <T> load(block: suspend DcimDataSource.() -> T): Result<T> {
        val source = source(settingsRepository.current())
        return runCatchingCancellable { source.block() }
    }
}
