package com.itmanager.app.ui.gpo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.GpoApplyJob
import com.itmanager.app.data.model.GpoChange
import com.itmanager.app.data.model.GpoDetail
import com.itmanager.app.data.model.GpoOu
import com.itmanager.app.data.model.GpoOverview
import com.itmanager.app.data.model.GpoScope
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The GPO being looked at, with what was loaded for it on demand. */
data class GpoPage(
    val id: String,
    val detail: GpoDetail? = null,
    val error: String? = null,
    val scope: GpoScope? = null,
    val scopeLoading: Boolean = false,
    val job: GpoApplyJob? = null,
)

@HiltViewModel
class GpoViewModel @Inject constructor(private val repository: DcimRepository) : ViewModel() {

    private val _overview = MutableStateFlow<UiState<GpoOverview>>(UiState.Loading)
    val overview: StateFlow<UiState<GpoOverview>> = _overview.asStateFlow()

    private val _ous = MutableStateFlow<List<GpoOu>>(emptyList())
    val ous: StateFlow<List<GpoOu>> = _ous.asStateFlow()

    private val _page = MutableStateFlow<GpoPage?>(null)
    val page: StateFlow<GpoPage?> = _page.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var poll: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            if (_overview.value !is UiState.Content) _overview.value = UiState.Loading
            _overview.value = repository.gpos().fold({ UiState.Content(it) }, { UiState.Error(it.toUserMessage()) })
            repository.gpoOus().onSuccess { _ous.value = it }
        }
    }

    fun open(id: String) {
        _page.value = GpoPage(id)
        viewModelScope.launch {
            repository.gpo(id)
                .onSuccess { d -> update(id) { it.copy(detail = d, error = null) } }
                .onFailure { e -> update(id) { it.copy(error = e.toUserMessage()) } }
        }
    }

    fun close() {
        poll?.cancel()
        _page.value = null
    }

    fun loadScope() {
        val id = _page.value?.id ?: return
        update(id) { it.copy(scopeLoading = true) }
        viewModelScope.launch {
            repository.gpoComputers(id)
                .onSuccess { s -> update(id) { it.copy(scope = s, scopeLoading = false) } }
                .onFailure { e ->
                    update(id) { it.copy(scopeLoading = false) }
                    _messages.tryEmit(e.toUserMessage())
                }
        }
    }

    fun fail(message: String) {
        _messages.tryEmit(message)
    }

    /** A change confirmed with biometrics: [path] under gpo/{id}/ ("status", "links", "registry/remove"…). */
    fun change(method: String, path: String, change: GpoChange, authMethod: AuthMethod) {
        val id = _page.value?.id ?: return
        run {
            repository.gpoChange(method, "$id/$path", change, authMethod)
                .onSuccess { d ->
                    update(id) { it.copy(detail = d, scope = null) }
                    _messages.tryEmit("Изменено")
                    refreshList()
                }
                .onFailure { _messages.tryEmit(it.toUserMessage()) }
        }
    }

    fun create(name: String, authMethod: AuthMethod) = run {
        repository.gpoChange("POST", "", GpoChange(name = name.trim()), authMethod)
            .onSuccess { d ->
                _messages.tryEmit("Политика создана. Свяжите её с подразделением, чтобы она начала действовать.")
                refreshList()
                _page.value = GpoPage(d.gpo.id, detail = d)
            }
            .onFailure { _messages.tryEmit(it.toUserMessage()) }
    }

    fun delete(authMethod: AuthMethod) {
        val id = _page.value?.id ?: return
        run {
            repository.gpoDelete(id, authMethod)
                .onSuccess {
                    _messages.tryEmit("Политика удалена")
                    _page.value = null
                    refreshList()
                }
                .onFailure { _messages.tryEmit(it.toUserMessage()) }
        }
    }

    /** gpupdate /force on the computers it reaches (null: all of them), then the progress until done. */
    fun apply(computers: List<String>?, authMethod: AuthMethod) {
        val id = _page.value?.id ?: return
        run {
            repository.gpoApply(id, computers, authMethod)
                .onSuccess { job -> update(id) { it.copy(job = job) }; follow(id, job.id) }
                .onFailure { _messages.tryEmit(it.toUserMessage()) }
        }
    }

    private fun follow(gpoId: String, jobId: String) {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (true) {
                delay(POLL_MS)
                val job = repository.gpoApplyJob(jobId).getOrNull() ?: return@launch
                update(gpoId) { it.copy(job = job) }
                if (!job.running) return@launch
            }
        }
    }

    private fun refreshList() {
        viewModelScope.launch {
            repository.gpos().onSuccess { _overview.value = UiState.Content(it) }
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun update(id: String, f: (GpoPage) -> GpoPage) {
        val p = _page.value
        if (p?.id == id) _page.value = f(p)
    }

    private companion object {
        const val POLL_MS = 1_500L
    }
}
