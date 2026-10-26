package com.itmanager.app.data.source

import android.os.Build
import com.itmanager.app.core.network.TagBoundElsewhereException
import com.itmanager.app.core.network.UnknownTagException
import com.itmanager.app.core.network.UserFacingException
import com.itmanager.app.data.model.ALL_DEVICES
import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.AlertSeverity
import com.itmanager.app.data.model.AuditEntry
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.ConfigDiff
import com.itmanager.app.data.model.Credential
import com.itmanager.app.data.model.CredentialDraft
import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Consumable
import com.itmanager.app.data.model.ConsumableKind
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceBulkChange
import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.DeviceHistoryEntry
import com.itmanager.app.data.model.Drawing
import com.itmanager.app.data.model.HistoryKind
import com.itmanager.app.data.model.describePortChange
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.DiagnosticKind
import com.itmanager.app.data.model.DiagnosticResult
import com.itmanager.app.data.model.FloorDraft
import com.itmanager.app.data.model.FloorInfo
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.FloorPlan
import com.itmanager.app.data.model.FoundDevice
import com.itmanager.app.data.model.FoundLink
import com.itmanager.app.data.model.ImportReport
import com.itmanager.app.data.model.LinkEnd
import com.itmanager.app.data.model.LinkSource
import com.itmanager.app.data.model.NetworkScan
import com.itmanager.app.data.model.ScanRanges
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortLoad
import com.itmanager.app.data.model.PortChangeResult
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.PlanFile
import com.itmanager.app.data.model.PlanRect
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.Room
import com.itmanager.app.data.model.Runbook
import com.itmanager.app.data.model.RunbookRun
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.TrafficSeries
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.data.model.Waypoint
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A small made-up factory floor: server room, IT office, accounting and a workshop.
 * Lets every screen work without a server and without touching production hardware.
 */
object DemoDataSource : DcimDataSource {

    private const val FLOOR_ID = "floor-1"
    private const val FLOOR_2_ID = "floor-2"
    private const val CORE_SWITCH_ID = "sw-core-01"

    /** Floors bottom to top; the first one also holds devices added without a floor. */
    @Volatile private var floors = listOf(
        FloorInfo(FLOOR_ID, "АБК и цех №1", 1, null),
        FloorInfo(FLOOR_2_ID, "АБК", 2, null),
    )

    @Volatile private var plans = mapOf(
        FLOOR_ID to FloorPlan(
            id = FLOOR_ID,
            title = titleOf(floors[0]),
            widthPx = 1600f,
            heightPx = 1000f,
            pixelsPerMeter = 25f,
            rooms = listOf(
                Room("Серверная", 40f, 40f, 360f, 300f),
                Room("ИТ-отдел", 380f, 40f, 700f, 300f),
                Room("Бухгалтерия", 720f, 40f, 1100f, 300f),
                Room("Склад", 1120f, 40f, 1560f, 300f),
                Room("Цех №1", 40f, 340f, 1560f, 960f),
            ),
        ),
        FLOOR_2_ID to FloorPlan(
            id = FLOOR_2_ID,
            title = titleOf(floors[1]),
            widthPx = 1600f,
            heightPx = 1000f,
            pixelsPerMeter = 25f,
            rooms = listOf(
                Room("Кроссовая", 40f, 40f, 360f, 300f),
                Room("Отдел кадров", 380f, 40f, 860f, 300f),
                Room("Приёмная", 880f, 40f, 1560f, 300f),
                Room("Переговорная", 40f, 340f, 860f, 700f),
            ),
        ),
    )

    private fun titleOf(info: FloorInfo) = info.name?.takeIf { it.isNotBlank() } ?: "${info.building} · этаж ${info.number}"

    private val vlans = listOf(
        Vlan(10, "Бухгалтерия", "10.10.0.0/24", 0xFF0A84FF),
        Vlan(20, "Производство", "10.20.0.0/23", 0xFFFF9F0A),
        Vlan(30, "Видеонаблюдение", "10.30.0.0/24", 0xFFBF5AF2),
        Vlan(40, "Принтеры", "10.40.0.0/24", 0xFF64D2FF),
        Vlan(50, "ИТ", "10.50.0.0/24", 0xFF30D158),
        Vlan(99, "Управление", "10.99.0.0/24", 0xFF8E8E93),
    )

