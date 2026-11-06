package com.itmanager.app.core.nfc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A link the phone's camera (or another app) hands to ItManager: a device label, itmanager://device/<id>, or the
 * server's connection code, netadmin://connect?u=…&t=….
 */
sealed interface DeepLink {
    data class Device(val id: String) : DeepLink

    /** The whole connection code, as the settings' QR scanner reads it. */
    data class Connect(val code: String) : DeepLink

    companion object {
        private val DEVICE = Regex("""^itmanager://device/([0-9A-Za-z-]{1,64})/?(\?.*)?$""", RegexOption.IGNORE_CASE)

        fun parse(text: String?): DeepLink? {
            val t = text?.trim().orEmpty()
            DEVICE.matchEntire(t)?.let { return Device(it.groupValues[1]) }
            if (t.startsWith("netadmin://connect", ignoreCase = true)) return Connect(t)
            return null
        }

        /** The device a scanned label points to: a link on new labels, the NFC tag's JSON on labels printed before. */
        fun deviceId(text: String): String? =
            (parse(text) as? Device)?.id ?: TagPayload.parse(text.encodeToByteArray())?.id
    }
}

/** Links that arrived before the screen that handles them was ready; each one is taken once. */
@Singleton
class DeepLinkInbox @Inject constructor() {
    private val _pending = MutableStateFlow<DeepLink?>(null)
    val pending: StateFlow<DeepLink?> = _pending.asStateFlow()

    fun offer(link: DeepLink) {
        _pending.value = link
    }

    /** The waiting link if it is a [T]; it is gone afterwards. */
    inline fun <reified T : DeepLink> take(): T? = (pending.value as? T)?.also { clear() }

    fun clear() {
        _pending.value = null
    }
}
