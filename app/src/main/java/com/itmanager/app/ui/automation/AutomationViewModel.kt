package com.itmanager.app.ui.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.NetAdminApi
import com.itmanager.app.core.network.runCatchingCancellable
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.Runbook
import com.itmanager.app.data.model.RunbookRun
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerRunbooks(val real: Boolean, val writes: Boolean)

/** One open runbook: the form, then the run's outcome. */
data class RunbookSession(
    val runbook: Runbook,
    val values: Map<String, String> = emptyMap(),
    val running: Boolean = false,
    val error: String? = null,
    val result: RunbookRun? = null,
)

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val repository: DcimRepository,
    private val api: NetAdminApi,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Runbook>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Runbook>>> = _state.asStateFlow()

    private val _session = MutableStateFlow<RunbookSession?>(null)
    val session: StateFlow<RunbookSession?> = _session.asStateFlow()

    val isDemo: StateFlow<Boolean> = repository.settings.map { it.demoMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** The server's runbook mode from /health: emulator or real AD; null in demo mode or while unknown. */
    private val _serverMode = MutableStateFlow<ServerRunbooks?>(null)
    val serverMode: StateFlow<ServerRunbooks?> = _serverMode.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _serverMode.value = null
                if (!settings.demoMode && settings.baseUrl.isNotBlank()) {
                    launch {
                        runCatchingCancellable { api.health(settings.baseUrl) }.getOrNull()?.let { h ->
                            _serverMode.value = ServerRunbooks(real = h.runbookMode == "real", writes = h.runbookWritesEnabled == true)
                        }
                    }
                }
                load()
            }
        }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    fun open(runbook: Runbook) {
        _session.value = RunbookSession(runbook)
    }

    /** Closing drops the result too, so a temporary password does not outlive the sheet. */
    fun close() {
        if (_session.value?.running == true) return
        _session.value = null
    }

    fun setValue(name: String, value: String) = _session.update {
        it?.copy(values = it.values + (name to value), error = null)
    }

    /** Back to the form for another run, keeping what was typed. */
    fun again() = _session.update { it?.copy(result = null, error = null) }

    /** Null when the form can be sent; otherwise what to fix. Checked before asking for biometrics. */
    fun validate(): String? {
        val s = _session.value ?: return "Сценарий не выбран"
        val missing = s.runbook.firstMissing(s.values) ?: return null
        val message = "Заполните поле «${missing.label}»"
        _session.update { it?.copy(error = message) }
        return message
    }

    fun fail(message: String) = _session.update { it?.copy(error = message) }

    fun run(authMethod: AuthMethod) {
        val s = _session.value ?: return
        if (s.running) return
        _session.value = s.copy(running = true, error = null, result = null)
        viewModelScope.launch {
            repository.runRunbook(s.runbook, s.runbook.arguments(s.values), authMethod)
                .onSuccess { run -> _session.update { it?.copy(running = false, result = run) } }
                .onFailure { e -> _session.update { it?.copy(running = false, error = e.toUserMessage()) } }
        }
    }

    private suspend fun load() {
        _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
        repository.runbooks()
            .onSuccess { _state.value = UiState.Content(it) }
            .onFailure { _state.value = UiState.Error(it.toUserMessage()) }
    }
}
