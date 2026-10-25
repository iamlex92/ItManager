package com.itmanager.app.data.source

import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Consumable
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.FloorPlan
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.Room
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * A small made-up factory floor: server room, IT office, accounting and a workshop.
 * Lets every screen work without a server and without touching production hardware.
 */
object DemoDataSource : DcimDataSource {

    private const val FLOOR_ID = "floor-1"

    private val plan = FloorPlan(
        id = FLOOR_ID,
        title = "АБК и цех №1 · этаж 1",
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
    )

    private val vlans = listOf(
        Vlan(10, "Бухгалтерия", "10.10.0.0/24", 0xFF0A84FF),
        Vlan(20, "Производство", "10.20.0.0/23", 0xFFFF9F0A),
        Vlan(30, "Видеонаблюдение", "10.30.0.0/24", 0xFFBF5AF2),
        Vlan(40, "Принтеры", "10.40.0.0/24", 0xFF64D2FF),
        Vlan(50, "ИТ", "10.50.0.0/24", 0xFF30D158),
        Vlan(99, "Управление", "10.99.0.0/24", 0xFF8E8E93),
    )

    private val devices = listOf(
        d("sw-core-01", "SW-CORE-01", DeviceType.SWITCH, "Cisco", "C9300-24P", "10.99.0.1", 120f, 110f, vlan = 99),
        d("srv-01", "SRV-01", DeviceType.SERVER, "HPE", "ProLiant DL380 Gen11", "10.50.0.10", 140f, 230f, vlan = 50),
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
    )

    private val connections = listOf(
        c("sw-core-01", "srv-01", "Cat6"),
        c("sw-core-01", "sw-acc-02", "OM3", "Магистраль АБК"),
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
            "mfp-buh", "MFP-BUH · Kyocera M8130cidn", "10.40.0.21", 48_231,
            listOf(
                Consumable("Чёрный", 0xFF1C1C1E, 64),
                Consumable("Голубой", 0xFF32ADE6, 7),
                Consumable("Пурпурный", 0xFFFF2D55, 38),
                Consumable("Жёлтый", 0xFFFFD60A, 81),
                Consumable("Барабан", 0xFF8E8E93, 55),
            ),
        ),
        Printer(
            "mfp-ceh", "MFP-CEH · HP LaserJet M428", "10.40.0.31", 120_554,
            listOf(Consumable("Чёрный", 0xFF1C1C1E, 9), Consumable("Барабан", 0xFF8E8E93, 61)),
        ),
        Printer(
            "mfp-adm", "MFP-ADM · Kyocera TASKalfa 2553ci", "10.40.0.11", 211_907,
            listOf(
                Consumable("Чёрный", 0xFF1C1C1E, 47),
                Consumable("Голубой", 0xFF32ADE6, 72),
                Consumable("Пурпурный", 0xFFFF2D55, 69),
                Consumable("Жёлтый", 0xFFFFD60A, 58),
                Consumable("Барабан", 0xFF8E8E93, 34),
            ),
        ),
        Printer(
            "prn-sklad", "PRN-SKLAD · HP LaserJet M404", "10.40.0.41", 33_018,
            listOf(Consumable("Чёрный", 0xFF1C1C1E, 91), Consumable("Барабан", 0xFF8E8E93, 88)),
        ),
    )

    private val byId = devices.associateBy { it.id }

    override suspend fun floorMap(): FloorMap {
        simulateNetwork()
        return FloorMap(plan, devices, connections, vlans)
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
        val switch = byId[deviceId]?.takeIf { it.type == DeviceType.SWITCH } ?: notFound("Коммутатор")
        return generatePorts(switch)
    }

    override suspend fun printers(): List<Printer> {
        simulateNetwork()
        return printers
    }

    override suspend fun planSvg(url: String): String = notFound("План")

    /**
     * Simulated live channel: a snapshot, then a spare port losing or regaining its link every few seconds,
     * so the live indicator and port colours can be seen moving without a server.
     */
    override fun live(deviceId: String): Flow<LiveEvent> = flow {
        emit(LiveEvent.Connected)
        val switch = byId[deviceId]?.takeIf { it.type == DeviceType.SWITCH } ?: run {
            awaitCancellation()
        }
        val ports = generatePorts(switch).toMutableList()
        emit(LiveEvent.Snapshot(deviceId, switch.isOnline, ports.toList()))
        val random = Random(System.nanoTime())
        while (true) {
            delay(2_500L + random.nextLong(3_500L))
            val candidates = ports.indices.filter { ports[it].isAdminUp && !ports[it].isUplink && ports[it].connectedDeviceName == null }
            if (candidates.isEmpty()) continue
            val i = candidates.random(random)
            val flipped = ports[i].copy(isOperUp = !ports[i].isOperUp)
            ports[i] = flipped
            emit(LiveEvent.PortChanged(deviceId, flipped))
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
        x: Float, y: Float, vlan: Int? = null, online: Boolean = true,
    ) = Device(
        id = id, name = name, type = type, vendor = vendor, model = model, ip = ip,
        mac = ip?.let { fakeMac(id) }, floorId = FLOOR_ID, x = x, y = y,
        isOnline = online || type == DeviceType.SOCKET || type == DeviceType.PATCH_PANEL,
        vlanId = vlan,
    )

    private fun c(a: String, b: String, cable: String = "Cat5e", label: String? = null) =
        Connection("$a--$b", a, b, cable, label)

    private fun fakeMac(seed: String): String {
        val r = Random(seed.hashCode())
        return (0 until 6).joinToString(":") { i ->
            val byte = if (i == 0) 0x00 else r.nextInt(256)
            "%02X".format(byte)
        }
    }
}
