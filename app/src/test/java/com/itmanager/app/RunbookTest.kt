package com.itmanager.app

import com.itmanager.app.core.network.AuditDto
import com.itmanager.app.core.network.RunbookRunBody
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.RunbookParamKind
import com.itmanager.app.data.model.Runbook
import com.itmanager.app.data.model.RunbookParam
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunbookTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val snapshot = Runbook(
        "vm_snapshot", "Инфраструктура", "Снапшот виртуальной машины", "", critical = true,
        params = listOf(
            RunbookParam("vm", "Виртуальная машина", RunbookParamKind.CHOICE),
            RunbookParam("name", "Имя снапшота", required = false),
        ),
    )

    @Test
    fun requiredFieldsMustBeFilled() {
        assertEquals("vm", snapshot.firstMissing(emptyMap())?.name)
        assertEquals("vm", snapshot.firstMissing(mapOf("vm" to "  "))?.name)
        // The snapshot name is optional.
        assertNull(snapshot.firstMissing(mapOf("vm" to "vm-mes")))
    }

    @Test
    fun argumentsAreTrimmedAndSkipEmptyOptionalFields() {
        assertEquals(mapOf("vm" to "vm-mes"), snapshot.arguments(mapOf("vm" to " vm-mes ", "name" to " ", "junk" to "x")))
    }

    @Test
    fun runbookAuditRowShowsNestedArgumentsAndAuthMethod() {
        val raw = """
            {"id": "a1", "action": "runbook:ad_unlock_account", "kind": "runbook", "title": "Разблокировка",
             "triggered_by": "Pixel 10a · 10.0.0.5",
             "parameters": {"params": {"account": "i.ivanov"}, "confirmed": false, "auth_method": "not_required", "simulated": true},
             "executed_at": "2026-10-25T15:00:00Z", "exit_code": 0, "stdout": "OK", "stderr": null, "duration_ms": 812}
        """
        val entry = json.decodeFromString(AuditDto.serializer(), raw).toDomain()
        assertEquals(AuditKind.RUNBOOK, entry.kind)
        assertEquals(listOf("account" to "i.ivanov"), entry.arguments)
        assertEquals(AuthMethod.NOT_REQUIRED, entry.authMethod)
        assertEquals("", entry.stderr)
        assertEquals(812L, entry.durationMs)
    }

    @Test
    fun switchAuditRowFlattensArguments() {
        val raw = """
            {"id": "a2", "action": "port_config", "kind": "switch", "title": "Настройка порта", "triggered_by": "api:10.0.0.5",
             "parameters": {"device": "SW-ACC-01", "tagged_vlans": [10, 20], "hal_mode": "emulator"},
             "executed_at": "2026-10-25T15:00:00+00:00", "exit_code": 403, "stdout": "BLOCKED"}
        """
        val entry = json.decodeFromString(AuditDto.serializer(), raw).toDomain()
        assertEquals(AuditKind.SWITCH, entry.kind)
        assertEquals(listOf("device" to "SW-ACC-01", "tagged_vlans" to "10, 20", "hal_mode" to "emulator"), entry.arguments)
        assertNull(entry.authMethod)
        assertTrue(!entry.succeeded)
    }

    @Test
    fun runBodyUsesServerFieldNames() {
        val body = json.encodeToString(RunbookRunBody.serializer(), RunbookRunBody(mapOf("account" to "x"), true, "biometric", "Pixel"))
        assertEquals("""{"params":{"account":"x"},"confirm":true,"auth_method":"biometric","operator":"Pixel"}""", body)
    }
}
