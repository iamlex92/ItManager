package com.itmanager.app.data.model

import java.time.Instant
import kotlinx.serialization.json.JsonElement

/** Domain models shared by all screens. Colors are ARGB longs so the data layer stays free of Compose. */

enum class DeviceType(val label: String) {
    SWITCH("Коммутатор"),
    WORKSTATION("Рабочее место"),
    SERVER("Сервер"),
    PRINTER("МФУ"),
    SOCKET("Розетка"),
    PATCH_PANEL("Патч-панель"),
    CAMERA("Камера"),
    ACCESS_POINT("Точка доступа"),
    ROUTER("Маршрутизатор"),
    FIREWALL("Межсетевой экран"),
    UPS("ИБП"),
    PHONE("IP-телефон"),
    /** A virtual machine or other virtual device: it has no cables and is not drawn on L1. */
    VIRTUAL("Виртуальная машина"),
    /** A 19" rack: other devices are mounted in it by unit. */
    RACK("Стойка"),
    UNKNOWN("Устройство");

    companion object {
        fun parse(raw: String?): DeviceType = entries.firstOrNull { it.name == raw?.uppercase() } ?: UNKNOWN
    }
}

data class Device(
    val id: String,
    val name: String,
    val type: DeviceType,
    val vendor: String?,
    val model: String?,
    val ip: String?,
    val mac: String?,
    val floorId: String?,
    /** Position on the floor plan, in plan pixels. */
    val x: Float,
    val y: Float,
    val isOnline: Boolean,
    val vlanId: Int?,
    /** For a virtual machine: the server it runs on. */
    val hostId: String? = null,
    /** A note shown in the device card. */
    val comment: String? = null,
    /** Mounted in this rack, from unit [rackU] (1 is the bottom) and [rackH] units high. */
    val rackId: String? = null,
    val rackU: Int? = null,
    val rackH: Int? = null,
    /** For a rack: its height in units. */
    val rackUnits: Int? = null,
    /** The switch and port this device is cabled to, through sockets and patch panels. */
    val switchId: String? = null,
    val switchPortId: String? = null,
    val switchPortName: String? = null,
    /** Free-form server-side attributes; a rack keeps its look here (see [RackStyle]). */
    val metadata: Map<String, JsonElement> = emptyMap(),
) {
    /** The unit number the device's top edge reaches in its rack. */
    val rackTop: Int? get() = rackU?.let { it + (rackH ?: 1) - 1 }

    fun toDraft() = DeviceDraft(
        name = name, type = type, ip = ip, vendor = vendor, model = model, hostId = hostId,
        comment = comment, rackId = rackId, rackU = rackU, rackH = rackH, rackUnits = rackUnits, mac = mac,
        metadata = metadata,
    )

    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return name.contains(q, ignoreCase = true) ||
            ip?.contains(q) == true ||
            mac?.replace("-", ":")?.contains(q.replace("-", ":"), ignoreCase = true) == true ||
            model?.contains(q, ignoreCase = true) == true
    }
}

data class Vlan(
    val id: Int,
    val name: String,
    /** "10.10.20.0/24", or empty for a VLAN without an IP subnet. */
    val subnet: String,
    val colorArgb: Long,
    val description: String? = null,
)

/** What the VLAN form sends; [subnet] blank clears it. */
data class VlanDraft(
    val id: Int,
    val name: String,
    val subnet: String,
    val colorArgb: Long,
    val description: String?,
) {
    /** Why the form cannot be saved, or null. */
    fun problem(): String? = idProblem() ?: nameProblem() ?: subnetProblem()

    // One per field, shown under that field.
    fun idProblem(): String? = "Номер VLAN от 1 до 4094".takeIf { id !in 1..4094 }
    fun nameProblem(): String? = "Укажите название".takeIf { name.isBlank() }
    fun subnetProblem(): String? = "Подсеть пишется так: 10.10.20.0/24".takeIf { subnet.isNotBlank() && !isCidr(subnet.trim()) }
}