    // Vars: devices found by the demo network scan can be added to the inventory.
    @Volatile private var devices = listOf(
        d("sw-core-01", "SW-CORE-01", DeviceType.SWITCH, "Cisco", "C9300-24P", "10.99.0.1", 120f, 110f, vlan = 99),
        d("srv-01", "SRV-01", DeviceType.SERVER, "HPE", "ProLiant DL380 Gen11", "10.50.0.10", 140f, 230f, vlan = 50),
        // Virtual machines on SRV-01: on L2 and L3 next to their host, never on L1.
        d("vm-1c", "VM-1C", DeviceType.VIRTUAL, "VMware", "1С:Предприятие", "10.50.0.21", 90f, 300f, vlan = 50, host = "srv-01"),
        d("vm-dc", "VM-DC-01", DeviceType.VIRTUAL, "VMware", "Контроллер домена", "10.50.0.22", 190f, 300f, vlan = 50, host = "srv-01"),
        d("pp-01", "PP-01", DeviceType.PATCH_PANEL, null, "Патч-панель 24 порта", null, 270f, 110f),
        d("sw-acc-02", "SW-ACC-02", DeviceType.SWITCH, "Eltex", "MES2324P", "10.99.0.2", 640f, 90f, vlan = 99),
        d("pp-02", "PP-02", DeviceType.PATCH_PANEL, null, "Патч-панель 24 порта", null, 640f, 180f),
        d("r-201", "R-201", DeviceType.SOCKET, null, "RJ-45", null, 470f, 280f),
        d("pc-it-01", "PC-IT-01", DeviceType.WORKSTATION, "Lenovo", "ThinkCentre M70q", "10.50.0.31", 470f, 170f, vlan = 50),
        d("r-101", "R-101", DeviceType.SOCKET, null, "RJ-45", null, 800f, 280f),
        d("r-102", "R-102", DeviceType.SOCKET, null, "RJ-45", null, 960f, 280f),
        d("r-103", "R-103", DeviceType.SOCKET, null, "RJ-45", null, 1070f, 200f),
        d("pc-buh-01", "PC-BUH-01", DeviceType.WORKSTATION, "Lenovo", "ThinkCentre M70q", "10.10.0.21", 800f, 160f, vlan = 10),
        d("pc-buh-02", "PC-BUH-02", DeviceType.WORKSTATION, "Lenovo", "ThinkCentre M70q", "10.10.0.22", 960f, 160f, vlan = 10, online = false),
        d("mfp-buh", "MFP-BUH", DeviceType.PRINTER, "Kyocera", "ECOSYS M8130cidn", "10.40.0.21", 1050f, 90f, vlan = 40),
        d("pc-sklad", "PC-SKLAD", DeviceType.WORKSTATION, "Dell", "OptiPlex 7010", "10.20.0.40", 1300f, 170f, vlan = 20),
        d("sw-acc-03", "SW-ACC-03", DeviceType.SWITCH, "Eltex", "MES2348P", "10.99.0.3", 110f, 420f, vlan = 99),
        d("r-301", "R-301", DeviceType.SOCKET, null, "RJ-45", null, 880f, 680f),
        d("pc-ceh-01", "PC-CEH-01", DeviceType.WORKSTATION, "Advantech", "IPC-610", "10.20.0.11", 880f, 580f, vlan = 20),
        d("pc-ceh-02", "PC-CEH-02", DeviceType.WORKSTATION, "Advantech", "IPC-610", "10.20.0.12", 1220f, 820f, vlan = 20, online = false),
        d("mfp-ceh", "MFP-CEH", DeviceType.PRINTER, "HP", "LaserJet Pro M428", "10.40.0.31", 380f, 520f, vlan = 40),
        d("cam-01", "CAM-01", DeviceType.CAMERA, "Hikvision", "DS-2CD2143G2", "10.30.0.11", 600f, 740f, vlan = 30),
        d("cam-02", "CAM-02", DeviceType.CAMERA, "Hikvision", "DS-2CD2143G2", "10.30.0.12", 1400f, 740f, vlan = 30),
        d("cam-03", "CAM-03", DeviceType.CAMERA, "Hikvision", "DS-2CD2143G2", "10.30.0.13", 1500f, 400f, vlan = 30, online = false),
        d("ap-01", "AP-01", DeviceType.ACCESS_POINT, "Ubiquiti", "U6 Pro", "10.99.0.51", 800f, 900f, vlan = 99),
        // Second floor of the office building, fed by a riser from the server room.
        d("sw-acc-05", "SW-ACC-05", DeviceType.SWITCH, "Eltex", "MES2324P", "10.99.0.5", 150f, 120f, vlan = 99, floor = FLOOR_2_ID),
        d("pc-hr-01", "PC-HR-01", DeviceType.WORKSTATION, "Lenovo", "ThinkCentre M70q", "10.10.0.41", 560f, 170f, vlan = 10, floor = FLOOR_2_ID),
        d("mfp-adm", "MFP-ADM", DeviceType.PRINTER, "Kyocera", "TASKalfa 2553ci", "10.40.0.11", 1200f, 120f, vlan = 40, floor = FLOOR_2_ID),
        d("ap-02", "AP-02", DeviceType.ACCESS_POINT, "Ubiquiti", "U6 Pro", "10.99.0.52", 450f, 520f, vlan = 99, floor = FLOOR_2_ID),
    )

    @Volatile private var connections = listOf(
        c("sw-core-01", "srv-01", "Cat6"),
        c("sw-core-01", "sw-acc-02", "OM3", "Магистраль АБК", listOf(Waypoint(120f, 60f), Waypoint(640f, 60f))),
        // Up the riser in the server room corner to the second floor.
        c(
            "sw-core-01", "sw-acc-05", "OM3", "Стояк на 2 этаж",
            listOf(Waypoint(330f, 110f, FLOOR_ID), Waypoint(330f, 60f, FLOOR_ID), Waypoint(330f, 60f, FLOOR_2_ID)),
        ),
        c("pc-hr-01", "sw-acc-05", "Cat6", null, listOf(Waypoint(560f, 260f), Waypoint(250f, 260f))),
        c("mfp-adm", "sw-acc-05", "Cat6", null, listOf(Waypoint(1200f, 280f), Waypoint(250f, 280f))),
        c("ap-02", "sw-acc-05"),
        c("sw-core-01", "sw-acc-03", "OM3", "Магистраль цеха"),
        c("pc-it-01", "r-201"), c("r-201", "pp-02"),
        c("pc-buh-01", "r-101"), c("r-101", "pp-02"),
        c("pc-buh-02", "r-102"), c("r-102", "pp-02"),
        c("mfp-buh", "r-103"), c("r-103", "pp-02"),
        c("pp-02", "sw-acc-02", "Cat6", "Патч-корд"),
        c("pc-sklad", "pp-01"), c("pp-01", "sw-core-01", "Cat6", "Патч-корд"),
        c("pc-ceh-01", "r-301"), c("r-301", "sw-acc-03"),
        c("pc-ceh-02", "sw-acc-03"),
        c("mfp-ceh", "sw-acc-03"),
        c("cam-01", "sw-acc-03"), c("cam-02", "sw-acc-03"), c("cam-03", "sw-acc-03"),
        c("ap-01", "sw-acc-03"),
    )

    private val printers = listOf(
        Printer(
            "mfp-buh", "MFP-BUH", "10.40.0.21", 48_231,
            listOf(
                Consumable("Чёрный", 0xFF1C1C1E, 64),
                Consumable("Голубой", 0xFF32ADE6, 7),
                Consumable("Пурпурный", 0xFFFF2D55, 38),
                Consumable("Жёлтый", 0xFFFFD60A, 81),
                Consumable("Барабан", 0xFF8E8E93, 55, ConsumableKind.DRUM),
                Consumable("Отработка", 0xFF8E8E93, 42, ConsumableKind.WASTE),
            ),
            model = "Kyocera ECOSYS M8130cidn",
        ),
        Printer(
            "mfp-ceh", "MFP-CEH", "10.40.0.31", 120_554,
            listOf(Consumable("Чёрный", 0xFF1C1C1E, 9), Consumable("Барабан", 0xFF8E8E93, 61, ConsumableKind.DRUM)),
            model = "HP LaserJet Pro M428",
        ),
        Printer(
            "mfp-adm", "MFP-ADM", "10.40.0.11", 211_907,
            listOf(
                Consumable("Чёрный", 0xFF1C1C1E, 47),
                Consumable("Голубой", 0xFF32ADE6, 72),
                Consumable("Пурпурный", 0xFFFF2D55, 69),
                Consumable("Жёлтый", 0xFFFFD60A, 58),
                Consumable("Барабан", 0xFF8E8E93, 34, ConsumableKind.DRUM),
                Consumable("Отработка", 0xFF8E8E93, 8, ConsumableKind.WASTE),
            ),
            model = "Kyocera TASKalfa 2553ci",
        ),
        Printer(
            "prn-sklad", "PRN-SKLAD", "10.40.0.41", 33_018,
            listOf(Consumable("Чёрный", 0xFF1C1C1E, 91), Consumable("Барабан", 0xFF8E8E93, 88, ConsumableKind.DRUM)),
            model = "HP LaserJet M404",
            isOnline = false,
        ),
    )

