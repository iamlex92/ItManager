package com.itmanager.app.data.model

import kotlin.math.floor

/** Devices drawn as one marker because at this zoom their icons would cover each other. */
data class MarkerCluster(val x: Float, val y: Float, val deviceIds: List<String>) {
    val isSingle: Boolean get() = deviceIds.size == 1
}

object MapClusters {
    /** Network gear first: when devices merge, the cluster sits where the switch is, not a socket. */
    private fun rank(type: DeviceType) = when (type) {
        DeviceType.ROUTER, DeviceType.FIREWALL, DeviceType.SWITCH -> 0
        DeviceType.SERVER, DeviceType.RACK, DeviceType.ACCESS_POINT -> 1
        DeviceType.SOCKET, DeviceType.PATCH_PANEL -> 3
        else -> 2
    }

    /**
     * Groups devices closer than [radius] (map pixels) to a group's first device. Greedy and stable: the same
     * input gives the same groups. Devices in [keepApart] (the selected one, the one being dragged) are never
     * merged. A group stands at the middle of its devices.
     */
    fun cluster(devices: List<Device>, radius: Float, keepApart: Set<String> = emptySet()): List<MarkerCluster> {
        if (radius <= 0f || devices.size < 2) return devices.map { MarkerCluster(it.x, it.y, listOf(it.id)) }
        val ordered = devices.sortedWith(compareBy({ rank(it.type) }, { it.name }, { it.id }))
        // Grid of radius-sized cells: neighbours of a point are in its cell or the eight around it.
        val grid = HashMap<Long, MutableList<Device>>()
        fun cell(v: Float) = floor(v / radius).toLong()
        fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xFFFFFFFFL)
        ordered.forEach { d -> if (d.id !in keepApart) grid.getOrPut(key(cell(d.x), cell(d.y))) { mutableListOf() } += d }
        val taken = HashSet<String>()
        val out = mutableListOf<MarkerCluster>()
        val r2 = radius * radius
        for (seed in ordered) {
            if (seed.id in taken) continue
            taken += seed.id
            if (seed.id in keepApart) {
                out += MarkerCluster(seed.x, seed.y, listOf(seed.id))
                continue
            }
            val members = mutableListOf(seed)
            val cx = cell(seed.x)
            val cy = cell(seed.y)
            for (dx in -1L..1L) for (dy in -1L..1L) {
                grid[key(cx + dx, cy + dy)]?.forEach { d ->
                    if (d.id !in taken) {
                        val ex = d.x - seed.x
                        val ey = d.y - seed.y
                        if (ex * ex + ey * ey <= r2) {
                            taken += d.id
                            members += d
                        }
                    }
                }
            }
            out += MarkerCluster(members.map { it.x }.average().toFloat(), members.map { it.y }.average().toFloat(), members.map { it.id })
        }
        return out
    }
}
