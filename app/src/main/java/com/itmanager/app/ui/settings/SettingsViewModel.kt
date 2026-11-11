package com.itmanager.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.NetAdminApi
import com.itmanager.app.core.network.ServerFailover
import com.itmanager.app.core.nfc.DeepLink
import com.itmanager.app.core.nfc.DeepLinkInbox
import com.itmanager.app.core.network.runCatchingCancellable
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.core.settings.ConnectQr
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
    /** Shown under the field only after the user has typed, never on an untouched form. */
    val urlError: String? = null,
    val check: ConnectionCheck = ConnectionCheck.Idle,
    val saved: ServerSettings? = null,
    val appLock: Boolean = true,
    /** A second server the app turns to while the main one cannot be reached. */
    val backupUrl: String = "",
    val backupToken: String = "",
    /** Who is using the app, for the journal. */
    val operatorName: String = "",
    val backupError: String? = null,
    val backupCheck: ConnectionCheck = ConnectionCheck.Idle,
    /** Requests go to the backup server right now. */
    val onBackup: Boolean = false,
) {
    val hasChanges: Boolean
        get() = saved != null && (
            saved.baseUrl != baseUrl.trim() || saved.apiToken != apiToken.trim() ||
                saved.backupUrl != backupUrl.trim() || saved.backupToken != backupToken.trim() ||
                saved.operatorName != operatorName.trim()
            )
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val api: NetAdminApi,
    private val failover: ServerFailover,
    private val links: DeepLinkInbox,
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
                backupUrl = current.backupUrl,
                backupToken = current.backupToken,
                operatorName = current.operatorName,
                saved = current,
            )
            launch { failover.onBackup.collect { on -> _state.update { it.copy(onBackup = on) } } }
            // A connection code scanned with the camera: the form fills in as from the app's own scanner.
            links.take<DeepLink.Connect>()?.let { applyScanned(it.code) }
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

    fun setOperatorName(value: String) = _state.update { it.copy(operatorName = value.take(60)) }

    fun setBackupUrl(value: String) = _state.update { it.copy(backupUrl = value, backupError = null, backupCheck = ConnectionCheck.Idle) }

    fun setBackupToken(value: String) = _state.update { it.copy(backupToken = value) }

    fun checkBackup() {
        val url = when (val r = ServerUrl.check(_state.value.backupUrl)) {
            is ServerUrlCheck.Invalid -> {
                _state.update { it.copy(backupError = r.reason) }
                return
            }
            is ServerUrlCheck.Valid -> r.normalized
        }
        viewModelScope.launch {
            _state.update { it.copy(backupUrl = url, backupError = null, backupCheck = ConnectionCheck.Running) }
            val result = runCatchingCancellable { api.health(url).toDomain() }
            _state.update {
                it.copy(
                    backupCheck = result.fold(
                        onSuccess = { health -> ConnectionCheck.Ok(health) },
                        onFailure = { e -> ConnectionCheck.Failed(e.toUserMessage()) },
                    ),
                )
            }
        }
    }

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

    /**
     * Text read from a QR code. A NetAdmin connection code fills the form;
     * the address is the first one in the code that answers. Nothing is saved until the user taps Save.
     */
    fun applyScanned(text: String) {
        val qr = ConnectQr.parse(text)
        if (qr == null) {
            _state.update { it.copy(check = ConnectionCheck.Failed("Это не код подключения NetAdmin. Его показывает netadmin-agent info на сервере.")) }
            return
        }
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _state.update {
                it.copy(baseUrl = qr.urls.first(), apiToken = qr.token, urlError = null, check = ConnectionCheck.Running)
            }
            var lastError: Throwable? = null
            for (url in qr.urls) {
                val result = runCatchingCancellable { api.health(url).toDomain() }
                val health = result.getOrNull()
                if (health != null) {
                    _state.update { it.copy(baseUrl = url, check = ConnectionCheck.Ok(health)) }
                    return@launch
                }
                lastError = result.exceptionOrNull()
            }
            val tried = qr.urls.joinToString(", ") { it.removePrefix("http://") }
            _state.update {
                it.copy(
                    check = ConnectionCheck.Failed(
                        "Код прочитан, но сервер не отвечает ($tried): ${lastError?.toUserMessage().orEmpty().trimEnd('.')}. " +
                            "Проверьте, что телефон в сети завода или в VPN.",
                    ),
                )
            }
        }
    }

    /** The scanner could not start (no Google Play services, the module not downloaded yet). Cancel is not a failure. */
    fun scanFailed() = _state.update {
        it.copy(check = ConnectionCheck.Failed("Сканер QR-кодов недоступен: нужны сервисы Google Play и интернет при первом запуске. Введите адрес и токен вручную."))
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
        if (url.isEmpty()) {
            _state.update { it.copy(urlError = "Укажите адрес сервера NetAdmin") }
            return false
        }
        val backup = if (s.backupUrl.isBlank()) {
            ""
        } else {
            when (val r = ServerUrl.check(s.backupUrl)) {
                is ServerUrlCheck.Invalid -> {
                    _state.update { it.copy(backupError = r.reason) }
                    return false
                }
                is ServerUrlCheck.Valid -> r.normalized
            }
        }
        if (backup.isNotEmpty() && backup == url) {
            _state.update { it.copy(backupError = "Резервный сервер должен быть другим, не основным") }
            return false
        }
        val new = ServerSettings(baseUrl = url, apiToken = s.apiToken.trim(), backupUrl = backup, backupToken = s.backupToken.trim(),
            operatorName = s.operatorName.trim(),
        )
        _state.update { it.copy(baseUrl = url, backupUrl = backup, saved = new) }
        viewModelScope.launch { settings.save(new) }
        return true
    }
}
