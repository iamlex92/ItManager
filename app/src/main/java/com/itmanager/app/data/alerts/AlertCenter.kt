package com.itmanager.app.data.alerts

import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.ALL_DEVICES
import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.sortedForDisplay
import com.itmanager.app.data.model.upsert
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The alert journal shared by the alerts screen, the bell badge and in-app notifications.
 * Loaded from the server, then kept current by the live channel while the app is on screen.
 */
@Singleton
class AlertCenter @Inject constructor(
    private val repository: DcimRepository,
) {
    private val _state = MutableStateFlow<UiState<List<Alert>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Alert>>> = _state.asStateFlow()

    /** Devices by id, to name the source of an alert and to open it. */
    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val devices: StateFlow<Map<String, Device>> = _devices.asStateFlow()

    /** Printers are named separately: some are known only to the SNMP poller. */
    private val _printerNames = MutableStateFlow<Map<String, String>>(emptyMap())

    fun nameOf(deviceId: String): String? = _devices.value[deviceId]?.name ?: _printerNames.value[deviceId]

    /** Reloads whenever the server settings change, for as long as [scope] lives. */
    fun start(scope: CoroutineScope) {
        scope.launch { repository.settings.collectLatest { reload() } }
    }

    suspend fun reload() = coroutineScope {
        _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
        val devices = async { repository.devices() }
        val printers = async { repository.printers() }
        val alerts = repository.alerts()
        devices.await().onSuccess { list -> _devices.value = list.associateBy { it.id } }
        printers.await().onSuccess { list -> _printerNames.value = list.associate { it.id to it.name } }
        _state.value = alerts.fold(
            onSuccess = { UiState.Content(it.sortedForDisplay()) },
            onFailure = { e ->
                // Keep what is on screen when only a refresh failed.
                (_state.value as? UiState.Content)?.copy(isRefreshing = false) ?: UiState.Error(e.toUserMessage())
            },
        )
    }

    /**
     * Follows the live channel; returns only when cancelled. [onFresh] gets alerts that are new and still open,
     * which is what deserves a notification.
     */
    suspend fun follow(onFresh: (Alert) -> Unit) {
        var connectedOnce = false
        repository.live(ALL_DEVICES).collect { event ->
            when (event) {
                // After a reconnect, catch up on what happened while the socket was down.
                LiveEvent.Connected -> {
                    if (connectedOnce) reload()
                    connectedOnce = true
                }
                is LiveEvent.AlertRaised -> {
                    val alert = event.alert
                    var isNew = false
                    _state.update { s ->
                        val content = s as? UiState.Content ?: return@update s
                        isNew = content.data.none { it.id == alert.id }
                        content.copy(data = content.data.upsert(alert).sortedForDisplay())
                    }
                    if (isNew && !alert.isResolved) onFresh(alert)
                }
                else -> Unit
            }
        }
    }

    suspend fun resolve(alertId: String): Result<Alert> =
        repository.resolveAlert(alertId).onSuccess { resolved ->
            _state.update { s -> if (s is UiState.Content) s.copy(data = s.data.upsert(resolved).sortedForDisplay()) else s }
        }
}
