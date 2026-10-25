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
)

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
}

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
    /** Absolute URL of the SVG plan drawn under the devices, or null to draw [rooms] only. */
    val imageUrl: String? = null,
)

enum class MapLayer(val short: String, val title: String) {
    L1("L1", "Кабели"),
    L2("L2", "VLAN"),
    L3("L3", "IP-подсети"),
}

/** Everything the map screen needs for one floor. */
data class FloorMap(
    val plan: FloorPlan,
    val devices: List<Device>,
    val connections: List<Connection>,
    val vlans: List<Vlan>,
)

data class ServerHealth(
    val status: String,
    val version: String?,
    val halMode: String?,
    val deviceWritesEnabled: Boolean?,
)

fun parseColorHex(hex: String?, fallback: Long = 0xFF3498DB): Long {
    val digits = hex?.trim()?.removePrefix("#") ?: return fallback
    return when (digits.length) {
        6 -> digits.toLongOrNull(16)?.let { 0xFF000000 or it } ?: fallback
        8 -> digits.toLongOrNull(16) ?: fallback
        else -> fallback
    }
}
