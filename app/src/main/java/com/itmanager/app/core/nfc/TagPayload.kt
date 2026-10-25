package com.itmanager.app.core.nfc

import com.itmanager.app.data.model.Device
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a NetAdmin tag carries (spec 3.4): `{"app":"netadmin","v":1,"id":"…","type":"switch","name":"SW-CORE-01"}`,
 * stored as one MIME record of type [MIME_TYPE].
 */
@Serializable
data class TagPayload(
    val app: String = APP,
    val v: Int = VERSION,
    val id: String,
    val type: String? = null,
    val name: String? = null,
) {
    companion object {
        const val APP = "netadmin"
        const val VERSION = 1
        const val MIME_TYPE = "application/json"

        private val json = Json {
            ignoreUnknownKeys = true
            // "app" and "v" have defaults but must still be written to the tag.
            encodeDefaults = true
            explicitNulls = false
        }

        fun forDevice(device: Device, withName: Boolean = true) = TagPayload(
            id = device.id,
            type = device.type.name.lowercase(),
            name = device.name.takeIf { withName },
        )

        /** Null for anything that is not a NetAdmin tag: another app's JSON, a broken record, a newer format. */
        fun parse(bytes: ByteArray): TagPayload? = runCatching {
            json.decodeFromString(serializer(), bytes.decodeToString())
        }.getOrNull()?.takeIf { it.app == APP && it.v <= VERSION && it.id.isNotBlank() }
    }

    fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()
}

/** Tag UID in the server's canonical form: uppercase hex bytes joined by colons, e.g. 04:A2:3B:1C:5D:80:00. */
fun formatTagUid(id: ByteArray): String = id.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
