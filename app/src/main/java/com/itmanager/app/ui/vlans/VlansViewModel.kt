package com.itmanager.app.ui.vlans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.VLAN_COLORS
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.data.model.VlanDraft
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

/** The VLAN form: a new VLAN when [isNew], with how saving went. */
data class VlanEditor(
    val isNew: Boolean,
    val draft: VlanDraft,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class VlansViewModel @Inject constructor(
    private val repository: DcimRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Vlan>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Vlan>>> = _state.asStateFlow()

    private val _editor = MutableStateFlow<VlanEditor?>(null)
    val editor: StateFlow<VlanEditor?> = _editor.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { repository.settings.collect { load() } }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        repository.vlans().fold(
            onSuccess = { list -> _state.value = UiState.Content(list.sortedBy { it.id }) },
            onFailure = { e -> if (_state.value !is UiState.Content) _state.value = UiState.Error(e.toUserMessage()) },
        )
    }

    fun openNew() {
        val taken = (_state.value as? UiState.Content)?.data.orEmpty()
        // The next free ten after the highest VLAN, as most plants number them.
        val next = ((taken.maxOfOrNull { it.id } ?: 0) / 10 + 1) * 10
        _editor.value = VlanEditor(
            isNew = true,
            draft = VlanDraft(next.coerceAtMost(4094), "", "", VLAN_COLORS[taken.size % VLAN_COLORS.size], null),
        )
    }

    fun openEdit(vlan: Vlan) {
        _editor.value = VlanEditor(false, VlanDraft(vlan.id, vlan.name, vlan.subnet, vlan.colorArgb, vlan.description))
    }

    fun change(draft: VlanDraft) = _editor.update { it?.copy(draft = draft, error = null) }

    fun close() {
        _editor.value = null
    }

    fun save() {
        val e = _editor.value ?: return
        if (e.busy) return
        e.draft.problem()?.let { p -> _editor.update { it?.copy(error = p) }; return }
        _editor.value = e.copy(busy = true, error = null)
        viewModelScope.launch {
            repository.saveVlan(e.isNew, e.draft).fold(
                onSuccess = { v ->
                    _editor.value = null
                    _messages.tryEmit(if (e.isNew) "VLAN ${v.id} добавлен" else "VLAN ${v.id} сохранён")
                    load()
                },
                onFailure = { err -> _editor.update { it?.copy(busy = false, error = err.toUserMessage()) } },
            )
        }
    }

    fun delete(id: Int) {
        _editor.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            repository.deleteVlan(id).fold(
                onSuccess = {
                    _editor.value = null
                    _messages.tryEmit("VLAN $id удалён")
                    load()
                },
                onFailure = { err -> _editor.update { it?.copy(busy = false, error = err.toUserMessage()) } },
            )
        }
    }
}
