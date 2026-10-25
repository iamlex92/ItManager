package com.itmanager.app.data.source

import com.itmanager.app.core.network.UserFacingException
import com.itmanager.app.data.model.Credential
import com.itmanager.app.data.model.CredentialDraft
import com.itmanager.app.data.model.CredentialKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Demo credential store. Like the server it keeps only whether a secret is set: the typed secret
 * is dropped at once, so not even demo memory holds it.
 */
internal object DemoCredentials {
    private val lock = Mutex()
    private val items = LinkedHashMap<String, Credential>()

    init {
        val now = Instant.now()
        listOf(
            Credential("demo-cred-1", "Коммутаторы: SSH только чтение", CredentialKind.SSH, "netro", true,
                "Учётная запись с privilege 1 на всех коммутаторах", 4, now.minus(Duration.ofDays(12))),
            Credential("demo-cred-2", "МФУ: SNMPv3", CredentialKind.SNMP_V3, "printmon", true, null, 0, now.minus(Duration.ofDays(40))),
        ).forEach { items[it.id] = it }
    }

    suspend fun list(): List<Credential> = lock.withLock { items.values.sortedBy { it.name.lowercase() } }

    suspend fun save(id: String?, draft: CredentialDraft): Credential {
        draft.problem(isNew = id == null)?.let { throw UserFacingException(it) }
        val name = draft.name.trim()
        val saved = lock.withLock {
            if (items.values.any { it.name.equals(name, ignoreCase = true) && it.id != id }) {
                throw UserFacingException("Учётные данные «$name» уже есть.")
            }
            val old = id?.let { items[it] ?: throw UserFacingException("Этих учётных данных уже нет. Обновите список.") }
            val credential = Credential(
                id = id ?: UUID.randomUUID().toString(),
                name = name,
                kind = old?.kind ?: draft.kind,
                username = draft.username.trim().ifEmpty { null },
                hasSecret = draft.secret.isNotEmpty() || old?.hasSecret == true,
                notes = draft.notes.trim().ifEmpty { null },
                devices = old?.devices ?: 0,
                updatedAt = Instant.now(),
            )
            items[credential.id] = credential
            credential
        }
        val secretNote = when {
            id == null -> null
            draft.secret.isNotEmpty() -> "secret" to "заменён"
            else -> "secret" to "прежний"
        }
        DemoRunbooks.recordSecurity(
            action = if (id == null) "credential_create" else "credential_update",
            title = if (id == null) "Добавление учётных данных" else "Изменение учётных данных",
            arguments = auditArguments(saved) + listOfNotNull(secretNote),
            summary = saved.name,
        )
        return saved
    }

    suspend fun delete(id: String) {
        val removed = lock.withLock { items.remove(id) } ?: throw UserFacingException("Этих учётных данных уже нет. Обновите список.")
        DemoRunbooks.recordSecurity("credential_delete", "Удаление учётных данных", auditArguments(removed), removed.name)
    }

    private fun auditArguments(c: Credential) =
        listOf("name" to c.name, "kind" to c.kind.wire) + listOfNotNull(c.username?.let { "username" to it })
}