    @Volatile private var byId = devices.associateBy { it.id }

    override suspend fun floorMap(floorId: String?): FloorMap {
        simulateNetwork()
        val info = floors.firstOrNull { it.id == floorId } ?: floors.first()
        val here = devices.filter { it.floorId == info.id }
        val ids = here.mapTo(HashSet()) { it.id }
        val links = connections.filter { it.deviceAId in ids || it.deviceBId in ids }
        val remote = links.flatMap { listOf(it.deviceAId, it.deviceBId) }.filter { it !in ids }
            .mapNotNull { id -> byId[id]?.let { id to it } }.toMap()
        return FloorMap(plans.getValue(info.id), here, links, vlans, floors, remote)
    }

    override suspend fun devices(): List<Device> {
        simulateNetwork()
        return devices
    }

    override suspend fun device(id: String): Device {
        simulateNetwork()
        return byId[id] ?: notFound("Устройство")
    }

    override suspend fun ports(deviceId: String): List<SwitchPort> {
        simulateNetwork()
        return currentPorts(switchOrThrow(deviceId))
    }

    // --- Simulated writes. Nothing leaves the phone; state lives until the app is closed. ---

    /** Ports the user changed in demo mode, by port id. */
    private val changedPorts = HashMap<String, SwitchPort>()
    /** Stored config copies per switch, for the diff screen. */
    private val storedConfigs = HashMap<String, List<String>>()
    private val history = HashMap<String, ArrayDeque<DeviceHistoryEntry>>()

