package com.itmanager.app.data.model

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** A task of the service: what to do, by when, how often it comes back. */
data class TaskItem(
    val id: String,
    val title: String,
    val notes: String?,
    /** ISO date; null: no date. */
    val dueDate: String?,
    val repeat: String,
    val repeatEvery: Int,
    val deviceName: String?,
    val isDone: Boolean,
    /** Days to the date (negative: overdue); null without a date. */
    val daysLeft: Int?,
)

/** What the task form holds; dates are typed as ГГГГ-ММ-ДД. */
data class TaskDraft(
    val title: String = "",
    val notes: String = "",
    val dueDate: String = "",
    val repeat: String = "none",
    val repeatEvery: String = "1",
) {
    val dateError: String?
        get() = if (dueDate.isBlank() || parseIsoDate(dueDate) != null) null else "Дата в виде 2026-12-31"

    val canSave: Boolean get() = title.isNotBlank() && dateError == null && (repeatEvery.toIntOrNull() ?: 0) in 1..60
}

fun TaskItem.toDraft() = TaskDraft(title, notes.orEmpty(), dueDate.orEmpty(), repeat, repeatEvery.toString())

/** A line of the document list: the start of the text, not all of it. */
data class DocBrief(
    val id: String,
    val title: String,
    val category: String,
    val excerpt: String,
    val deviceName: String?,
    val pinned: Boolean,
)

data class DocFull(
    val id: String,
    val title: String,
    val category: String,
    val body: String,
    val deviceName: String?,
    val pinned: Boolean,
    val updatedAt: String,
)

data class DocDraft(
    val title: String = "",
    val category: String = "note",
    val body: String = "",
    val pinned: Boolean = false,
) {
    val canSave: Boolean get() = title.isNotBlank()
}

fun DocFull.toDraft() = DocDraft(title, category, body, pinned)

fun parseIsoDate(text: String): LocalDate? = try {
    LocalDate.parse(text.trim())
} catch (e: DateTimeParseException) {
    null
}

val TASK_REPEATS = listOf(
    "none" to "Без повтора",
    "daily" to "Каждый день",
    "weekly" to "Каждую неделю",
    "monthly" to "Каждый месяц",
    "yearly" to "Каждый год",
)

fun repeatLabel(repeat: String, every: Int): String? {
    if (repeat == "none") return null
    val unit = when (repeat) {
        "daily" -> "дн."
        "weekly" -> "нед."
        "monthly" -> "мес."
        else -> "г."
    }
    return if (every <= 1) TASK_REPEATS.firstOrNull { it.first == repeat }?.second?.lowercase() else "каждые $every $unit"
}

val DOC_CATEGORIES = listOf(
    "instruction" to "Инструкция",
    "passport" to "Паспорт",
    "scheme" to "Схема",
    "note" to "Заметка",
)

fun docCategoryLabel(category: String): String = DOC_CATEGORIES.firstOrNull { it.first == category }?.second ?: "Заметка"
