package com.itmanager.app.core.nfc

import android.content.Context
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import com.itmanager.app.core.network.TagBoundElsewhereException
import com.itmanager.app.core.network.UserFacingException
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.source.DcimRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A tag held to the phone outside binding mode. */
data class TagScan(val uid: String, val payload: TagPayload?)

/** Binding a tag to a device: the sheet follows these steps. */
data class BindState(val device: Device, val step: BindStep, val force: Boolean = false)

sealed interface BindStep {
    /** Waiting for a tag. [retry] is set after a failed attempt, so the sheet can say "try again". */
    data class Waiting(val retry: String? = null) : BindStep
    data object Working : BindStep
    data class Conflict(val otherDevice: String?) : BindStep
    data object Done : BindStep
}

enum class NfcAvailability { READY, DISABLED, MISSING }

/**
 * Everything NFC in one place. The activity hands every tag to [onTag]; outside binding mode it becomes a [TagScan],
 * in binding mode the tag is first bound on the server, then written with the device's payload.
 */
@Singleton
class NfcController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DcimRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _scans = MutableSharedFlow<TagScan>(extraBufferCapacity = 4)
    val scans: SharedFlow<TagScan> = _scans.asSharedFlow()

    private val _binding = MutableStateFlow<BindState?>(null)
    val binding: StateFlow<BindState?> = _binding.asStateFlow()

    fun availability(): NfcAvailability {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcAvailability.MISSING
        return if (adapter.isEnabled) NfcAvailability.READY else NfcAvailability.DISABLED
    }

    fun startBinding(device: Device) {
        _binding.value = BindState(device, BindStep.Waiting())
    }

    /** The user agreed to take the tag away from the device it was bound to; they hold it to the phone again. */
    fun confirmMove() {
        _binding.update { it?.copy(step = BindStep.Waiting(), force = true) }
    }

    fun cancelBinding() {
        _binding.value = null
    }

    /** Called on any thread with a freshly discovered tag. */
    fun onTag(tag: Tag) {
        scope.launch {
            val binding = _binding.value
            if (binding != null) {
                if (binding.step is BindStep.Waiting) bind(binding, tag)
            } else {
                _scans.emit(TagScan(formatTagUid(tag.id), readPayload(tag)))
            }
        }
    }

    private suspend fun bind(state: BindState, tag: Tag) {
        _binding.value = state.copy(step = BindStep.Working)
        val uid = formatTagUid(tag.id)
        // Server first: if it refuses, the tag keeps whatever it had. A repeat after a failed write is harmless.
        val bound = repository.bindNfc(state.device.id, uid, state.force)
        val error = bound.exceptionOrNull()
        if (error is TagBoundElsewhereException) {
            _binding.value = state.copy(step = BindStep.Conflict(error.deviceName))
            return
        }
        if (error != null) {
            _binding.value = state.copy(step = BindStep.Waiting(retry = error.toUserMessage()))
            return
        }
        val written = withContext(Dispatchers.IO) { runCatching { write(tag, state.device) } }
        _binding.value = written.fold(
            onSuccess = { state.copy(step = BindStep.Done) },
            onFailure = { e ->
                val message = when (e) {
                    is UserFacingException -> e.message.orEmpty()
                    is TagLostException, is IOException -> "Метку убрали раньше времени. Поднесите её ещё раз и подержите."
                    else -> "Не удалось записать метку: ${e.message ?: e.javaClass.simpleName}"
                }
                state.copy(step = BindStep.Waiting(retry = message))
            },
        )
    }

    private fun readPayload(tag: Tag): TagPayload? {
        val message = Ndef.get(tag)?.cachedNdefMessage ?: return null
        return message.records.firstNotNullOfOrNull { record ->
            if (record.tnf == NdefRecord.TNF_MIME_MEDIA) TagPayload.parse(record.payload) else null
        }
    }

    /** Payload for an NTAG213 must fit 137 bytes; a long device name is dropped first, the id always stays. */
    private fun write(tag: Tag, device: Device) {
        val full = message(TagPayload.forDevice(device))
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                if (!ndef.isWritable) throw UserFacingException("Метка защищена от записи.")
                val message = full.takeIf { it.byteArrayLength <= ndef.maxSize }
                    ?: message(TagPayload.forDevice(device, withName = false)).takeIf { it.byteArrayLength <= ndef.maxSize }
                    ?: throw UserFacingException("На метке мало места. Нужна NTAG213, 215 или 216.")
                ndef.writeNdefMessage(message)
            } finally {
                runCatching { ndef.close() }
            }
            return
        }
        val formatable = NdefFormatable.get(tag) ?: throw UserFacingException("Эта метка не поддерживает NDEF. Нужна NTAG213, 215 или 216.")
        formatable.connect()
        try {
            formatable.format(full)
        } catch (e: FormatException) {
            throw UserFacingException("Не удалось разметить метку.")
        } finally {
            runCatching { formatable.close() }
        }
    }

    private fun message(payload: TagPayload) =
        NdefMessage(NdefRecord.createMime(TagPayload.MIME_TYPE, payload.encode()))
}
