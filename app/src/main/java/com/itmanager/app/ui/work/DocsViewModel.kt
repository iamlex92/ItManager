package com.itmanager.app.ui.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.DocBrief
import com.itmanager.app.data.model.DocDraft
import com.itmanager.app.data.model.DocFull
import com.itmanager.app.data.model.toDraft
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The document form: [editing] null means a new document. */
data class DocEditor(
    val editing: DocFull?,
    val draft: DocDraft,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DocsViewModel @Inject constructor(private val repository: DcimRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<DocBrief>>>(UiState.Loading)
    val state: StateFlow<UiState<List<DocBrief>>> = _state.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The document being read. */
    private val _opened = MutableStateFlow<DocFull?>(null)
    val opened: StateFlow<DocFull?> = _opened.asStateFlow()

    private val _editor = MutableStateFlow<DocEditor?>(null)
    val editor: StateFlow<DocEditor?> = _editor.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var searchJob: Job? = null

    init {
        load()
    }

    fun load() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else it }
        repository.documents(_query.value).fold(
            onSuccess = { _state.value = UiState.Content(it) },
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

    /** Looks as the user types, after a short pause. */
    fun setQuery(text: String) {
        _query.value = text.take(100)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            reload()
        }
    }

    fun open(brief: DocBrief) {
        viewModelScope.launch {
            repository.document(brief.id).fold(
                onSuccess = { _opened.value = it },
                onFailure = { _messages.tryEmit(it.toUserMessage()) },
            )
        }
    }

    fun closeReader() {
        _opened.value = null
    }

    fun openNew() {
        _editor.value = DocEditor(null, DocDraft())
    }

    fun openEdit(doc: DocFull) {
        _editor.value = DocEditor(doc, doc.toDraft())
    }

    fun updateDraft(draft: DocDraft) = _editor.update { it?.copy(draft = draft, error = null) }

    fun closeEditor() {
        _editor.value = null
    }

    fun save() {
        val editor = _editor.value ?: return
        if (editor.busy || !editor.draft.canSave) return
        _editor.value = editor.copy(busy = true, error = null)
        viewModelScope.launch {
            repository.saveDocument(editor.editing?.id, editor.draft).fold(
                onSuccess = { saved ->
                    _editor.value = null
                    // A document that was being read shows what was saved.
                    if (_opened.value != null) _opened.value = saved
                    _messages.tryEmit(if (editor.editing == null) "Документ добавлен" else "Сохранено")
                    reload()
                },
                onFailure = { e -> _editor.update { it?.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }

    fun delete(doc: DocFull) {
        viewModelScope.launch {
            repository.deleteDocument(doc.id).fold(
                onSuccess = {
                    _editor.value = null
                    _opened.value = null
                    _messages.tryEmit("Документ удалён")
                    reload()
                },
                onFailure = { e -> _editor.update { it?.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }
}