    private fun logHistory(switchId: String, kind: HistoryKind, title: String, summary: String, commands: List<String>) {
        val log = history.getOrPut(switchId) { ArrayDeque() }
        log.addFirst(DeviceHistoryEntry(Instant.now(), kind, title, summary, "${Build.MODEL} · демо", commands))
        while (log.size > 200) log.removeLast()
    }
    private val demoEvents = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 16)

    private fun switchOrThrow(id: String) = byId[id]?.takeIf { it.type == DeviceType.SWITCH } ?: notFound("Коммутатор")

    private fun currentPorts(switch: Device) = generatePorts(switch).map { changedPorts[it.id] ?: it }

    private fun portOrThrow(switch: Device, portId: String) =
        currentPorts(switch).firstOrNull { it.id == portId } ?: notFound("Порт")

    override suspend fun vlans(): List<Vlan> = vlans

    override suspend fun previewPortChange(switchId: String, portId: String, change: PortChange): List<String> {
        simulateNetwork()
        val switch = switchOrThrow(switchId)
        val commands = CommandTranslator.portChange(switch.vendor, portOrThrow(switch, portId), change)
        if (commands.isEmpty()) throw UserFacingException("Изменений нет: порт уже настроен так.")
        return commands
    }

    override suspend fun applyPortChange(
        switchId: String, portId: String, change: PortChange, confirmUplink: Boolean,
    ): PortChangeResult {
        val commands = previewPortChange(switchId, portId, change)
        val switch = switchOrThrow(switchId)
        val port = portOrThrow(switch, portId)
        if (port.isUplink && !confirmUplink) throw UserFacingException("Это аплинк. Подтвердите изменение аплинка явно.")
        storedConfigs.getOrPut(switchId) { runningConfig(switch) }
        val mode = change.mode ?: port.mode
        val updated = port.copy(
            mode = mode,
            accessVlan = if (mode == PortMode.ACCESS) change.accessVlan ?: port.accessVlan else null,
            taggedVlans = if (mode == PortMode.TRUNK) change.taggedVlans ?: port.taggedVlans else emptyList(),
            isAdminUp = change.isAdminUp ?: port.isAdminUp,
            isOperUp = (change.isAdminUp ?: port.isAdminUp) && port.isOperUp,
        )
        changedPorts[portId] = updated
        demoEvents.emit(LiveEvent.PortChanged(switchId, updated))
        DemoRunbooks.recordSwitch("Настройка порта", listOf("device" to switch.name, "port" to port.name), commands)
        logHistory(switchId, HistoryKind.CHANGE, "Настройка порта", describePortChange(port, change), commands)
        return PortChangeResult(updated, commands)
    }

    override suspend fun bouncePort(switchId: String, portId: String, confirmUplink: Boolean): List<String> {
        val switch = switchOrThrow(switchId)
        val port = portOrThrow(switch, portId)
        if (port.isUplink && !confirmUplink) throw UserFacingException("Это аплинк. Подтвердите перезапуск аплинка явно.")
        demoEvents.emit(LiveEvent.PortChanged(switchId, port.copy(isOperUp = false)))
        delay(2_000)
        demoEvents.emit(LiveEvent.PortChanged(switchId, port.copy(isOperUp = true)))
        return CommandTranslator.bounce(switch.vendor, port).also {
            DemoRunbooks.recordSwitch("Перезапуск порта", listOf("device" to switch.name, "port" to port.name), it)
            logHistory(switchId, HistoryKind.CHANGE, "Перезапуск порта", "${port.name}: shutdown и no shutdown", it)
        }
    }

    override suspend fun writeMemory(switchId: String): List<String> {
        simulateNetwork()
        val switch = switchOrThrow(switchId)
        return CommandTranslator.writeMemory(switch.vendor).also {
            DemoRunbooks.recordSwitch("Сохранение конфигурации", listOf("device" to switch.name), it)
            logHistory(switchId, HistoryKind.CHANGE, "Сохранение конфигурации", "running-config сохранён в startup-config", it)
        }
    }

    override suspend fun backupConfig(switchId: String) {
        simulateNetwork()
        val current = runningConfig(switchOrThrow(switchId))
        val note = when (storedConfigs[switchId]) {
            null -> "первая сохранённая копия"
            current -> "без изменений с прошлой копии"
            else -> "конфигурация изменилась с прошлой копии"
        }
        storedConfigs[switchId] = current
        logHistory(switchId, HistoryKind.CONFIG_BACKUP, "Копия конфигурации", note, emptyList())
    }

    override suspend fun deviceHistory(switchId: String): List<DeviceHistoryEntry> {
        simulateNetwork()
        switchOrThrow(switchId)
        return history[switchId]?.toList().orEmpty()
    }

    override suspend fun configDiff(switchId: String): ConfigDiff {
        simulateNetwork()
        val switch = switchOrThrow(switchId)
        val stored = storedConfigs.getOrPut(switchId) { runningConfig(switch) }
        val diff = blockDiff(stored, runningConfig(switch))
        return ConfigDiff(changed = diff.isNotEmpty(), diff = diff)
    }

    private fun runningConfig(switch: Device) =
        CommandTranslator.runningConfig(switch.vendor, switch.name, currentPorts(switch))

    /** Last simulated SNMP poll per printer; the first load counts as a poll a few minutes ago. */
    private val polledAt = HashMap<String, Instant>()

    private fun printerNow(p: Printer) =
        p.copy(polledAt = polledAt.getOrPut(p.id) { Instant.now().minusSeconds(180) }.takeIf { p.isOnline })

    override suspend fun printers(): List<Printer> {
        simulateNetwork()
        return printers.map(::printerNow)
    }

    override suspend fun refreshPrinter(printerId: String): Printer {
        delay(800)
        val printer = printers.firstOrNull { it.id == printerId } ?: notFound("МФУ")
        if (printer.isOnline) polledAt[printerId] = Instant.now()
        return printerNow(printer).also { demoEvents.emit(LiveEvent.PrinterChanged(it)) }
    }

    // --- NFC tags: bindings live in memory until the app is closed. ---

    private val nfcBindings = HashMap<String, String>()

    override suspend fun deviceForTag(payloadId: String?, uid: String): Device {
        simulateNetwork()
        return payloadId?.let { byId[it] } ?: nfcBindings[uid]?.let { byId[it] } ?: throw UnknownTagException()
    }

    override suspend fun bindNfc(deviceId: String, uid: String, force: Boolean): Device {
        simulateNetwork()
        val device = byId[deviceId] ?: notFound("Устройство")
        val current = nfcBindings[uid]
        if (current != null && current != deviceId && !force) throw TagBoundElsewhereException(byId[current]?.name)
        // One tag per device, as in the server's inventory.
        nfcBindings.entries.removeAll { it.value == deviceId }
        nfcBindings[uid] = deviceId
        return device
    }

    // --- Alerts ---

    private val alerts = mutableListOf(
        demoAlert("a-1", "cam-03", AlertSeverity.CRITICAL, "HOST_DOWN", "CAM-03 не отвечает на ping 3 минуты", minutesAgo = 12),
        demoAlert("a-2", "mfp-ceh", AlertSeverity.WARNING, "TONER_LOW", "MFP-CEH: чёрный тонер 9%", minutesAgo = 95),
        demoAlert("a-3", "sw-acc-03", AlertSeverity.WARNING, "LINK_DOWN", "SW-ACC-03 gi1/0/7: линк упал", minutesAgo = 300)
            .copy(isResolved = true, resolvedAt = Instant.now().minusSeconds(280L * 60)),
    )
    private val alertLock = Mutex()
    private var liveAlertSent = false

    private fun demoAlert(
        id: String, deviceId: String, severity: AlertSeverity, type: String, message: String, minutesAgo: Long,
    ) = Alert(id, deviceId, null, severity, type, message, false, Instant.now().minusSeconds(minutesAgo * 60), null)

    override suspend fun alerts(): List<Alert> {
        simulateNetwork()
        return alertLock.withLock { alerts.toList() }
    }

    override suspend fun resolveAlert(alertId: String): Alert {
        simulateNetwork()
        val resolved = alertLock.withLock {
            val i = alerts.indexOfFirst { it.id == alertId }
            if (i < 0) notFound("Событие")
            alerts[i].copy(isResolved = true, resolvedAt = alerts[i].resolvedAt ?: Instant.now()).also { alerts[i] = it }
        }
        demoEvents.emit(LiveEvent.AlertRaised(resolved))
        return resolved
    }

    override suspend fun switchTraffic(switchId: String, hours: Int): List<PortLoad> {
        simulateNetwork()
        return DemoTraffic.summary(currentPorts(switchOrThrow(switchId)), hours)
    }

    override suspend fun portTraffic(switchId: String, portId: String, hours: Int, points: Int): TrafficSeries {
        simulateNetwork()
        return DemoTraffic.series(portOrThrow(switchOrThrow(switchId), portId), hours, points)
    }

    override suspend fun credentials(): List<Credential> = DemoCredentials.list()

    override suspend fun saveCredential(id: String?, draft: CredentialDraft): Credential = DemoCredentials.save(id, draft)

    override suspend fun deleteCredential(id: String) = DemoCredentials.delete(id)

    // --- Map editor: inventory only, kept in memory until the app is closed. ---

    /** Plans picked on the phone, by their demo:// url. */
    private val planImages = HashMap<String, ByteArray>()
    private var planVersion = 0
    private var createdDevices = 0
    private var createdFloors = 0

    override suspend fun planBytes(url: String): ByteArray = planImages[url] ?: notFound("План")

    override suspend fun ensureFloor(plan: FloorPlan): FloorPlan = plans[plan.id] ?: plans.getValue(floors.first().id)

    private fun floorOrFirst(id: String) = if (id in plans) id else floors.first().id

    /** The floor new devices from a scan go to. */
    private val firstPlan get() = plans.getValue(floorOrFirst(FLOOR_ID))

    private fun planOf(plan: FloorPlan) = plans[plan.id] ?: notFound("Этаж")

    private fun setPlan(plan: FloorPlan): FloorPlan {
        plans = plans + (plan.id to plan)
        return plan
    }

    override suspend fun createDevice(floorId: String, draft: DeviceDraft, x: Float, y: Float): Device {
        simulateNetwork()
        return inventoryLock.withLock {
            checkName(draft.name, null)
            val device = Device(
                id = "new-${++createdDevices}", name = draft.name.trim(), type = draft.type, vendor = draft.vendor,
                model = draft.model, ip = draft.ip, mac = null, floorId = floorOrFirst(floorId), x = x, y = y,
                isOnline = draft.type == DeviceType.SOCKET || draft.type == DeviceType.PATCH_PANEL,
                vlanId = draft.ip?.let { ip -> vlans.firstOrNull { ScanRanges.contains(it.subnet, ip) }?.id },
                hostId = draft.hostId,
            )
            setDevices(devices + device)
            device
        }
    }

    override suspend fun updateDevice(id: String, draft: DeviceDraft): Device {
        simulateNetwork()
        return inventoryLock.withLock {
            val current = byId[id] ?: notFound("Устройство")
            checkName(draft.name, id)
            val updated = current.copy(
                name = draft.name.trim(), type = draft.type, ip = draft.ip, vendor = draft.vendor, model = draft.model,
                hostId = draft.hostId,
            )
            setDevices(devices.map { if (it.id == id) updated else it })
            updated
        }
    }

    override suspend fun moveDevice(id: String, floorId: String, x: Float, y: Float) {
        inventoryLock.withLock {
            if (id !in byId) notFound("Устройство")
            setDevices(devices.map { if (it.id == id) it.copy(x = x, y = y, floorId = floorOrFirst(floorId)) else it })
        }
    }

    override suspend fun deleteDevice(id: String) {
        simulateNetwork()
        inventoryLock.withLock {
            if (id !in byId) notFound("Устройство")
            setDevices(devices.filter { it.id != id })
            connections = connections.filter { it.deviceAId != id && it.deviceBId != id }
        }
    }

    override suspend fun bulkUpdateDevices(ids: List<String>, change: DeviceBulkChange): Int {
        simulateNetwork()
        return inventoryLock.withLock {
            val chosen = ids.toSet()
            if (change.hostId != null && change.hostId in chosen) throw UserFacingException("Устройство не может быть хостом самому себе.")
            setDevices(
                devices.map { d ->
                    if (d.id !in chosen) d else d.copy(
                        type = change.type ?: d.type,
                        floorId = change.floorId?.let(::floorOrFirst) ?: d.floorId,
                        vendor = change.vendor ?: d.vendor,
                        hostId = change.hostId ?: d.hostId,
                    )
                },
            )
            devices.count { it.id in chosen }
        }
    }

    override suspend fun bulkDeleteDevices(ids: List<String>): Int {
        simulateNetwork()
        return inventoryLock.withLock {
            val chosen = ids.toSet()
            val count = devices.count { it.id in chosen }
            setDevices(devices.filter { it.id !in chosen }.map { if (it.hostId in chosen) it.copy(hostId = null) else it })
            connections = connections.filter { it.deviceAId !in chosen && it.deviceBId !in chosen }
            count
        }
    }

    override suspend fun linkDevices(deviceAId: String, deviceBId: String, cableType: String): Connection {
        simulateNetwork()
        return inventoryLock.withLock {
            if (deviceAId == deviceBId) throw UserFacingException("Нельзя соединить устройство с самим собой.")
            if (deviceAId !in byId || deviceBId !in byId) notFound("Устройство")
            if (connections.any { setOf(it.deviceAId, it.deviceBId) == setOf(deviceAId, deviceBId) }) {
                throw UserFacingException("Эти устройства уже соединены.")
            }
            c(deviceAId, deviceBId, cableType).also { connections = connections + it }
        }
    }

    override suspend fun deleteConnection(id: String) {
        simulateNetwork()
        inventoryLock.withLock { connections = connections.filter { it.id != id } }
    }

    override suspend fun routeConnection(id: String, waypoints: List<Waypoint>): Connection = inventoryLock.withLock {
        val link = connections.firstOrNull { it.id == id } ?: notFound("Кабель")
        val updated = link.copy(waypoints = waypoints.take(200))
        connections = connections.map { if (it.id == id) updated else it }
        updated
    }

    override suspend fun createFloor(draft: FloorDraft): FloorInfo {
        simulateNetwork()
        return inventoryLock.withLock {
            val info = FloorInfo("floor-new-${++createdFloors}", draft.building.trim(), draft.number, draft.name?.trim())
            floors = (floors + info).sortedBy { it.number }
            setPlan(FloorPlan(info.id, titleOf(info), 1600f, 1000f, 25f, emptyList()))
            info
        }
    }

    override suspend fun renameFloor(id: String, draft: FloorDraft): FloorInfo {
        simulateNetwork()
        return inventoryLock.withLock {
            val info = FloorInfo(id, draft.building.trim(), draft.number, draft.name?.trim()?.takeIf { it.isNotEmpty() })
            if (floors.none { it.id == id }) notFound("Этаж")
            floors = floors.map { if (it.id == id) info else it }.sortedBy { it.number }
            setPlan(plans.getValue(id).copy(title = titleOf(info)))
            info
        }
    }

    override suspend fun deleteFloor(id: String) {
        simulateNetwork()
        inventoryLock.withLock {
            if (floors.none { it.id == id }) notFound("Этаж")
            val count = devices.count { it.floorId == id }
            if (count > 0) throw UserFacingException("На этаже $count устройств: перенесите их на другой этаж или удалите.")
            if (floors.size == 1) throw UserFacingException("Это единственный этаж.")
            plans[id]?.imageUrl?.let(planImages::remove)
            floors = floors.filter { it.id != id }
            plans = plans - id
        }
    }

    override suspend fun uploadPlan(plan: FloorPlan, file: PlanFile): FloorPlan {
        simulateNetwork()
        return inventoryLock.withLock {
            val current = planOf(plan)
            current.imageUrl?.let(planImages::remove)
            val url = "demo://plan/${++planVersion}"
            planImages[url] = file.bytes
            val rect = current.imageRect ?: run {
                val w = current.widthPx
                PlanRect(0f, 0f, w, w * file.height / file.width.coerceAtLeast(1))
            }
            current.copy(
                imageUrl = url, imageRect = rect,
                widthPx = maxOf(current.widthPx, rect.right), heightPx = maxOf(current.heightPx, rect.bottom),
            ).let(::setPlan)
        }
    }

    override suspend fun removePlan(plan: FloorPlan): FloorPlan = inventoryLock.withLock {
        val current = planOf(plan)
        current.imageUrl?.let(planImages::remove)
        setPlan(current.copy(imageUrl = null, imageRect = null))
    }

    override suspend fun placePlan(plan: FloorPlan, rect: PlanRect): FloorPlan = inventoryLock.withLock {
        val current = planOf(plan)
        setPlan(
            current.copy(
                imageRect = rect, widthPx = maxOf(current.widthPx, rect.right), heightPx = maxOf(current.heightPx, rect.bottom),
            ),
        )
    }

    override suspend fun saveDrawings(plan: FloorPlan, drawings: List<Drawing>): FloorPlan = inventoryLock.withLock {
        setPlan(planOf(plan).copy(drawings = drawings))
    }

    private fun setDevices(list: List<Device>) {
        devices = list
        byId = list.associateBy { it.id }
    }

    private fun checkName(name: String, exceptId: String?) {
        if (name.isBlank()) throw UserFacingException("Укажите имя устройства.")
        if (devices.any { it.id != exceptId && it.name.equals(name.trim(), ignoreCase = true) }) {
            throw UserFacingException("Устройство с именем ${name.trim()} уже есть.")
        }
    }

    /** Like the server's emulator: the answer follows whether the device with that IP is online. */
    override suspend fun diagnostic(switchId: String, kind: DiagnosticKind, target: String): DiagnosticResult {
        val switch = switchOrThrow(switchId)
        delay(if (kind == DiagnosticKind.PING) 1_200 else 2_000)
        val reachable = devices.firstOrNull { it.ip == target }?.isOnline == true
        val result = when (kind) {
            DiagnosticKind.PING -> CommandTranslator.ping(switch.vendor, target, PING_COUNT, reachable)
            DiagnosticKind.TRACEROUTE -> {
                val core = devices.first { it.id == CORE_SWITCH_ID }.ip
                val hops = listOfNotNull(core.takeIf { it != switch.ip && it != target }, target)
                CommandTranslator.traceroute(switch.vendor, target, hops, reachable)
            }
        }
        DemoRunbooks.recordSwitch(
            if (kind == DiagnosticKind.PING) "Ping с коммутатора" else "Traceroute с коммутатора",
            listOf("device" to switch.name, "target" to target),
            listOf(result.command, result.output),
        )
        return result
    }

    override suspend fun runbooks(): List<Runbook> {
        simulateNetwork()
        return DemoRunbooks.catalog
    }

    override suspend fun runRunbook(runbook: Runbook, arguments: Map<String, String>, authMethod: AuthMethod): RunbookRun =
        DemoRunbooks.run(runbook, arguments, authMethod)

    override suspend fun audit(kind: AuditKind?): List<AuditEntry> {
        simulateNetwork()
        return DemoRunbooks.audit(kind)
    }

    // --- Network discovery: nothing is sent anywhere; the scan answers from the demo inventory. ---

    private val inventoryLock = Mutex()

    override suspend fun startScan(subnets: List<String>, community: String): NetworkScan {
        simulateNetwork()
        val (world, links) = scanWorld()
        return annotate(DemoDiscovery.start(subnets, community, world, links))
    }

    override suspend fun scan(id: String): NetworkScan = annotate(DemoDiscovery.get(id))

    override suspend fun latestScan(): NetworkScan? = DemoDiscovery.latest()?.let(::annotate)

    /** Hosts answering on the demo network and the links their switches' tables show. */
    private fun scanWorld(): Pair<List<DemoDiscovery.Host>, List<FoundLink>> {
        val snmpTypes = setOf(DeviceType.SWITCH, DeviceType.SERVER, DeviceType.PRINTER, DeviceType.CAMERA, DeviceType.ACCESS_POINT)
        val world = devices.filter { it.ip != null && it.isOnline && it.type != DeviceType.SOCKET && it.type != DeviceType.PATCH_PANEL }.map { d ->
            DemoDiscovery.Host(
                FoundDevice(
                    d.ip!!, d.name, d.mac, d.vendor, d.model, d.type, null, d.type in snmpTypes, null, null,
                    openPorts = DemoDiscovery.openPorts(d.type),
                ),
                community = if (d.type in snmpTypes) "public" else null,
            )
        }.toMutableList()
        val links = ArrayList<FoundLink>()
        val switches = devices.filter { it.type == DeviceType.SWITCH }
        val ports = switches.associate { it.id to currentPorts(it) }
        for (sw in switches) {
            for (port in ports.getValue(sw.id)) {
                val peer = port.connectedDeviceName?.let { name -> devices.firstOrNull { it.name == name } } ?: continue
                if (peer.type == DeviceType.SWITCH) {
                    if (links.any { it.a.name == peer.name && it.b.name == sw.name }) continue
                    val back = ports[peer.id]?.firstOrNull { it.connectedDeviceName == sw.name }?.name.orEmpty()
                    links += FoundLink(LinkEnd(sw.name, port.name, sw.ip, null), LinkEnd(peer.name, back, peer.ip, null), LinkSource.LLDP, false)
                } else {
                    links += FoundLink(LinkEnd(sw.name, port.name, sw.ip, null), LinkEnd(peer.name, "", peer.ip, null), LinkSource.FDB, false)
                }
            }
        }

        // Devices the inventory does not know yet: a new access switch behind the core with a printer and a camera,
        // and a PC without SNMP on a free port of an access switch. Once added, they answer as inventory devices.
        val known = devices.mapNotNull { it.ip }.toSet()
        val core = byId[CORE_SWITCH_ID]
        val sw4 = LinkEnd("SW-ACC-04", "te1/0/1", "10.99.0.4", null)
        fun extra(device: FoundDevice, community: String?, vararg extraLinks: FoundLink?) {
            if (device.ip in known) return
            world += DemoDiscovery.Host(device, community)
            links += extraLinks.filterNotNull()
        }
        val coreUplink = core?.let { c -> ports[c.id]?.firstOrNull { it.isUplink && it.connectedDeviceName == null } }
        extra(
            FoundDevice("10.99.0.4", "SW-ACC-04", "A8:F9:4B:7E:00:04", "Eltex", "MES2324P", DeviceType.SWITCH,
                "MES2324P AC 28-port 1G/10G Managed Switch", true, null, null, openPorts = DemoDiscovery.openPorts(DeviceType.SWITCH)),
            "public",
            if (core != null && coreUplink != null) {
                FoundLink(LinkEnd(core.name, coreUplink.name, core.ip, null), sw4, LinkSource.LLDP, false)
            } else {
                null
            },
        )
        extra(
            FoundDevice("10.40.0.57", "MFP-SKLAD", "00:17:C8:5A:00:57", "Kyocera", "ECOSYS M2540dn", DeviceType.PRINTER,
                "KYOCERA Document Solutions Printing System", true, null, null, openPorts = DemoDiscovery.openPorts(DeviceType.PRINTER)),
            "public",
            FoundLink(sw4.copy(port = "gi1/0/3"), LinkEnd("MFP-SKLAD", "", "10.40.0.57", null), LinkSource.FDB, false),
        )
        extra(
            FoundDevice("10.30.0.14", "CAM-04", "44:47:CC:28:00:14", "Hikvision", "DS-2CD2143G2-I", DeviceType.CAMERA,
                "Hikvision IP camera", true, null, null, openPorts = DemoDiscovery.openPorts(DeviceType.CAMERA)),
            "public",
            FoundLink(sw4.copy(port = "gi1/0/7"), LinkEnd("CAM-04", "", "10.30.0.14", null), LinkSource.FDB, false),
        )
        val access = byId["sw-acc-03"]
        val freePort = access?.let { a -> ports[a.id]?.firstOrNull { !it.isUplink && it.connectedDeviceName == null } }
        extra(
            // ICMP blocked by the Windows firewall: found by its open ports, recognised as a PC by them and the MAC.
            FoundDevice("10.20.0.77", null, "3C:52:82:AA:00:77", "HP", null, DeviceType.WORKSTATION,
                "Тип: Windows: открыт RPC, SMB, RDP", false, null, null, seenBy = "tcp",
                openPorts = DemoDiscovery.openPorts(DeviceType.WORKSTATION)),
            null,
            if (access != null && freePort != null) {
                FoundLink(LinkEnd(access.name, freePort.name, access.ip, null), LinkEnd("", "", "10.20.0.77", null), LinkSource.FDB, false)
            } else {
                null
            },
        )
        return world to links
    }

    /** Marks what the inventory already has, as of now: added devices and links turn "known" right after an import. */
    private fun annotate(scan: NetworkScan): NetworkScan {
        val current = devices
        val byIp = current.filter { it.ip != null }.associateBy { it.ip }
        val pairs = knownPairs()
        fun resolve(end: LinkEnd) = byIp[end.ip] ?: current.firstOrNull { it.name.equals(end.name, ignoreCase = true) }
        return scan.copy(
            devices = scan.devices.map { d -> byIp[d.ip]?.let { d.copy(inventoryId = it.id, inventoryName = it.name) } ?: d },
            links = scan.links.map { link ->
                val a = resolve(link.a)
                val b = resolve(link.b)
                link.copy(
                    a = link.a.copy(inventoryId = a?.id),
                    b = link.b.copy(inventoryId = b?.id, name = link.b.name.ifBlank { b?.name ?: link.b.ip.orEmpty() }),
                    known = a != null && b != null && setOf(a.id, b.id) in pairs,
                )
            },
        )
    }

    /** Device pairs already cabled to each other, also through sockets and patch panels. */
    private fun knownPairs(): Set<Set<String>> = devices.filter { it.type == DeviceType.SWITCH }.flatMap { sw ->
        currentPorts(sw).mapNotNull { p -> p.connectedDeviceName?.let { name -> devices.firstOrNull { it.name == name } } }
            .map { setOf(sw.id, it.id) }
    }.toSet() + connections.map { setOf(it.deviceAId, it.deviceBId) }

    override suspend fun importScan(id: String, ips: List<String>, withLinks: Boolean): ImportReport {
        simulateNetwork()
        val scan = annotate(DemoDiscovery.finished(id))
        val report = inventoryLock.withLock {
            val found = scan.devices.associateBy { it.ip }
            val created = ArrayList<Device>()
            val skipped = ArrayList<String>()
            val selected = HashSet<String>()
            val names = devices.mapTo(HashSet()) { it.name.lowercase() }
            for (ip in ips.distinct()) {
                val d = found[ip]
                if (d == null) {
                    skipped += "$ip: нет в результатах сканирования"
                    continue
                }
                val existing = devices.firstOrNull { it.ip == ip }
                if (existing != null) {
                    selected += existing.id
                    continue
                }
                var name = d.name ?: "HOST-" + ip.replace('.', '-')
                val base = name
                var n = 2
                while (!names.add(name.lowercase())) name = "$base-${n++}"
                val device = Device(
                    id = "found-" + ip.replace('.', '-'), name = name, type = d.type, vendor = d.vendor, model = d.model,
                    ip = ip, mac = d.mac, floorId = floorOrFirst(FLOOR_ID), x = 0f, y = 0f, isOnline = true,
                    vlanId = vlans.firstOrNull { ScanRanges.contains(it.subnet, ip) }?.id,
                )
                created += device
                selected += device.id
            }
            val all = devices + created
            fun resolve(end: LinkEnd) = all.firstOrNull { end.ip != null && it.ip == end.ip }
                ?: all.firstOrNull { it.name.equals(end.name, ignoreCase = true) }
            val pairs = knownPairs().toMutableSet()
            val added = ArrayList<Connection>()
            val addedLabels = ArrayList<String>()
            if (withLinks) {
                for (link in scan.links) {
                    val a = resolve(link.a) ?: continue
                    val b = resolve(link.b) ?: continue
                    if (a.id == b.id || (a.id !in selected && b.id !in selected) || !pairs.add(setOf(a.id, b.id))) continue
                    added += c(a.id, b.id, if (a.type == DeviceType.SWITCH && b.type == DeviceType.SWITCH) "OM3" else "Cat6", "авто: ${link.source.label}")
                    addedLabels += "${a.name} ${link.a.port.ifBlank { "—" }} ↔ ${b.name} ${link.b.port.ifBlank { "—" }}"
                }
            }
            placeNear(created, all, added)
            devices = devices + created
            connections = connections + added
            byId = devices.associateBy { it.id }
            ImportReport(created.map { it.name }, addedLabels, skipped)
        }
        DemoRunbooks.recordNetwork(
            action = "discovery_import",
            title = "Добавление найденных устройств",
            arguments = listOf("ips" to ips.joinToString(), "links" to if (withLinks) "да" else "нет"),
            stdout = (report.created.map { "+ $it" } + report.links.map { "+ связь $it" }).joinToString("\n")
                .ifEmpty { "Новых устройств и связей нет" },
            stderr = report.skipped.joinToString("\n"),
            durationMs = null,
        )
        return report
    }

    /** Next to a linked device that already has a place, otherwise in a row along the bottom of the plan. */
    private fun placeNear(created: MutableList<Device>, all: List<Device>, links: List<Connection>) {
        val position = HashMap<String, Pair<Float, Float>>()
        all.filter { it !in created }.forEach { position[it.id] = it.x to it.y }
        val around = HashMap<String, Int>()
        val pending = created.map { it.id }.toMutableList()
        var progress = true
        while (pending.isNotEmpty() && progress) {
            progress = false
            for (id in pending.toList()) {
                val anchor = links.mapNotNull { l ->
                    when (id) {
                        l.deviceAId -> l.deviceBId
                        l.deviceBId -> l.deviceAId
                        else -> null
                    }
                }.firstOrNull { it in position } ?: continue
                val (ax, ay) = position.getValue(anchor)
                val k = around.getOrDefault(anchor, 0)
                around[anchor] = k + 1
                val angle = PI / 2 + k * PI / 3
                position[id] = (ax + 90f * cos(angle).toFloat()).coerceIn(30f, firstPlan.widthPx - 30f) to
                    (ay + 90f * sin(angle).toFloat()).coerceIn(30f, firstPlan.heightPx - 30f)
                pending -= id
                progress = true
            }
        }
        pending.forEachIndexed { i, id -> position[id] = (100f + i * 100f).coerceAtMost(firstPlan.widthPx - 30f) to firstPlan.heightPx - 60f }
        for (i in created.indices) {
            val (x, y) = position.getValue(created[i].id)
            created[i] = created[i].copy(x = x, y = y)
        }
    }

    /**
     * Simulated live channel: a snapshot, then a spare port losing or regaining its link every few seconds,
     * so the live indicator and port colours can be seen moving without a server.
     */
    override fun live(deviceId: String): Flow<LiveEvent> = channelFlow {
        send(LiveEvent.Connected)
        if (deviceId == ALL_DEVICES) {
            launch {
                demoEvents.collect { if (it is LiveEvent.AlertRaised || it is LiveEvent.PrinterChanged) send(it) }
            }
            // One fresh alert a little after start, so the in-app notification can be seen without a server.
            delay(20_000)
            val alert = alertLock.withLock {
                if (liveAlertSent) return@withLock null
                liveAlertSent = true
                demoAlert("a-live", "pc-ceh-02", AlertSeverity.WARNING, "LINK_DOWN", "SW-ACC-03 gi1/0/2: линк к PC-CEH-02 упал", 0)
                    .also { alerts += it }
            }
            alert?.let { demoEvents.emit(LiveEvent.AlertRaised(it)) }
            awaitCancellation()
        }
        val switch = byId[deviceId]?.takeIf { it.type == DeviceType.SWITCH } ?: run {
            awaitCancellation()
        }
        val ports = currentPorts(switch).toMutableList()
        val lock = Mutex()
        send(LiveEvent.Snapshot(deviceId, switch.isOnline, ports.toList()))
        // Changes the user makes in demo mode arrive the same way the server would push them.
        launch {
            demoEvents.collect { event ->
                if (event is LiveEvent.PortChanged && event.deviceId == deviceId) {
                    lock.withLock {
                        val i = ports.indexOfFirst { it.id == event.port.id }
                        if (i >= 0) ports[i] = event.port
                    }
                    send(event)
                }
            }
        }
        val random = Random(System.nanoTime())
        while (true) {
            delay(2_500L + random.nextLong(3_500L))
            val flipped = lock.withLock {
                val candidates = ports.indices.filter {
                    ports[it].isAdminUp && !ports[it].isUplink && ports[it].connectedDeviceName == null
                }
                if (candidates.isEmpty()) return@withLock null
                val i = candidates.random(random)
                ports[i].copy(isOperUp = !ports[i].isOperUp).also { ports[i] = it }
            } ?: continue
            send(LiveEvent.PortChanged(deviceId, flipped))
        }
    }

    /** Copper ports per model, plus four SFP uplinks on every switch. */
    private fun generatePorts(switch: Device): List<SwitchPort> {
        val copper = if (switch.model?.contains("48") == true) 48 else 24
        val cisco = switch.vendor == "Cisco"
        val uplinkPeers = neighbours(switch.id).filter { it.type == DeviceType.SWITCH }
        val accessPeers = endDevicesBehind(switch.id)
        val random = Random(switch.id.hashCode())

        val ports = ArrayList<SwitchPort>(copper + 4)
        for (n in 1..copper) {
            val name = if (cisco) "Gi1/0/$n" else "gi1/0/$n"
            val peer = accessPeers.getOrNull(n - 1)
            ports += if (peer != null) {
                val powered = peer.type == DeviceType.CAMERA || peer.type == DeviceType.ACCESS_POINT
                SwitchPort(
                    id = "${switch.id}-$n", number = n, name = name, isUplink = false,
                    mode = PortMode.ACCESS, accessVlan = peer.vlanId ?: 1, taggedVlans = emptyList(),
                    isAdminUp = true, isOperUp = peer.isOnline,
                    speedMbps = if (peer.type == DeviceType.CAMERA) 100 else 1000,
                    poeEnabled = powered, connectedDeviceName = peer.name,
                )
            } else {
                val adminUp = random.nextFloat() > 0.08f
                val operUp = adminUp && random.nextFloat() < 0.55f
                SwitchPort(
                    id = "${switch.id}-$n", number = n, name = name, isUplink = false,
                    mode = PortMode.ACCESS, accessVlan = listOf(10, 20, 30, 40).random(random), taggedVlans = emptyList(),
                    isAdminUp = adminUp, isOperUp = operUp,
                    speedMbps = if (random.nextFloat() < 0.25f) 100 else 1000,
                    poeEnabled = random.nextFloat() < 0.2f,
                    connectedDeviceName = null,
                )
            }
        }
        for (i in 1..4) {
            val n = copper + i
            val peer = uplinkPeers.getOrNull(i - 1)
            ports += SwitchPort(
                id = "${switch.id}-$n", number = n,
                name = if (cisco) "Te1/1/$i" else "te1/0/$i",
                isUplink = true, mode = PortMode.TRUNK, accessVlan = null,
                taggedVlans = vlans.map { it.id },
                isAdminUp = true, isOperUp = peer != null, speedMbps = 10_000,
                poeEnabled = false, connectedDeviceName = peer?.name,
            )
        }
        return ports
    }

    private fun neighbours(id: String): List<Device> = connections.mapNotNull { link ->
        when (id) {
            link.deviceAId -> byId[link.deviceBId]
            link.deviceBId -> byId[link.deviceAId]
            else -> null
        }
    }

    /** End devices cabled to a switch, following sockets and patch panels along the way. */
    private fun endDevicesBehind(switchId: String): List<Device> {
        val result = ArrayList<Device>()
        val visited = hashSetOf(switchId)
        val queue = ArrayDeque(neighbours(switchId).filter { it.type != DeviceType.SWITCH })
        while (queue.isNotEmpty()) {
            val device = queue.removeFirst()
            if (!visited.add(device.id)) continue
            if (device.type == DeviceType.PATCH_PANEL || device.type == DeviceType.SOCKET) {
                queue.addAll(neighbours(device.id).filter { it.id !in visited && it.type != DeviceType.SWITCH })
            } else {
                result += device
            }
        }
        return result
    }

    private suspend fun simulateNetwork() = delay(250)

    private fun d(
        id: String, name: String, type: DeviceType, vendor: String?, model: String?, ip: String?,
        x: Float, y: Float, vlan: Int? = null, online: Boolean = true, floor: String = FLOOR_ID, host: String? = null,
    ) = Device(
        id = id, name = name, type = type, vendor = vendor, model = model, ip = ip,
        mac = ip?.let { fakeMac(id) }, floorId = floor, x = x, y = y,
        isOnline = online || type == DeviceType.SOCKET || type == DeviceType.PATCH_PANEL,
        vlanId = vlan,
        hostId = host,
    )

    private fun c(a: String, b: String, cable: String = "Cat5e", label: String? = null, route: List<Waypoint> = emptyList()) =
        Connection("$a--$b", a, b, cable, label, route)

    private fun fakeMac(seed: String): String {
        val r = Random(seed.hashCode())
        return (0 until 6).joinToString(":") { i ->
            val byte = if (i == 0) 0x00 else r.nextInt(256)
            "%02X".format(byte)
        }
    }
}
