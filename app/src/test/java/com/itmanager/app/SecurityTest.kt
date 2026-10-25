package com.itmanager.app

import com.itmanager.app.core.network.CredentialDto
import com.itmanager.app.core.network.CredentialPatch
import com.itmanager.app.core.network.HealthDto
import com.itmanager.app.core.network.UserFacingException
import com.itmanager.app.core.network.toBody
import com.itmanager.app.core.network.toPatch
import com.itmanager.app.core.security.LockTimer
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.CredentialDraft
import com.itmanager.app.data.model.CredentialKind
import com.itmanager.app.data.source.DemoCredentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecurityTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun draft(name: String = "Коммутаторы", secret: String = "s3cret") =
        CredentialDraft(name, CredentialKind.SSH, " netro ", secret, "")

    @Test
    fun `lock stays open after a short trip out and locks after a long one`() {
        val timer = LockTimer(timeoutMs = 1_000)
        assertFalse("cold return without a background trip", timer.shouldRelock(5_000))
        timer.onBackground(10_000)
        assertFalse(timer.shouldRelock(10_500))
        timer.onBackground(20_000)
        assertTrue(timer.shouldRelock(21_000))
        assertFalse("relock is reported once per return", timer.shouldRelock(30_000))
    }

    @Test
    fun `draft checks`() {
        assertEquals("Укажите название", draft(name = " ").problem(isNew = true))
        assertNotNull("a new login needs its secret", draft(secret = "").problem(isNew = true))
        assertNull("an edit keeps the stored secret", draft(secret = "").problem(isNew = false))
        assertNull(draft().problem(isNew = true))
    }

    @Test
    fun `blank secret is left out of an update`() {
        val patch = json.encodeToString(CredentialPatch.serializer(), draft(secret = "").toPatch("Pixel"))
        assertFalse(patch.contains("secret"))
        assertTrue(patch.contains("\"username\":\"netro\""))
        val body = draft().toBody("Pixel")
        assertEquals("ssh", body.kind)
        assertEquals("s3cret", body.secret)
    }

    @Test
    fun `credential from the server has no secret field`() {
        val dto = json.decodeFromString(
            CredentialDto.serializer(),
            """{"id":"1","name":"МФУ","kind":"snmp_v3","username":"mon","has_secret":true,"notes":null,
               "devices":2,"created_at":"2026-10-25T10:00:00Z","updated_at":"2026-10-25T11:00:00Z"}""",
        )
        val c = dto.toDomain()
        assertEquals(CredentialKind.SNMP_V3, c.kind)
        assertTrue(c.hasSecret)
        assertEquals(2, c.devices)
        assertNotNull(c.updatedAt)
        assertEquals(CredentialKind.OTHER, CredentialKind.parse("telnet"))
    }

    @Test
    fun `security audit kind and health flag parse`() {
        assertEquals(AuditKind.SECURITY, AuditKind.parse("security"))
        val health = json.decodeFromString(HealthDto.serializer(), """{"status":"ok","secrets_encrypted":true}""").toDomain()
        assertEquals(true, health.secretsEncrypted)
        val old = json.decodeFromString(HealthDto.serializer(), """{"status":"ok"}""").toDomain()
        assertNull(old.secretsEncrypted)
    }

    @Test
    fun `demo store refuses a new login without a secret`() = runTest {
        assertTrue(DemoCredentials.list().all { it.hasSecret })
        try {
            DemoCredentials.save(null, draft(secret = ""))
            fail("saved without a secret")
        } catch (e: UserFacingException) {
            assertTrue(e.message!!.startsWith("Укажите"))
        }
    }
}
