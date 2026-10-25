package com.itmanager.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.NetAdminApi
import com.itmanager.app.core.network.runCatchingCancellable
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.core.settings.ServerSettings
import com.itmanager.app.core.settings.ServerUrl
import com.itmanager.app.core.settings.ServerUrlCheck
import com.itmanager.app.core.settings.SettingsRepository
import com.itmanager.app.data.model.ServerHealth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectionCheck {
    data object Idle : ConnectionCheck
    data object Running : ConnectionCheck
    data class Ok(val health: ServerHealth) : ConnectionCheck
    data class Failed(val message: String) : ConnectionCheck
}

data class SettingsUiState(
    val loaded: Boolean = false,
    val baseUrl: String = "",
    val apiToken: String = "",
    val demoMode: Boolean = true,
    /** Shown under the field only after the user has typed, never on an untouched form. */
    val urlError: String? = null,
    val check: ConnectionCheck = ConnectionCheck.Idle,
    val saved: ServerSettings? = null,
    val appLock: Boolean = true,
) {
    val hasChanges: Boolean
        get() = saved != null && (saved.baseUrl != baseUrl.trim() || saved.apiToken != apiToken.trim() || saved.demoMode != demoMode)
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val api: NetAdminApi,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            val current = settings.current()
            _state.value = SettingsUiState(
                loaded = true,
                baseUrl = current.baseUrl,
                apiToken = current.apiToken,
                demoMode = current.demoMode,
                saved = current,
            )
            // Applied at once, apart from the server form and its Save button.
            settings.appLock.collect { on -> _state.update { it.copy(appLock = on) } }
        }
    }

    /** Turning the lock off is confirmed with biometrics by the screen first. */
    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch { settings.setAppLock(enabled) }
    }

    fun setBaseUrl(value: String) = _state.update {
        val error = if (value.isBlank()) null else (ServerUrl.check(value) as? ServerUrlCheck.Invalid)?.reason
        it.copy(baseUrl = value, urlError = error, check = ConnectionCheck.Idle)
    }

    fun setApiToken(value: String) = _state.update { it.copy(apiToken = value) }

    fun setDemoMode(value: Boolean) = _state.update { it.copy(demoMode = value) }

    fun checkConnection() {
        val url = when (val r = ServerUrl.check(_state.value.baseUrl)) {
            is ServerUrlCheck.Invalid -> {
                _state.update { it.copy(urlError = r.reason) }
                return
            }
            is ServerUrlCheck.Valid -> r.normalized
        }
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _state.update { it.copy(baseUrl = url, urlError = null, check = ConnectionCheck.Running) }
            val result = runCatchingCancellable { api.health(url).toDomain() }
            _state.update {
                it.copy(
                    check = result.fold(
                        onSuccess = { health -> ConnectionCheck.Ok(health) },
                        onFailure = { e -> ConnectionCheck.Failed(e.toUserMessage()) },
                    ),
                )
            }
        }
    }

    /** Returns false and shows the reason when the form cannot be saved as is. */
    fun save(): Boolean {
        val s = _state.value
        val url = if (s.baseUrl.isBlank()) {
            ""
        } else {
            when (val r = ServerUrl.check(s.baseUrl)) {
                is ServerUrlCheck.Invalid -> {
                    _state.update { it.copy(urlError = r.reason) }
                    return false
                }
                is ServerUrlCheck.Valid -> r.normalized
            }
        }
        if (!s.demoMode && url.isEmpty()) {
            _state.update { it.copy(urlError = "Без адреса сервера работают только демо-данные") }
            return false
        }
        val new = ServerSettings(baseUrl = url, apiToken = s.apiToken.trim(), demoMode = s.demoMode)
        _state.update { it.copy(baseUrl = url, saved = new) }
        viewModelScope.launch { settings.save(new) }
        return true
    }
}
