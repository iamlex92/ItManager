package com.itmanager.app.data.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Which end of the rack unit 1 is drawn at. */
enum class RackNumbering(val key: String, val label: String) {
    BOTTOM("bottom", "Снизу вверх"),
    TOP("top", "Сверху вниз"),
}

/** What a mounted device's block says about it. */
enum class RackLabels(val key: String, val label: String) {
    NAME("name", "Имя"),
    NAME_MODEL("name_model", "Имя и модель"),
    NONE("none", "Без подписей"),
}

enum class RackWidth(val key: String, val label: String, val maxDp: Int) {
    NARROW("narrow", "Узкая", 300),
    NORMAL("normal", "Обычная", 440),
    WIDE("wide", "Широкая", 640),
}

/** How a rack is drawn. Kept in the rack device's metadata, so the web console and the app share it. */
data class RackStyle(
    /** #RRGGBB of the frame and rails; null is the theme's. */
    val color: String? = null,
    val numbering: RackNumbering = RackNumbering.BOTTOM,
    val labels: RackLabels = RackLabels.NAME_MODEL,
    val width: RackWidth = RackWidth.NORMAL,
) {
    /** [metadata] with this style written over its rack_* keys; every other key stays. */
    fun writeTo(metadata: Map<String, JsonElement>): Map<String, JsonElement> {
        val out = metadata.toMutableMap()
        if (color == null) out.remove(KEY_COLOR) else out[KEY_COLOR] = JsonPrimitive(color)
        out[KEY_NUMBERING] = JsonPrimitive(numbering.key)
        out[KEY_LABELS] = JsonPrimitive(labels.key)
        out[KEY_WIDTH] = JsonPrimitive(width.key)
        return out
    }

    companion object {
        const val KEY_COLOR = "rack_color"
        const val KEY_NUMBERING = "rack_numbering"
        const val KEY_LABELS = "rack_labels"
        const val KEY_WIDTH = "rack_width"

        private val HEX = Regex("^#[0-9a-fA-F]{6}$")

        /** The style kept in [metadata]; anything missing or unknown falls back to the default. */
        fun from(metadata: Map<String, JsonElement>): RackStyle {
            fun text(key: String) = (metadata[key] as? JsonPrimitive)?.contentOrNull
            return RackStyle(
                color = text(KEY_COLOR)?.takeIf { HEX.matches(it) },
                numbering = RackNumbering.entries.firstOrNull { it.key == text(KEY_NUMBERING) } ?: RackNumbering.BOTTOM,
                labels = RackLabels.entries.firstOrNull { it.key == text(KEY_LABELS) } ?: RackLabels.NAME_MODEL,
                width = RackWidth.entries.firstOrNull { it.key == text(KEY_WIDTH) } ?: RackWidth.NORMAL,
            )
        }
    }
}
