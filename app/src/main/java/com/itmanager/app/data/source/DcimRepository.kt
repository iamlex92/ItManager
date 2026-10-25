package com.itmanager.app.data.source

import com.itmanager.app.core.network.LiveSocket
import com.itmanager.app.core.network.NetAdminApi
import com.itmanager.app.core.network.runCatchingCancellable
import com.itmanager.app.core.settings.ServerSettings
import com.itmanager.app.core.settings.SettingsRepository
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
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
    /** Screens reload when this emits. */
    val settings: Flow<ServerSettings> = settingsRepository.settings

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

    suspend fun planSvg(url: String): Result<String> = load { planSvg(url) }

    /** Restarts the stream whenever settings change, so a new server address takes effect at once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun live(deviceId: String): Flow<LiveEvent> =
        settingsRepository.settings.flatMapLatest { source(it).live(deviceId) }

    private fun source(settings: ServerSettings): DcimDataSource =
        if (settings.demoMode) DemoDataSource else RemoteDataSource(api, liveSocket, settings)

    private suspend fun <T> load(block: suspend DcimDataSource.() -> T): Result<T> {
        val source = source(settingsRepository.current())
        return runCatchingCancellable { source.block() }
    }
}
