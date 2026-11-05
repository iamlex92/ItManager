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

/**
 * Where a device is and what it hangs off, for its list row: room, rack and unit, switch port, host.
 * [byId] holds every device (racks, switches and hosts are looked up in it); [zones] are the rooms by floor id.
 * Null when nothing is known.
 */
fun devicePlace(device: Device, byId: Map<String, Device>, zones: Map<String, List<Zone>> = emptyMap()): String? {
    val room = device.floorId?.let { zones[it] }?.roomAt(device.x, device.y)?.name
    val rack = device.rackId?.let { id ->
        val unit = device.rackU?.let { u -> "U$u" + if ((device.rackH ?: 1) > 1) "–${device.rackTop}" else "" }
        listOfNotNull(byId[id]?.name ?: "Стойка", unit).joinToString(" ")
    }
    val port = device.switchPortId?.let {
        val switchName = device.switchId?.let { id -> byId[id]?.name } ?: "коммутатор"
        "порт $switchName ${device.switchPortName.orEmpty()}".trim()
    }
    val host = device.hostId?.let { id -> byId[id]?.name?.let { "на хосте $it" } }
    return listOfNotNull(room, rack, port, host).joinToString(" · ").ifEmpty { null }
}
