package com.itmanager.app.data.model

import java.time.Instant

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
) {
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
    val subnet: String,
    val colorArgb: Long,
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
)

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
    L3("L3", "IP-подсети"),
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
)

fun parseColorHex(hex: String?, fallback: Long = 0xFF3498DB): Long {
    val digits = hex?.trim()?.removePrefix("#") ?: return fallback
    return when (digits.length) {
        6 -> digits.toLongOrNull(16)?.let { 0xFF000000 or it } ?: fallback
        8 -> digits.toLongOrNull(16) ?: fallback
        else -> fallback
    }
}
