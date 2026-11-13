package com.itmanager.app

import com.itmanager.app.core.network.DocBriefDto
import com.itmanager.app.core.network.DocDto
import com.itmanager.app.core.network.TaskDto
import com.itmanager.app.data.model.TaskDraft
import com.itmanager.app.data.model.docCategoryLabel
import com.itmanager.app.data.model.repeatLabel
import com.itmanager.app.data.model.toDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkTest {
    @Test
    fun `a task needs a title, a real date and a sane repeat`() {
        assertFalse(TaskDraft().canSave)
        assertTrue(TaskDraft(title = "Проверить ИБП").canSave)
        assertTrue(TaskDraft(title = "ИБП", dueDate = "2026-12-31").canSave)
        assertFalse(TaskDraft(title = "ИБП", dueDate = "31.12.2026").canSave)
        assertFalse(TaskDraft(title = "ИБП", dueDate = "2026-02-31").canSave)
        assertFalse(TaskDraft(title = "ИБП", repeatEvery = "0").canSave)
        assertFalse(TaskDraft(title = "ИБП", repeatEvery = "").canSave)
        assertNull(TaskDraft(title = "x", dueDate = "").dateError)
    }

    @Test
    fun `repeats read as words`() {
        assertNull(repeatLabel("none", 1))
        assertEquals("каждый месяц", repeatLabel("monthly", 1))
        assertEquals("каждые 3 мес.", repeatLabel("monthly", 3))
        assertEquals("каждые 2 нед.", repeatLabel("weekly", 2))
    }

    @Test
    fun `server answers reach the model and back into the form`() {
        val t = TaskDto(id = "t", title = "Батарея", dueDate = "2026-11-10", repeat = "monthly", repeatEvery = 2, daysLeft = -3).toDomain()
        assertEquals(-3, t.daysLeft)
        val draft = t.toDraft()
        assertEquals("2026-11-10", draft.dueDate)
        assertEquals("2", draft.repeatEvery)
        assertEquals("", draft.notes)
        val brief = DocBriefDto(id = "d", title = "Паспорт", category = "passport", pinned = true).toDomain()
        assertTrue(brief.pinned)
        assertEquals("Паспорт", docCategoryLabel(brief.category))
        assertEquals("Заметка", docCategoryLabel("something-new"))
        val doc = DocDto(id = "d", title = "Паспорт", body = "# Серверная").toDomain()
        assertEquals("# Серверная", doc.toDraft().body)
    }
}
