package com.itmanager.app.core.network

import com.itmanager.app.data.model.DocBrief
import com.itmanager.app.data.model.DocFull
import com.itmanager.app.data.model.TaskItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val notes: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    val repeat: String = "none",
    @SerialName("repeat_every") val repeatEvery: Int = 1,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("is_done") val isDone: Boolean = false,
    @SerialName("days_left") val daysLeft: Int? = null,
) {
    fun toDomain() = TaskItem(id, title, notes, dueDate, repeat, repeatEvery, deviceName, isDone, daysLeft)
}

@Serializable
data class TaskCompleteDto(val done: TaskDto, val next: TaskDto? = null)

@Serializable
data class DocBriefDto(
    val id: String,
    val title: String,
    val category: String = "note",
    val excerpt: String = "",
    @SerialName("device_name") val deviceName: String? = null,
    val pinned: Boolean = false,
) {
    fun toDomain() = DocBrief(id, title, category, excerpt, deviceName, pinned)
}

@Serializable
data class DocDto(
    val id: String,
    val title: String,
    val category: String = "note",
    val body: String = "",
    @SerialName("device_name") val deviceName: String? = null,
    val pinned: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
) {
    fun toDomain() = DocFull(id, title, category, body, deviceName, pinned, updatedAt)
}
