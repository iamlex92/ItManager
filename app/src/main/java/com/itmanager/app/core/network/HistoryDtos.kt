package com.itmanager.app.core.network

import com.itmanager.app.data.model.DeviceHistoryEntry
import com.itmanager.app.data.model.HistoryKind
import kotlinx.serialization.Serializable

// Change history of a switch (server/API.md, "History").

@Serializable
data class HistoryEntryDto(
    val at: String? = null,
    val kind: String,
    val title: String,
    val summary: String = "",
    val actor: String = "",
    val commands: List<String> = emptyList(),
) {
    fun toDomain() = DeviceHistoryEntry(parseInstant(at), HistoryKind.parse(kind), title, summary, actor, commands)
}