private val CIDR = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)/(3[0-2]|[12]?\d)$""")

/** An IPv4 network with its prefix length: 10.10.20.0/24. */
fun isCidr(text: String): Boolean = CIDR.matches(text)

/** Colours offered for VLANs: distinct on the dark map. */
val VLAN_COLORS = listOf(
    0xFF3498DB, 0xFFE67E22, 0xFF9B59B6, 0xFFE74C3C, 0xFF2ECC71, 0xFF1ABC9C, 0xFFF1C40F, 0xFF34495E, 0xFFFF7EB6, 0xFF95A5A6,
)

/** A physical cable between two devices (L1). */
data class Connection(
    val id: String,
    val deviceAId: String,
    val deviceBId: String,
    val cableType: String,
    val label: String?,
    /** Bends of the cable on the map, from end A towards end B; empty: a straight line. */
    val waypoints: List<Waypoint> = emptyList(),
    /** The colour picked for the cable on the map; null: the map colours it by type and layer. */
    val colorArgb: Long? = null,
)

/** A bend of a cable, in map pixels of its floor; [floorId] null: the floor of end A. */
data class Waypoint(val x: Float, val y: Float, val floorId: String? = null)

enum class PortMode(val label: String) { ACCESS("Access"), TRUNK("Trunk") }

/** Port color per the spec: green 1G/10G, orange 10/100, gray down or shut. */
enum class PortStatus(val label: String) {
    GIGABIT("Линк 1/10 Гбит/с"),
    FAST("Линк 10/100 Мбит/с"),
    DOWN("Нет линка"),
    DISABLED("Выключен (shutdown)"),
}

data class SwitchPort(
    val id: String,
    val number: Int,
    val name: String,
    val isUplink: Boolean,
    val mode: PortMode,
    val accessVlan: Int?,
    val taggedVlans: List<Int>,
    val isAdminUp: Boolean,
    val isOperUp: Boolean,
    val speedMbps: Int,
    val poeEnabled: Boolean,
    val connectedDeviceName: String?,
) {
    val status: PortStatus
        get() = when {
            !isAdminUp -> PortStatus.DISABLED
            !isOperUp -> PortStatus.DOWN
            speedMbps >= 1000 -> PortStatus.GIGABIT
            else -> PortStatus.FAST
        }

    /** PoE is only worth a bolt icon when something is actually drawing power. */
    val isPoeActive: Boolean get() = poeEnabled && isOperUp

    /** VLANs the port carries: its access VLAN, or every tagged VLAN on a trunk. */
    val vlanIds: List<Int> get() = if (mode == PortMode.ACCESS) listOfNotNull(accessVlan) else taggedVlans.sorted()

    fun carries(vlanId: Int): Boolean = vlanId in vlanIds
}

/** How many ports carry each VLAN, by VLAN id ascending. Drives the legend of the VLAN port view. */
fun List<SwitchPort>.vlanUsage(): Map<Int, Int> =
    flatMap { it.vlanIds.distinct() }.groupingBy { it }.eachCount().toSortedMap()

enum class ConsumableKind {
    TONER, WASTE, DRUM, OTHER;

    companion object {
        fun parse(raw: String?): ConsumableKind = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OTHER
    }
}

/** One supply as Printer-MIB reports it. [levelPercent] is always what remains: toner left, or free room in the waste box. */
data class Consumable(
    val name: String,
    val colorArgb: Long,
    val levelPercent: Int,
    val kind: ConsumableKind = ConsumableKind.TONER,
) {
    val isLow: Boolean get() = levelPercent < LOW_LEVEL_PERCENT

    companion object {
        const val LOW_LEVEL_PERCENT = 10
    }
}

data class Printer(
    val id: String,
    val name: String,
    val ip: String?,
    val totalPages: Long,
    val consumables: List<Consumable>,
    val model: String? = null,
    val isOnline: Boolean = true,
    /** When the server last read the printer over SNMP; null if it never has. */
    val polledAt: Instant? = null,
) {
    val hasLowConsumable: Boolean get() = consumables.any { it.isLow }
}

/** Rooms are drawn as outlines on the map until the server serves real SVG plans. */
data class Room(
    val name: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class FloorPlan(
    val id: String,
    val title: String,
    val widthPx: Float,
    val heightPx: Float,
    val pixelsPerMeter: Float,
    val rooms: List<Room>,
    /** Absolute URL of the plan drawn under the devices (SVG, PNG or JPEG), or null to draw [rooms] only. */
    val imageUrl: String? = null,
    /** Where the plan image lies on the map; null: it fills the whole floor. */
    val imageRect: PlanRect? = null,
    /** Free lines drawn over the map in the editor. */
    val drawings: List<Drawing> = emptyList(),
    /** Areas outlined on the map; the ones marked as rooms collect the devices that stand in them. */
    val zones: List<Zone> = emptyList(),
    /** Room numbers in circles, captions and pointers placed on the map. */
    val annotations: List<Annotation> = emptyList(),
    /** False for the placeholder floor of a server that has none yet; the editor creates one on first save. */
    val stored: Boolean = true,
)

/** A rectangle on the map, in map pixels. */
data class PlanRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/** A free line drawn over the map: [points] are x, y pairs in map pixels. */
data class Drawing(val colorArgb: Long, val width: Float, val points: List<Float>)

/** An area outlined on the map: [points] are x, y pairs in map pixels. A room lists the devices inside it. */
data class Zone(
    val id: String,
    val name: String,
    val isRoom: Boolean,
    val colorArgb: Long,
    val points: List<Float>,
) {
    /** Even-odd ray casting: true when the point lies inside the outline. */
    fun contains(x: Float, y: Float): Boolean {
        val n = points.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = points[2 * i]
            val yi = points[2 * i + 1]
            val xj = points[2 * j]
            val yj = points[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    val area: Float
        get() {
            val n = points.size / 2
            var sum = 0f
            for (i in 0 until n) {
                val j = (i + 1) % n
                sum += points[2 * i] * points[2 * j + 1] - points[2 * j] * points[2 * i + 1]
            }
            return kotlin.math.abs(sum) / 2f
        }

    /** The middle of the bounding box, where the name is written. */
    val centre: Pair<Float, Float>
        get() {
            val xs = points.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = points.filterIndexed { i, _ -> i % 2 == 1 }
            return ((xs.minOrNull() ?: 0f) + (xs.maxOrNull() ?: 0f)) / 2f to ((ys.minOrNull() ?: 0f) + (ys.maxOrNull() ?: 0f)) / 2f
        }
}

enum class AnnotationKind(val label: String) {
    /** A room number in a circle. */
    BADGE("Номер"),
    /** A caption. */
    TEXT("Подпись"),
    /** A pointer from [Annotation.x], [Annotation.y] to [Annotation.x2], [Annotation.y2]. */
    ARROW("Стрелка");

    companion object {
        fun parse(raw: String?): AnnotationKind = entries.firstOrNull { it.name == raw?.uppercase() } ?: TEXT
    }
}

/** A mark on the map, in map pixels. [size] 1 is normal. */
data class Annotation(
    val id: String,
    val kind: AnnotationKind,
    val x: Float,
    val y: Float,
    val text: String,
    val colorArgb: Long,
    val x2: Float? = null,
    val y2: Float? = null,
    val size: Float = 1f,
) {
    /** Moved by [dx], [dy]: an arrow moves whole. */
    fun movedBy(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy, x2 = x2?.plus(dx), y2 = y2?.plus(dy))
}

/** The room a point lies in; the smallest one wins when rooms are drawn inside each other. */
fun List<Zone>.roomAt(x: Float, y: Float): Zone? = filter { it.isRoom && it.contains(x, y) }.minByOrNull { it.area }

/** A plan picked on the phone, ready to upload: PNG, JPEG or SVG, with its size in pixels. */
class PlanFile(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)

/** What the editor's device form sends: a new object on the map or changes to one. */
data class DeviceDraft(
    val name: String,
    val type: DeviceType,
    val ip: String?,
    val vendor: String?,
    val model: String?,
    val hostId: String? = null,
    val comment: String? = null,
    val rackId: String? = null,
    val rackU: Int? = null,
    val rackH: Int? = null,
    val rackUnits: Int? = null,
    /** aa:bb:cc:dd:ee:ff; null clears it. */
    val mac: String? = null,
    /** Sent whole: the server replaces the dictionary, so keep what the device already has. */
    val metadata: Map<String, JsonElement>? = null,
)

private val MAC = Regex("^[0-9a-fA-F]{2}([:-]?[0-9a-fA-F]{2}){5}$")

/** A MAC address the server accepts: aa:bb:cc:dd:ee:ff, with dashes or without separators. */
fun isMac(text: String): Boolean = MAC.matches(text.trim())

/** One change applied to many devices at once; a null field stays as it is on each. */
data class DeviceBulkChange(
    val type: DeviceType? = null,
    val floorId: String? = null,
    val vendor: String? = null,
    val hostId: String? = null,
) {
    val isEmpty get() = type == null && floorId == null && vendor == null && hostId == null
}

enum class MapLayer(val short: String, val title: String) {
    L1("L1", "Кабели"),
    L2("L2", "VLAN"),
    L3("L3", "Подсети"),
}

/** Everything the map screen needs for one floor. */
data class FloorMap(
    val plan: FloorPlan,
    /** Devices on this floor. */
    val devices: List<Device>,
    /** Cables with at least one end on this floor. */
    val connections: List<Connection>,
    val vlans: List<Vlan>,
    /** Every floor of the building, bottom to top. */
    val floors: List<FloorInfo> = emptyList(),
    /** The far ends of cables that go to another floor, by id. */
    val remoteDevices: Map<String, Device> = emptyMap(),
)

/** A floor in the switcher: [name] is what people call it, the number orders the floors. */
data class FloorInfo(val id: String, val building: String, val number: Int, val name: String?) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: "Этаж $number"
}

/** What the floor settings send: a new floor or changes to one. */
data class FloorDraft(val building: String, val number: Int, val name: String?)

data class ServerHealth(
    val status: String,
    val version: String?,
    val halMode: String?,
    val deviceWritesEnabled: Boolean?,
    /** Null on a server from before credential encryption. */
    val secretsEncrypted: Boolean? = null,
    val telegramAlerts: Boolean? = null,
    /** "emulator" or "real" (AD over LDAPS, folder rights over WinRM). */
    val runbookMode: String? = null,
    val runbookWritesEnabled: Boolean? = null,
    /** "real": ping and SNMP polling of printers and ports; "emulator": no polling, made-up figures. */
    val monitorMode: String? = null,
)

fun parseColorHex(hex: String?, fallback: Long = 0xFF3498DB): Long {
    val digits = hex?.trim()?.removePrefix("#") ?: return fallback
    return when (digits.length) {
        6 -> digits.toLongOrNull(16)?.let { 0xFF000000 or it } ?: fallback
        8 -> digits.toLongOrNull(16) ?: fallback
        else -> fallback
    }
}

/** Height of a rack that did not say how many units it has. */
const val DEFAULT_RACK_UNITS = 42
