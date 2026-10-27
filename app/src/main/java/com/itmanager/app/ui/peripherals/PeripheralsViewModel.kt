package com.itmanager.app.ui.peripherals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.Peripheral
import com.itmanager.app.data.model.PeripheralDraft
import com.itmanager.app.data.model.takesPeripherals
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The peripheral form: what is edited ([editing] null means a new item) and how saving went. */
data class PeripheralEditor(
    val editing: Peripheral?,
    val draft: PeripheralDraft,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Peripherals of every device and the stock. Shared by the list screen and the device card on the map:
 * both read the same list, so a monitor moved in one place shows up in the other at once.
 */
@HiltViewModel
class PeripheralsViewModel @Inject constructor(
    private val repository: DcimRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Peripheral>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Peripheral>>> = _state.asStateFlow()

    /** Devices a peripheral can be attached to, by name. */
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _editor = MutableStateFlow<PeripheralEditor?>(null)
    val editor: StateFlow<PeripheralEditor?> = _editor.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { repository.peripheralChanges.collect { load() } }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else it }
        repository.devices().onSuccess { list ->
            _devices.value = list.filter { it.type.takesPeripherals }.sortedBy { it.name.lowercase() }
        }
        repository.peripherals().fold(
            onSuccess = { list -> _state.value = UiState.Content(list) },
            onFailure = { e ->
                // Keep what is on screen when only a refresh failed.
                val current = _state.value
                if (current is UiState.Content) {
                    _state.value = current.copy(isRefreshing = false)
                    _messages.tryEmit(e.toUserMessage())
                } else {
                    _state.value = UiState.Error(e.toUserMessage())
                }
            },
        )
    }

    fun openNew(draft: PeripheralDraft) {
        _editor.value = PeripheralEditor(null, draft)
    }

    fun openEdit(item: Peripheral) {
        _editor.value = PeripheralEditor(item, item.toDraft())
    }

    fun updateDraft(draft: PeripheralDraft) = _editor.update { it?.copy(draft = draft, error = null) }

    fun closeEditor() {
        _editor.value = null
    }

    fun save() {
        val editor = _editor.value ?: return
        if (editor.busy) return
        _editor.value = editor.copy(busy = true, error = null)
        viewModelScope.launch {
            repository.savePeripheral(editor.editing?.id, editor.draft.trimmed()).fold(
                onSuccess = { saved ->
                    _editor.value = null
                    _messages.tryEmit(if (editor.editing == null) "Добавлено: ${saved.title}" else "Сохранено")
                },
                onFailure = { e -> _editor.update { it?.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }

    /** Attaches to [deviceId] or returns to stock with null. */
    fun attach(item: Peripheral, deviceId: String?) {
        viewModelScope.launch {
            repository.attachPeripheral(item.id, deviceId).fold(
                onSuccess = { moved ->
                    _editor.value = null
                    _messages.tryEmit(moved.deviceName?.let { "${moved.title} → $it" } ?: "${moved.title}: на складе")
                },
                onFailure = { e -> _messages.tryEmit(e.toUserMessage()) },
            )
        }
    }

    fun delete(item: Peripheral) {
        viewModelScope.launch {
            repository.deletePeripheral(item.id).fold(
                onSuccess = {
                    _editor.value = null
                    _messages.tryEmit("Удалено: ${item.title}")
                },
                onFailure = { e ->
                    if (_editor.value != null) {
                        _editor.update { it?.copy(busy = false, error = e.toUserMessage()) }
                    } else {
                        _messages.tryEmit(e.toUserMessage())
                    }
                },
            )
        }
    }
}
