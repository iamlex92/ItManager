package com.itmanager.app

import com.itmanager.app.core.network.DeviceDto
import com.itmanager.app.core.network.ExpirationDto
import com.itmanager.app.core.network.LicenseDto
import com.itmanager.app.data.model.daysLeftText
import com.itmanager.app.data.model.daysUntil
import com.itmanager.app.data.model.licenseKindLabel
import com.itmanager.app.data.model.shortDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LifecycleTest {
    @Test
    fun `dates are shown day first and bad ones stay as they are`() {
        assertEquals("13.11.2026", shortDate("2026-11-13"))
        assertEquals("13.11.2026", shortDate("2026-11-13T00:00:00Z"))
        assertEquals("never", shortDate("never"))
        assertNull(shortDate(null))
        assertNull(daysUntil("never"))
    }

    @Test
    fun `days left read as a sentence`() {
        assertEquals("просрочено на 3 дн.", daysLeftText(-3))
        assertEquals("сегодня", daysLeftText(0))
        assertEquals("через 12 дн.", daysLeftText(12))
    }

    @Test
    fun `server answers reach the model`() {
        val e = ExpirationDto(source = "warranty", title = "Гарантия: PC-1", due = "2026-12-01", daysLeft = 12, level = "month", deviceId = "d1").toDomain()
        assertEquals("month", e.level)
        assertEquals("d1", e.deviceId)
        val l = LicenseDto(id = "l", software = "1С", seats = 2, used = 3, over = true, kind = "per_user").toDomain()
        assertEquals(true, l.over)
        assertEquals("на пользователя", licenseKindLabel(l.kind))
        val d = DeviceDto(id = "x", name = "x", type = "WORKSTATION", serialNumber = "SN1", warrantyUntil = "2027-01-01", assignedTo = "Иванова").toDomain()
        assertEquals("SN1", d.serialNumber)
        assertEquals("2027-01-01", d.warrantyUntil)
        assertEquals("Иванова", d.assignedTo)
    }
}
