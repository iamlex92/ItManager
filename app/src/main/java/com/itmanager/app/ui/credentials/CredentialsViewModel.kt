package com.itmanager.app.ui.credentials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Credential
import com.itmanager.app.data.model.CredentialDraft
import com.itmanager.app.data.model.CredentialKind
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

/** The add/edit sheet. [editing] is null for a new login. The secret field always starts empty. */
data class CredentialEditor(
    val editing: Credential?,
    val draft: CredentialDraft,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val isNew: Boolean get() = editing == null

    companion object {
        fun new() = CredentialEditor(null, CredentialDraft("", CredentialKind.SSH, "", "", ""))

        fun edit(c: Credential) = CredentialEditor(c, CredentialDraft(c.name, c.kind, c.username.orEmpty(), "", c.notes.orEmpty()))
    }
}

@HiltViewModel
class CredentialsViewModel @Inject constructor(
    private val repository: DcimRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Credential>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Credential>>> = _state.asStateFlow()

    private val _editor = MutableStateFlow<CredentialEditor?>(null)
    val editor: StateFlow<CredentialEditor?> = _editor.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { repository.settings.collect { load() } }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
        repository.credentials().fold(
            onSuccess = { list -> _state.value = UiState.Content(list) },
            onFailure = { e ->
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

    fun openNew() {
        _editor.value = CredentialEditor.new()
    }

    fun openEdit(credential: Credential) {
        _editor.value = CredentialEditor.edit(credential)
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun updateDraft(transform: (CredentialDraft) -> CredentialDraft) =
        _editor.update { it?.copy(draft = transform(it.draft), error = null) }

    /** The form's own check, before the screen asks for biometrics. */
    fun validate(): Boolean {
        val e = _editor.value ?: return false
        val problem = e.draft.problem(e.isNew) ?: return true
        _editor.update { it?.copy(error = problem) }
        return false
    }

    /** Called after the operator confirmed with biometrics. */
    fun save() {
        val e = _editor.value ?: return
        if (e.saving || !validate()) return
        _editor.update { it?.copy(saving = true) }
        viewModelScope.launch {
            repository.saveCredential(e.editing?.id, e.draft).fold(
                onSuccess = { saved ->
                    _editor.value = null
                    _messages.tryEmit(if (e.isNew) "«${saved.name}» сохранено" else "«${saved.name}» обновлено")
                    load()
                },
                onFailure = { err -> _editor.update { it?.copy(saving = false, error = err.toUserMessage()) } },
            )
        }
    }

    /** Called after the operator confirmed with biometrics. */
    fun delete(credential: Credential) {
        viewModelScope.launch {
            repository.deleteCredential(credential.id).fold(
                onSuccess = {
                    _messages.tryEmit("«${credential.name}» удалено")
                    load()
                },
                onFailure = { err -> _messages.tryEmit(err.toUserMessage()) },
            )
        }
    }
}
