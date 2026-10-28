package com.itmanager.app.data.model

/** How the device list is split into sections. */
enum class DeviceGrouping(val label: String) {
    TYPE("По типу"),
    FLOOR("По этажу"),
    ROOM("По помещению"),
}

/** One section of the device list: a header and the devices under it, by name. */
data class DeviceGroup(val key: String, val title: String, val devices: List<Device>)

/** Passive cabling: it never answers, so it never counts as offline. */
val Device.isPassive: Boolean
    get() = type == DeviceType.SOCKET || type == DeviceType.PATCH_PANEL || type == DeviceType.RACK

/** Active equipment that does not answer right now. */
val Device.isDown: Boolean get() = !isOnline && !isPassive

/**
 * Splits [devices] into sections. [floors] orders the floor sections bottom to top; [zones] (by floor id) names
 * the rooms for [DeviceGrouping.ROOM]. Devices with no floor (virtual machines, not placed yet) come last.
 */
fun groupDevices(
    devices: List<Device>,
    by: DeviceGrouping,
    floors: List<FloorInfo>,
    zones: Map<String, List<Zone>> = emptyMap(),
): List<DeviceGroup> {
    val byName = compareBy<Device> { it.name.lowercase() }
    if (by == DeviceGrouping.TYPE) {
        return devices.groupBy { it.type }.entries
            .sortedBy { it.key.ordinal }
            .map { (type, list) -> DeviceGroup(type.name, type.label, list.sortedWith(byName)) }
    }
    val floorOrder = floors.withIndex().associate { (i, f) -> f.id to i }
    val floorTitle = floors.associate { it.id to it.title }
    val (placed, loose) = devices.partition { it.floorId != null && it.floorId in floorOrder }
    val groups = placed.groupBy { it.floorId!! }.entries
        .sortedBy { floorOrder[it.key] }
        .flatMap { (floorId, list) ->
            val floor = floorTitle[floorId].orEmpty()
            if (by == DeviceGrouping.FLOOR) {
                listOf(DeviceGroup(floorId, floor, list.sortedWith(byName)))
            } else {
                val rooms = zones[floorId].orEmpty()
                list.groupBy { rooms.roomAt(it.x, it.y)?.name }.entries
                    // Named rooms by name, then whatever stands in no room.
                    .sortedWith(compareBy<Map.Entry<String?, List<Device>>>({ it.key == null }, { it.key?.lowercase() }))
                    .map { (room, inRoom) ->
                        DeviceGroup("$floorId/${room.orEmpty()}", "$floor · ${room ?: "вне помещений"}", inRoom.sortedWith(byName))
                    }
            }
        }
    return if (loose.isEmpty()) groups else groups + DeviceGroup("none", "Не на карте", loose.sortedWith(byName))
}
