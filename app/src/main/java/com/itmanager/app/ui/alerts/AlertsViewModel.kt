package com.itmanager.app.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.alerts.AlertCenter
import com.itmanager.app.data.model.Alert
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlertRow(val alert: Alert, val deviceName: String?)

data class AlertsList(val rows: List<AlertRow>, val openCount: Int, val resolvedCount: Int)

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val center: AlertCenter,
) : ViewModel() {

    private val _showResolved = MutableStateFlow(false)
    val showResolved: StateFlow<Boolean> = _showResolved.asStateFlow()

    /** Alerts being marked resolved right now, so their button waits instead of firing twice. */
    private val _resolving = MutableStateFlow<Set<String>>(emptySet())
    val resolving: StateFlow<Set<String>> = _resolving.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val state: StateFlow<UiState<AlertsList>> = combine(center.state, _showResolved, center.devices) { s, showResolved, _ ->
        when (s) {
            UiState.Loading -> UiState.Loading
            is UiState.Error -> UiState.Error(s.message)
            is UiState.Content -> {
                val visible = s.data.filter { showResolved || !it.isResolved }
                UiState.Content(
                    AlertsList(
                        rows = visible.map { AlertRow(it, center.nameOf(it.deviceId)) },
                        openCount = s.data.count { !it.isResolved },
                        resolvedCount = s.data.count { it.isResolved },
                    ),
                    isRefreshing = s.isRefreshing,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun setShowResolved(show: Boolean) {
        _showResolved.value = show
    }

    fun refresh() {
        viewModelScope.launch { center.reload() }
    }

    /** Marks the incident handled in the server's journal. Nothing on the equipment changes. */
    fun resolve(alert: Alert) {
        if (alert.id in _resolving.value) return
        _resolving.update { it + alert.id }
        viewModelScope.launch {
            center.resolve(alert.id).onFailure { _messages.emit(it.toUserMessage()) }
            _resolving.update { it - alert.id }
        }
    }
}
