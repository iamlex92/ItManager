package com.itmanager.app.data.model

/** A point of a cable on one floor: an end device ([index] -1 for end A, waypoints.size for end B) or a bend. */
data class RoutePoint(val x: Float, val y: Float, val index: Int)

/**
 * Where a cable leaves this floor for [otherFloorId]. [virtual]: the cable has no bend on this floor yet,
 * so the riser is drawn beside the device and dragging it adds the first bend at [insertAt].
 */
data class Riser(val x: Float, val y: Float, val otherFloorId: String?, val virtual: Boolean, val insertAt: Int)

/** One cable as drawn on one floor. */
data class CableRoute(val link: Connection, val points: List<RoutePoint>, val riser: Riser?) {
    /** The index a new bend takes when dragged out of the segment between points [k] and [k] + 1. */
    fun insertAt(k: Int): Int {
        val next = points[k + 1].index
        return if (next in link.waypoints.indices) next else points[k].index + 1
    }
}

/** How far beside its device a riser without bends is drawn, in map pixels. */
const val RISER_OFFSET = 48f

/**
 * The part of [link] on floor [floorId]. [here] holds the devices shown on this floor; [floorOf] gives the
 * floor of any other device. Null when neither end is on this floor.
 */
fun routeOn(link: Connection, floorId: String, here: Map<String, Device>, floorOf: (String) -> String?): CableRoute? {
    val a = here[link.deviceAId]
    val b = here[link.deviceBId]
    if (a == null && b == null) return null
    val floorA = if (a != null) floorId else floorOf(link.deviceAId)
    val local = link.waypoints.withIndex()
        .filter { (it.value.floorId ?: floorA) == floorId }
        .map { RoutePoint(it.value.x, it.value.y, it.index) }
    val size = link.waypoints.size
    val points = buildList {
        if (a != null) add(RoutePoint(a.x, a.y, -1))
        addAll(local)
        if (b != null) add(RoutePoint(b.x, b.y, size))
    }
    if (a != null && b != null) return CableRoute(link, points, null)
    val other = if (a != null) floorOf(link.deviceBId) else floorA
    val riser = if (local.isEmpty()) {
        val end = points.single()
        // Bends of the local end come first for end A and last for end B.
        Riser(end.x + RISER_OFFSET, end.y - RISER_OFFSET, other, virtual = true, insertAt = if (a != null) 0 else size)
    } else {
        val far = if (a != null) local.last() else local.first()
        Riser(far.x, far.y, other, virtual = false, insertAt = if (a != null) far.index + 1 else far.index)
    }
    return CableRoute(link, points, riser)
}

fun List<Waypoint>.inserted(at: Int, point: Waypoint): List<Waypoint> =
    toMutableList().apply { add(at.coerceIn(0, size), point) }

fun List<Waypoint>.moved(index: Int, x: Float, y: Float): List<Waypoint> =
    mapIndexed { i, p -> if (i == index) p.copy(x = x, y = y) else p }

fun List<Waypoint>.removed(index: Int): List<Waypoint> = filterIndexed { i, _ -> i != index }
