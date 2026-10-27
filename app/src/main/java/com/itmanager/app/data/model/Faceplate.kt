package com.itmanager.app.data.model

/**
 * A drawing of a device's front panel (SVG, [width] × [height] in its own units) with its ports marked on it.
 * Marks are fractions of the drawing, so they follow it at any size.
 */
data class Faceplate(
    val deviceId: String,
    val width: Float,
    val height: Float,
    val ports: List<FaceplatePort>,
) {
    val aspect: Float get() = if (height > 0f) width / height else 10f
}

/** One port's place on the drawing: top left corner [x], [y] and size [w], [h], all fractions of 0..1. */
data class FaceplatePort(
    val portId: String,
    val portName: String,
    val portNumber: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    fun contains(fx: Float, fy: Float): Boolean = fx in x..(x + w) && fy in y..(y + h)

    val centerX: Float get() = x + w / 2f
    val centerY: Float get() = y + h / 2f
}

/** Port marking helpers for the faceplate editor. Pure functions, kept here so they can be tested. */
object FaceplateMarks {
    const val DEFAULT_W = 0.022f
    const val DEFAULT_H = 0.28f

    /** A mark of size [w] × [h] centred on [fx], [fy], kept inside the drawing. */
    fun centredAt(port: SwitchPort, fx: Float, fy: Float, w: Float, h: Float): FaceplatePort {
        val cw = w.coerceIn(0.002f, 1f)
        val ch = h.coerceIn(0.002f, 1f)
        return FaceplatePort(
            port.id, port.name, port.number,
            x = (fx - cw / 2f).coerceIn(0f, 1f - cw),
            y = (fy - ch / 2f).coerceIn(0f, 1f - ch),
            w = cw, h = ch,
        )
    }

    /** Ports in the order they are marked: by number, every [step]-th starting at [start]. */
    fun sequence(ports: List<SwitchPort>, start: SwitchPort, step: Int): List<SwitchPort> {
        val sorted = ports.sortedBy { it.number }
        val from = sorted.indexOfFirst { it.id == start.id }.takeIf { it >= 0 } ?: return emptyList()
        return sorted.drop(from).filterIndexed { i, _ -> i % step.coerceAtLeast(1) == 0 }
    }

    /** The port to mark after [current]: the next one [step] places on that has no mark yet, or null. */
    fun next(ports: List<SwitchPort>, current: SwitchPort, step: Int, marked: Set<String>): SwitchPort? =
        sequence(ports, current, step).drop(1).firstOrNull { it.id !in marked }

    /**
     * Continues a row: the two marks before [from] in its sequence give the spacing, and every following
     * port of the sequence gets a mark at that spacing while it still fits on the drawing. Marks already
     * set are replaced. Returns all marks, or null when there are not two marks to learn the spacing from.
     */
    fun continueRow(
        ports: List<SwitchPort>,
        marks: Map<String, FaceplatePort>,
        from: SwitchPort,
        step: Int,
    ): Map<String, FaceplatePort>? {
        val sorted = ports.sortedBy { it.number }
        val index = sorted.indexOfFirst { it.id == from.id }
        if (index < 0) return null
        val s = step.coerceAtLeast(1)
        // The two ports before [from] in the same sequence.
        val prev1 = sorted.getOrNull(index - s) ?: return null
        val prev2 = sorted.getOrNull(index - 2 * s) ?: return null
        val a = marks[prev2.id] ?: return null
        val b = marks[prev1.id] ?: return null
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (dx == 0f && dy == 0f) return null
        val out = marks.toMutableMap()
        var last = b
        for (port in sequence(ports, from, s)) {
            val x = last.x + dx
            val y = last.y + dy
            if (x < -0.001f || y < -0.001f || x + b.w > 1.001f || y + b.h > 1.001f) break
            val mark = FaceplatePort(port.id, port.name, port.number, x.coerceIn(0f, 1f - b.w), y.coerceIn(0f, 1f - b.h), b.w, b.h)
            out[port.id] = mark
            last = mark
        }
        return out
    }
}
