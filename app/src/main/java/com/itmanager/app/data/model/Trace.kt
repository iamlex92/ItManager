package com.itmanager.app.data.model

/**
 * The physical path from a device to the switch that serves it,
 * e.g. PC -> socket -> patch panel -> switch. Returns the connection ids along the path,
 * or an empty list when the device is a switch itself or is not cabled to one.
 */
fun traceToSwitch(
    startId: String,
    devices: Map<String, Device>,
    connections: List<Connection>,
): List<String> {
    if (devices[startId]?.type == DeviceType.SWITCH) return emptyList()

    val adjacency = HashMap<String, MutableList<Connection>>()
    for (c in connections) {
        adjacency.getOrPut(c.deviceAId) { mutableListOf() }.add(c)
        adjacency.getOrPut(c.deviceBId) { mutableListOf() }.add(c)
    }

    // Breadth-first, so the nearest switch wins when a device has several paths.
    val cameFrom = HashMap<String, Connection>()
    val visited = hashSetOf(startId)
    val queue = ArrayDeque<String>().apply { add(startId) }
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (current != startId && devices[current]?.type == DeviceType.SWITCH) {
            val path = ArrayList<String>()
            var node = current
            while (node != startId) {
                val link = cameFrom.getValue(node)
                path.add(link.id)
                node = if (link.deviceAId == node) link.deviceBId else link.deviceAId
            }
            return path.asReversed()
        }
        for (link in adjacency[current].orEmpty()) {
            val next = if (link.deviceAId == current) link.deviceBId else link.deviceAId
            if (visited.add(next)) {
                cameFrom[next] = link
                queue.add(next)
            }
        }
    }
    return emptyList()
}
