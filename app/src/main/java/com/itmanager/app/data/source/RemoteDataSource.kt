package com.itmanager.app.data.source

import com.itmanager.app.core.network.LiveSocket
import com.itmanager.app.core.network.NetAdminApi
import com.itmanager.app.core.network.UnknownTagException
import com.itmanager.app.core.network.UserFacingException
import com.itmanager.app.core.network.toDomain
import com.itmanager.app.core.settings.ServerSettings
import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.ConfigDiff
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DiagnosticKind
import com.itmanager.app.data.model.DiagnosticResult
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.FloorPlan
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

class RemoteDataSource(
    private val api: NetAdminApi,
    private val liveSocket: LiveSocket,
    private val settings: ServerSettings,
) : DcimDataSource {

    override suspend fun floorMap(): FloorMap = coroutineScope {
        val floors = async { api.floors(settings) }
        val devices = async { api.devices(settings).map { it.toDomain() } }
        val connections = async { api.connections(settings).mapNotNull { it.toDomain() } }
        val vlans = async { api.vlans(settings).map { it.toDomain() } }

        val floorDto = floors.await().firstOrNull()
        val allDevices = devices.await()
        val floorDevices = if (floorDto == null) allDevices else allDevices.filter { it.floorId == floorDto.id }
        // Without a plan size from the server, frame whatever devices exist.
        val fallbackWidth = (floorDevices.maxOfOrNull { it.x } ?: 0f) + PLAN_MARGIN
        val fallbackHeight = (floorDevices.maxOfOrNull { it.y } ?: 0f) + PLAN_MARGIN
        val plan = floorDto?.toDomain(settings.baseUrl, fallbackWidth, fallbackHeight)
            ?: FloorPlan("default", "План здания", fallbackWidth, fallbackHeight, 50f, emptyList())

        val ids = floorDevices.mapTo(HashSet()) { it.id }
        FloorMap(
            plan = plan,
            devices = floorDevices,
            connections = connections.await().filter { it.deviceAId in ids && it.deviceBId in ids },
            vlans = vlans.await(),
        )
    }

    override suspend fun devices(): List<Device> = api.devices(settings).map { it.toDomain() }

    override suspend fun device(id: String): Device = api.device(settings, id).toDomain()

    override suspend fun ports(deviceId: String): List<SwitchPort> =
        api.ports(settings, deviceId).map { it.toDomain() }.sortedBy { it.number }

    override suspend fun printers(): List<Printer> = api.printers(settings).map { it.toDomain() }

    override suspend fun vlans(): List<Vlan> = api.vlans(settings).map { it.toDomain() }

    override suspend fun previewPortChange(switchId: String, portId: String, change: PortChange): List<String> =
        api.previewPortConfig(settings, portId, change).commands

    override suspend fun applyPortChange(switchId: String, portId: String, change: PortChange, confirmUplink: Boolean) =
        api.applyPortConfig(settings, portId, change, confirmUplink).toDomain()

    override suspend fun bouncePort(switchId: String, portId: String, confirmUplink: Boolean): List<String> =
        api.bouncePort(settings, portId, confirmUplink).commands

    override suspend fun writeMemory(switchId: String): List<String> = api.writeMemory(settings, switchId).commands

    override suspend fun backupConfig(switchId: String) = api.backupConfig(settings, switchId)

    override suspend fun configDiff(switchId: String): ConfigDiff = api.configDiff(settings, switchId).toDomain()

    override suspend fun diagnostic(switchId: String, kind: DiagnosticKind, target: String): DiagnosticResult =
        when (kind) {
            DiagnosticKind.PING -> api.ping(settings, switchId, target, PING_COUNT)
            DiagnosticKind.TRACEROUTE -> api.traceroute(settings, switchId, target)
        }.toDomain()

    override suspend fun deviceForTag(payloadId: String?, uid: String): Device {
        if (payloadId != null) {
            try {
                return api.deviceForTag(settings, payloadId).toDomain()
            } catch (_: UnknownTagException) {
                // The device was deleted or re-created; the tag's UID binding may still be valid.
            }
        }
        return api.deviceByNfc(settings, uid).toDomain()
    }

    override suspend fun bindNfc(deviceId: String, uid: String, force: Boolean): Device =
        api.bindNfc(settings, deviceId, uid, force).toDomain()

    override suspend fun alerts(): List<Alert> = api.alerts(settings).map { it.toDomain() }

    override suspend fun resolveAlert(alertId: String): Alert = api.resolveAlert(settings, alertId).toDomain()

    override suspend fun refreshPrinter(printerId: String): Printer = api.refreshPrinter(settings, printerId).toDomain()

    override suspend fun planSvg(url: String): String = api.text(url)

    override fun live(deviceId: String): Flow<LiveEvent> = liveSocket.events(settings, deviceId)

    private companion object {
        const val PLAN_MARGIN = 200f
    }
}

internal const val PING_COUNT = 5

internal fun notFound(what: String): Nothing = throw UserFacingException("$what не найдено")
