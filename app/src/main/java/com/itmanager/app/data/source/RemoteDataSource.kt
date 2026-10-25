package com.itmanager.app.data.source

import com.itmanager.app.core.network.NetAdminApi
import com.itmanager.app.core.network.UserFacingException
import com.itmanager.app.core.network.toDomain
import com.itmanager.app.core.settings.ServerSettings
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.FloorPlan
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.SwitchPort
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class RemoteDataSource(
    private val api: NetAdminApi,
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
        val plan = floorDto?.toDomain(fallbackWidth, fallbackHeight)
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

    private companion object {
        const val PLAN_MARGIN = 200f
    }
}

internal fun notFound(what: String): Nothing = throw UserFacingException("$what не найдено")
