package com.itmanager.app.data.source

import android.os.Build
import com.itmanager.app.core.network.LiveSocket
import com.itmanager.app.core.network.NetAdminApi
import com.itmanager.app.core.network.BulkDeleteBody
import com.itmanager.app.core.network.BulkSetBody
import com.itmanager.app.core.network.BulkUpdateBody
import com.itmanager.app.core.network.DeviceBody
import com.itmanager.app.core.network.DeviceFromTemplateBody
import com.itmanager.app.core.network.DeviceTemplateBody
import com.itmanager.app.core.network.DeviceMove
import com.itmanager.app.core.network.FloorCreateBody
import com.itmanager.app.core.network.FloorDto
import com.itmanager.app.core.network.FloorPatch
import com.itmanager.app.core.network.FloorRename
import com.itmanager.app.core.network.RouteBody
import com.itmanager.app.core.network.cableColorPatch
import com.itmanager.app.core.network.WaypointDto
import com.itmanager.app.core.network.FaceplatePortsBody
import com.itmanager.app.core.network.ImportBody
import com.itmanager.app.core.network.attachBody
import com.itmanager.app.core.network.LinkBody
import com.itmanager.app.core.network.PcSweepBody
import com.itmanager.app.core.network.PcKeysBody
import com.itmanager.app.core.network.PcActionBody
import com.itmanager.app.core.network.RunbookRunBody
import com.itmanager.app.core.network.ScanBody
import com.itmanager.app.core.network.toBody
import com.itmanager.app.core.network.toPatch
import com.itmanager.app.core.network.UnknownTagException
import com.itmanager.app.core.network.UserFacingException
import com.itmanager.app.core.network.toDomain
import com.itmanager.app.core.settings.ServerSettings
import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.Annotation
import com.itmanager.app.data.model.PcSweepHit
import com.itmanager.app.data.model.PcStatus
import com.itmanager.app.data.model.PcResult
import com.itmanager.app.data.model.PcKeyJob
import com.itmanager.app.data.model.PcAction
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
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.DiagnosticKind
import com.itmanager.app.data.model.DiagnosticResult
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
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortDraft
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
import com.itmanager.app.data.model.Zone
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class RemoteDataSource(
    private val api: NetAdminApi,
    private val liveSocket: LiveSocket,
    private val settings: ServerSettings,
) : DcimDataSource {

    override suspend fun floorMap(floorId: String?): FloorMap = coroutineScope {
        val floors = async { api.floors(settings) }
        val devices = async { api.devices(settings).map { it.toDomain() } }
        val connections = async { api.connections(settings).mapNotNull { it.toDomain() } }
        val vlans = async { api.vlans(settings).map { it.toDomain() } }

        val allFloors = floors.await()
        val first = allFloors.firstOrNull()
        val floorDto = allFloors.firstOrNull { it.id == floorId } ?: first
        val allDevices = devices.await()
        val floorIds = allFloors.mapTo(HashSet()) { it.id }
        // Devices without a floor (found by the scanner, added before any floor existed) show on the first one.
        val onFirst = { d: Device -> d.floorId == null || d.floorId !in floorIds }
        val floorDevices = if (floorDto == null) {
            allDevices
        } else {
            allDevices.filter { it.floorId == floorDto.id || (floorDto.id == first?.id && onFirst(it)) }
        }
        // Without a plan size from the server, frame whatever devices exist, and leave room to add more.
        val fallbackWidth = ((floorDevices.maxOfOrNull { it.x } ?: 0f) + PLAN_MARGIN).coerceAtLeast(MIN_WIDTH)
        val fallbackHeight = ((floorDevices.maxOfOrNull { it.y } ?: 0f) + PLAN_MARGIN).coerceAtLeast(MIN_HEIGHT)
        val plan = floorDto?.toDomain(settings.baseUrl, fallbackWidth, fallbackHeight)
            ?: FloorPlan(NO_FLOOR, "План здания", fallbackWidth, fallbackHeight, 50f, emptyList(), stored = false)

        val ids = floorDevices.mapTo(HashSet()) { it.id }
        val links = connections.await().filter { it.deviceAId in ids || it.deviceBId in ids }
        val byId = allDevices.associateBy { it.id }
        val remote = links.flatMap { listOf(it.deviceAId, it.deviceBId) }.filter { it !in ids }
            .mapNotNull { id -> byId[id]?.let { d -> id to if (onFirst(d)) d.copy(floorId = first?.id) else d } }.toMap()
        FloorMap(
            plan = plan,
            devices = floorDevices,
            connections = links.filter { (it.deviceAId in ids || it.deviceAId in remote) && (it.deviceBId in ids || it.deviceBId in remote) },
            vlans = vlans.await(),
            floors = allFloors.map { it.info() },
            remoteDevices = remote,
        )
    }

    override suspend fun devices(): List<Device> = api.devices(settings).map { it.toDomain() }

    override suspend fun device(id: String): Device = api.device(settings, id).toDomain()

    override suspend fun ports(deviceId: String): List<SwitchPort> =
        api.ports(settings, deviceId).map { it.toDomain() }.sortedBy { it.number }

    override suspend fun printers(): List<Printer> = api.printers(settings).map { it.toDomain() }

    override suspend fun vlans(): List<Vlan> = api.vlans(settings).map { it.toDomain() }

    override suspend fun saveVlan(isNew: Boolean, draft: VlanDraft): Vlan =
        if (isNew) {
            api.createVlan(settings, draft.toBody(withId = true)).toDomain()
        } else {
            api.patchVlan(settings, draft.id, draft.toBody(withId = false)).toDomain()
        }

    override suspend fun deleteVlan(id: Int) = api.deleteVlan(settings, id)

    override suspend fun previewPortChange(switchId: String, portId: String, change: PortChange): List<String> =
        api.previewPortConfig(settings, portId, change).commands

    override suspend fun applyPortChange(switchId: String, portId: String, change: PortChange, confirmUplink: Boolean) =
        api.applyPortConfig(settings, portId, change, confirmUplink).toDomain()

    override suspend fun bouncePort(switchId: String, portId: String, confirmUplink: Boolean): List<String> =
        api.bouncePort(settings, portId, confirmUplink).commands

    override suspend fun writeMemory(switchId: String): List<String> = api.writeMemory(settings, switchId).commands

    override suspend fun backupConfig(switchId: String): String = api.backupConfig(settings, switchId)

    override suspend fun configDiff(switchId: String): ConfigDiff = api.configDiff(settings, switchId).toDomain()

    override suspend fun ipam(): IpamOverview = api.ipam(settings).toDomain()

    override suspend fun ipamCheck(ip: String): IpamCheck = api.ipamCheck(settings, ip).toDomain()

    override suspend fun deviceTemplates(): List<DeviceTemplate> = api.deviceTemplates(settings).map { it.toDomain() }

    override suspend fun saveDeviceTemplate(draft: DeviceTemplateDraft): DeviceTemplate = api.createDeviceTemplate(
        settings,
        DeviceTemplateBody(
            draft.name.trim(), draft.type.name, draft.vendor.trim().ifEmpty { null }, draft.model.trim().ifEmpty { null },
            draft.portCount, draft.portPrefix.trim().ifEmpty { "Port" }, draft.rackH,
        ),
    ).toDomain()

    override suspend fun deleteDeviceTemplate(id: String) = api.deleteDeviceTemplate(settings, id)

    override suspend fun createDeviceFromTemplate(templateId: String, name: String, ip: String?, at: MapPoint?): Device =
        api.createDeviceFromTemplate(
            settings,
            templateId,
            DeviceFromTemplateBody(name.trim(), ip?.trim()?.ifEmpty { null }, at?.floorId, at?.x, at?.y),
        ).toDomain()

    override suspend fun importConfig(switchId: String, text: String, apply: Boolean): ConfigImportResult =
        api.importConfig(settings, switchId, text, apply).toDomain()

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

    override suspend fun runbooks(): List<Runbook> = api.runbooks(settings).map { it.toDomain() }

    override suspend fun runRunbook(runbook: Runbook, arguments: Map<String, String>, authMethod: AuthMethod): RunbookRun =
        api.runRunbook(
            settings,
            runbook.id,
            RunbookRunBody(
                params = arguments,
                confirm = authMethod != AuthMethod.NOT_REQUIRED,
                authMethod = authMethod.wire,
                // No user accounts yet: the phone model tells runs from different phones apart in the log.
                operator = Build.MODEL,
            ),
        ).toDomain()

    override suspend fun audit(kind: AuditKind?): List<AuditEntry> = api.audit(settings, kind).map { it.toDomain() }

    override suspend fun startScan(subnets: List<String>, community: String): NetworkScan =
        api.startScan(settings, ScanBody(subnets, community, operator = Build.MODEL)).toDomain()

    override suspend fun scan(id: String): NetworkScan = api.scan(settings, id).toDomain()

    override suspend fun latestScan(): NetworkScan? = api.latestScan(settings)?.toDomain()

    override suspend fun importScan(id: String, ips: List<String>, withLinks: Boolean): ImportReport =
        api.importScan(settings, id, ImportBody(ips, withLinks, operator = Build.MODEL)).toDomain()

    override suspend fun deviceHistory(switchId: String): List<DeviceHistoryEntry> =
        api.deviceHistory(settings, switchId).map { it.toDomain() }

    override suspend fun switchTraffic(switchId: String, hours: Int): List<PortLoad> =
        api.switchTraffic(settings, switchId, hours).ports.map { it.toDomain() }

    override suspend fun portTraffic(switchId: String, portId: String, hours: Int, points: Int): TrafficSeries =
        api.portTraffic(settings, portId, hours, points).toDomain()

    override suspend fun credentials(): List<Credential> = api.credentials(settings).map { it.toDomain() }

    override suspend fun saveCredential(id: String?, draft: CredentialDraft): Credential =
        if (id == null) {
            api.createCredential(settings, draft.toBody(Build.MODEL)).toDomain()
        } else {
            api.updateCredential(settings, id, draft.toPatch(Build.MODEL)).toDomain()
        }

    override suspend fun deleteCredential(id: String) = api.deleteCredential(settings, id, Build.MODEL)

    override suspend fun planBytes(url: String): ByteArray = api.planBytes(settings, url)

    private fun FloorDto.toPlan(current: FloorPlan) = toDomain(settings.baseUrl, current.widthPx, current.heightPx)

    override suspend fun ensureFloor(plan: FloorPlan): FloorPlan {
        if (plan.stored) return plan
        val body = FloorCreateBody("Здание", 1, plan.widthPx.toInt(), plan.heightPx.toInt())
        return api.createFloor(settings, body).toPlan(plan)
    }

    /** A server from before a category existed answers 422 to it: say that, instead of "check the fields". */
    private suspend fun <T> knowingTypes(type: DeviceType?, block: suspend () -> T): T = try {
        block()
    } catch (e: UserFacingException) {
        val status = (e.cause as? ResponseException)?.response?.status?.value
        if (type != null && type in NEWER_TYPES && status == 422) {
            throw UserFacingException(
                "Этот сервер ещё не знает категорию «${type.label}»: он старой версии. Обновите сервер (установщик по ссылке в README), и она появится.",
                e,
            )
        }
        throw e
    }

    override suspend fun createDevice(floorId: String, draft: DeviceDraft, x: Float, y: Float): Device =
        knowingTypes(draft.type) {
            api.createDevice(
                settings,
                DeviceBody(
                    draft.name, draft.type.name, floorId, x, y, draft.ip, draft.vendor, draft.model, draft.hostId,
                    draft.comment, draft.rackId, draft.rackU, draft.rackH, draft.rackUnits, draft.mac?.trim()?.ifEmpty { null },
                    sshCredentialId = draft.sshCredentialId,
                ),
            ).toDomain()
        }

    override suspend fun updateDevice(id: String, draft: DeviceDraft): Device =
        knowingTypes(draft.type) { api.patchDevice(settings, id, draft.toPatch()).toDomain() }

    override suspend fun moveDevice(id: String, floorId: String, x: Float, y: Float) {
        api.moveDevice(settings, id, DeviceMove(x, y, floorId))
    }

    override suspend fun deleteDevice(id: String) = api.deleteDevice(settings, id)

    override suspend fun bulkUpdateDevices(ids: List<String>, change: DeviceBulkChange): Int = knowingTypes(change.type) {
        val set = BulkSetBody(change.type?.name, change.floorId, change.vendor, change.hostId)
        try {
            api.bulkUpdateDevices(settings, BulkUpdateBody(ids, set)).changed
        } catch (e: Exception) {
            if (!api.isMissingEndpoint(e)) throw e
            // An older server: the same change, one device at a time.
            val patch = Json { explicitNulls = false }.encodeToJsonElement(BulkSetBody.serializer(), set).jsonObject
            ids.forEach { api.patchDevice(settings, it, patch) }
            ids.size
        }
    }

    override suspend fun bulkDeleteDevices(ids: List<String>): Int = try {
        api.bulkDeleteDevices(settings, BulkDeleteBody(ids)).changed
    } catch (e: Exception) {
        if (!api.isMissingEndpoint(e)) throw e
        ids.forEach { api.deleteDevice(settings, it) }
        ids.size
    }

    override suspend fun linkDevices(
        deviceAId: String,
        deviceBId: String,
        cableType: String,
        portAId: String?,
        portBId: String?,
    ): Connection =
        api.linkDevices(settings, LinkBody(deviceAId, deviceBId, cableType, portAId, portBId)).toDomain()
            ?: throw UserFacingException("Сервер не вернул кабель")

    override suspend fun deleteConnection(id: String) = api.deleteConnection(settings, id)

    override suspend fun recolorConnection(id: String, colorArgb: Long?): Connection =
        api.patchConnection(settings, id, cableColorPatch(colorArgb)).toDomain()
            ?: throw UserFacingException("Сервер не вернул кабель")

    override suspend fun createPort(deviceId: String, draft: PortDraft): SwitchPort =
        api.createPort(settings, draft.toPatch(deviceId)).toDomain()

    override suspend fun updatePortRecord(portId: String, draft: PortDraft): SwitchPort =
        api.patchPort(settings, portId, draft.toPatch()).toDomain()

    override suspend fun deletePort(portId: String) = api.deletePort(settings, portId)

    override suspend fun routeConnection(id: String, waypoints: List<Waypoint>): Connection =
        api.routeConnection(settings, id, RouteBody(waypoints.map(WaypointDto::of))).toDomain()
            ?: throw UserFacingException("Сервер не вернул кабель")

    override suspend fun createFloor(draft: FloorDraft): FloorInfo {
        // A new floor takes the size of the lowest one, so devices placed by hand land in the same frame.
        val base = api.floors(settings).firstOrNull()
        val body = FloorCreateBody(
            draft.building, draft.number, base?.widthPx?.toInt() ?: MIN_WIDTH.toInt(), base?.heightPx?.toInt() ?: MIN_HEIGHT.toInt(),
            draft.name?.takeIf { it.isNotBlank() },
        )
        return api.createFloor(settings, body).info()
    }

    override suspend fun renameFloor(id: String, draft: FloorDraft): FloorInfo =
        api.renameFloor(settings, id, FloorRename(draft.building, draft.number, draft.name.orEmpty())).info()

    override suspend fun deleteFloor(id: String) = api.deleteFloor(settings, id)

    override suspend fun uploadPlan(plan: FloorPlan, file: PlanFile): FloorPlan =
        api.uploadPlan(settings, plan.id, file.bytes, file.mime, file.width, file.height).toPlan(plan)

    override suspend fun removePlan(plan: FloorPlan): FloorPlan = api.deletePlan(settings, plan.id).toPlan(plan)

    override suspend fun placePlan(plan: FloorPlan, rect: PlanRect): FloorPlan =
        api.patchFloor(settings, plan.id, FloorPatch.placement(rect, plan.widthPx, plan.heightPx)).toPlan(plan)

    override suspend fun saveDrawings(plan: FloorPlan, drawings: List<Drawing>): FloorPlan =
        api.patchFloor(settings, plan.id, FloorPatch.drawings(drawings)).toPlan(plan)

    override suspend fun saveZones(plan: FloorPlan, zones: List<Zone>): FloorPlan =
        api.patchFloor(settings, plan.id, FloorPatch.zones(zones)).toPlan(plan)

    override suspend fun saveAnnotations(plan: FloorPlan, annotations: List<Annotation>): FloorPlan =
        api.patchFloor(settings, plan.id, FloorPatch.annotations(annotations)).toPlan(plan)

    override suspend fun peripherals(): List<Peripheral> = api.peripherals(settings).map { it.toDomain() }

    override suspend fun savePeripheral(id: String?, draft: PeripheralDraft): Peripheral =
        if (id == null) {
            api.createPeripheral(settings, draft.toBody()).toDomain()
        } else {
            api.patchPeripheral(settings, id, draft.toBody()).toDomain()
        }

    override suspend fun attachPeripheral(id: String, deviceId: String?): Peripheral =
        api.patchPeripheral(settings, id, attachBody(deviceId)).toDomain()

    override suspend fun deletePeripheral(id: String) = api.deletePeripheral(settings, id)

    override suspend fun topology(): Topology = api.topology(settings).toDomain()

    override suspend fun faceplate(deviceId: String): Faceplate? = api.faceplate(settings, deviceId)?.toDomain()

    override suspend fun faceplateSvg(deviceId: String): ByteArray = api.faceplateSvg(settings, deviceId)

    override suspend fun uploadFaceplate(deviceId: String, svg: ByteArray): Faceplate =
        api.uploadFaceplate(settings, deviceId, svg).toDomain()

    override suspend fun saveFaceplatePorts(deviceId: String, marks: Collection<FaceplatePort>): Faceplate =
        api.setFaceplatePorts(settings, deviceId, FaceplatePortsBody.of(marks)).toDomain()

    override suspend fun copyFaceplate(deviceId: String, fromDeviceId: String): Faceplate =
        api.copyFaceplate(settings, deviceId, fromDeviceId).toDomain()

    override suspend fun deleteFaceplate(deviceId: String) = api.deleteFaceplate(settings, deviceId)

    override fun live(deviceId: String): Flow<LiveEvent> = liveSocket.events(settings, deviceId)

    override suspend fun pcStatus(): PcStatus = api.pcStatus(settings).toDomain()

    override suspend fun pcSweep(prefix: String): List<PcSweepHit> = api.pcSweep(settings, PcSweepBody(prefix)).map { it.toDomain() }

    override suspend fun pcAction(host: String, action: PcAction, arg: String?, authMethod: AuthMethod): PcResult =
        api.pcAction(
            settings,
            host,
            action.wire,
            PcActionBody(
                arg = arg,
                confirm = action.confirm && authMethod != AuthMethod.NOT_REQUIRED,
                authMethod = authMethod.wire,
                operator = Build.MODEL,
            ),
        ).toDomain(action)

    override suspend fun pcKeysStart(source: String, prefix: String?): PcKeyJob =
        api.pcKeysStart(settings, PcKeysBody(source, prefix, operator = Build.MODEL)).toDomain()

    override suspend fun pcKeys(id: String): PcKeyJob = api.pcKeys(settings, id).toDomain()

    override suspend fun pcKeysCsv(id: String): ByteArray = api.download(settings, "pcs/keys/$id/csv")

    private companion object {
        const val PLAN_MARGIN = 200f
        const val MIN_WIDTH = 1600f
        const val MIN_HEIGHT = 1000f
        const val NO_FLOOR = "default"
    }
}

internal const val PING_COUNT = 5

internal fun notFound(what: String): Nothing = throw UserFacingException("$what не найдено")

/** Categories added after the first server release: an older server does not accept them. */
private val NEWER_TYPES = setOf(DeviceType.ROUTER, DeviceType.FIREWALL, DeviceType.UPS, DeviceType.PHONE, DeviceType.VIRTUAL, DeviceType.RACK)
