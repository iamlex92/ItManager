package com.itmanager.app.ui.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.TaskDraft
import com.itmanager.app.data.model.TaskItem
import com.itmanager.app.data.model.toDraft
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The task form: [editing] null means a new task. */
data class TaskEditor(
    val editing: TaskItem?,
    val draft: TaskDraft,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TasksViewModel @Inject constructor(private val repository: DcimRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<TaskItem>>>(UiState.Loading)
    val state: StateFlow<UiState<List<TaskItem>>> = _state.asStateFlow()

    private val _showDone = MutableStateFlow(false)
    val showDone: StateFlow<Boolean> = _showDone.asStateFlow()

    private val _editor = MutableStateFlow<TaskEditor?>(null)
    val editor: StateFlow<TaskEditor?> = _editor.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else it }
        repository.tasks(_showDone.value).fold(
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

    fun setShowDone(value: Boolean) {
        _showDone.value = value
        load()
    }

    /** Ticks a task off, or brings a finished one back. A repeating task makes its next one by itself. */
    fun toggle(item: TaskItem) {
        viewModelScope.launch {
            val result = if (item.isDone) repository.reopenTask(item.id) else repository.completeTask(item.id)
            result.onFailure { _messages.tryEmit(it.toUserMessage()) }
            reload()
        }
    }

    fun openNew(date: String = "") {
        _editor.value = TaskEditor(null, TaskDraft(dueDate = date))
    }

    fun openEdit(item: TaskItem) {
        _editor.value = TaskEditor(item, item.toDraft())
    }

    fun updateDraft(draft: TaskDraft) = _editor.update { it?.copy(draft = draft, error = null) }

    fun closeEditor() {
        _editor.value = null
    }

    fun save() {
        val editor = _editor.value ?: return
        if (editor.busy || !editor.draft.canSave) return
        _editor.value = editor.copy(busy = true, error = null)
        viewModelScope.launch {
            repository.saveTask(editor.editing?.id, editor.draft).fold(
                onSuccess = {
                    _editor.value = null
                    _messages.tryEmit(if (editor.editing == null) "Задача добавлена" else "Сохранено")
                    reload()
                },
                onFailure = { e -> _editor.update { it?.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }

    fun delete(item: TaskItem) {
        viewModelScope.launch {
            repository.deleteTask(item.id).fold(
                onSuccess = {
                    _editor.value = null
                    _messages.tryEmit("Задача удалена")
                    reload()
                },
                onFailure = { e -> _editor.update { it?.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }
}
