package com.itmanager.app.core.network

import com.itmanager.app.data.model.Credential
import com.itmanager.app.data.model.CredentialDraft
import com.itmanager.app.data.model.CredentialKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Stored credentials (server/API.md, "Credentials"). The secret is write-only.

@Serializable
data class CredentialDto(
    val id: String,
    val name: String,
    val kind: String,
    val username: String? = null,
    @SerialName("has_secret") val hasSecret: Boolean = false,
    val notes: String? = null,
    val devices: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun toDomain() = Credential(
        id = id, name = name, kind = CredentialKind.parse(kind), username = username, hasSecret = hasSecret,
        notes = notes, devices = devices, updatedAt = parseInstant(updatedAt),
    )
}

@Serializable
data class CredentialBody(
    val name: String,
    val kind: String,
    val username: String? = null,
    val secret: String? = null,
    val notes: String? = null,
    val operator: String? = null,
)

/** Null fields are left out of the request (explicitNulls = false), so the server keeps them as they are. */
@Serializable
data class CredentialPatch(
    val name: String? = null,
    val username: String? = null,
    val secret: String? = null,
    val notes: String? = null,
    val operator: String? = null,
)

internal fun CredentialDraft.toBody(operator: String) = CredentialBody(
    name = name.trim(),
    kind = kind.wire,
    username = username.trim().ifEmpty { null },
    secret = secret.ifEmpty { null },
    notes = notes.trim().ifEmpty { null },
    operator = operator,
)

/** An empty username or note is sent as "" to clear it; an empty secret is left out to keep the stored one. */
internal fun CredentialDraft.toPatch(operator: String) = CredentialPatch(
    name = name.trim(),
    username = username.trim(),
    secret = secret.ifEmpty { null },
    notes = notes.trim(),
    operator = operator,
)
