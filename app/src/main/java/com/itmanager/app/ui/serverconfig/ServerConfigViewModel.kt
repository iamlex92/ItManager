package com.itmanager.app.ui.serverconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Credential
import com.itmanager.app.data.model.ServerConfig
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/** What the operator changed and has not saved yet. */
data class ServerConfigEdits(
    val values: Map<String, JsonElement> = emptyMap(),
    /** Back to netadmin.env. */
    val reset: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = values.isEmpty() && reset.isEmpty()
}

@HiltViewModel
class ServerConfigViewModel @Inject constructor(private val repository: DcimRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ServerConfig>>(UiState.Loading)
    val state: StateFlow<UiState<ServerConfig>> = _state.asStateFlow()

    private val _edits = MutableStateFlow(ServerConfigEdits())
    val edits: StateFlow<ServerConfigEdits> = _edits.asStateFlow()

    private val _credentials = MutableStateFlow<List<Credential>>(emptyList())
    val credentials: StateFlow<List<Credential>> = _credentials.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _restarting = MutableStateFlow(false)
    val restarting: StateFlow<Boolean> = _restarting.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            repository.credentials().onSuccess { _credentials.value = it }
            _state.value = repository.serverConfig().fold(
                onSuccess = { UiState.Content(it) },
                onFailure = { UiState.Error(it.toUserMessage()) },
            )
            _edits.value = ServerConfigEdits()
        }
    }

    fun set(key: String, value: JsonElement?) = _edits.update {
        if (value == null) it.copy(values = it.values - key) else it.copy(values = it.values + (key to value), reset = it.reset - key)
    }

    fun reset(key: String) = _edits.update { it.copy(values = it.values - key, reset = it.reset + key) }

    fun fail(message: String) {
        _messages.tryEmit(message)
    }

    /** Called after the operator confirmed with biometrics. */
    fun save() {
        val e = _edits.value
        if (e.isEmpty || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            repository.saveServerConfig(e.values, e.reset.toList())
                .onSuccess { config ->
                    _state.value = UiState.Content(config)
                    _edits.value = ServerConfigEdits()
                    _messages.tryEmit(
                        if (config.restartPending) "Сохранено. Изменения начнут действовать после перезапуска сервера." else "Сохранено",
                    )
                }
                .onFailure { _messages.tryEmit(it.toUserMessage()) }
            _busy.value = false
        }
    }

    /** Asks the server to restart, then waits until it answers again (up to two minutes). */
    fun restart() {
        if (_restarting.value) return
        viewModelScope.launch {
            _restarting.value = true
            repository.restartServer().onFailure {
                _messages.tryEmit(it.toUserMessage())
                _restarting.value = false
                return@launch
            }
            delay(FIRST_CHECK_MS)
            repeat(CHECKS) {
                val back = repository.serverConfig().getOrNull()
                if (back != null) {
                    _state.value = UiState.Content(back)
                    _edits.value = ServerConfigEdits()
                    _restarting.value = false
                    _messages.tryEmit("Сервер перезапущен, настройки действуют")
                    return@launch
                }
                delay(CHECK_MS)
            }
            _restarting.value = false
            _messages.tryEmit("Сервер не ответил за 2 минуты: проверьте службу NetAdmin")
        }
    }

    private companion object {
        const val FIRST_CHECK_MS = 4_000L
        const val CHECK_MS = 2_000L
        const val CHECKS = 58
    }
}
