package com.itmanager.app.ui.switchdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.navigation.SwitchRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SwitchDetails(val device: Device, val ports: List<SwitchPort>)

/** Whether the port panel is following the switch in real time. */
sealed interface LiveState {
    data object Connecting : LiveState
    data object Live : LiveState
    data class Offline(val reason: String) : LiveState
}

@HiltViewModel
class SwitchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DcimRepository,
) : ViewModel() {

    private val deviceId = savedStateHandle.toRoute<SwitchRoute>().deviceId

    private val _state = MutableStateFlow<UiState<SwitchDetails>>(UiState.Loading)
    val state: StateFlow<UiState<SwitchDetails>> = _state.asStateFlow()

    private val _live = MutableStateFlow<LiveState>(LiveState.Connecting)
    val live: StateFlow<LiveState> = _live.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            repository.live(deviceId).collect(::apply)
        }
    }

    private fun apply(event: LiveEvent) {
        val target = when (event) {
            is LiveEvent.Snapshot -> event.deviceId
            is LiveEvent.PortChanged -> event.deviceId
            is LiveEvent.DeviceStatus -> event.deviceId
            else -> deviceId
        }
        if (target != deviceId) return
        when (event) {
            LiveEvent.Connected -> _live.value = LiveState.Live
            is LiveEvent.Disconnected -> _live.value = LiveState.Offline(event.reason)
            is LiveEvent.Snapshot -> updateDetails { d ->
                d.copy(
                    device = event.isOnline?.let { d.device.copy(isOnline = it) } ?: d.device,
                    ports = event.ports.sortedBy { it.number },
                )
            }
            is LiveEvent.PortChanged -> updateDetails { d ->
                d.copy(ports = d.ports.map { if (it.id == event.port.id) event.port else it })
            }
            is LiveEvent.DeviceStatus -> updateDetails { d -> d.copy(device = d.device.copy(isOnline = event.isOnline)) }
        }
    }

    private inline fun updateDetails(crossinline change: (SwitchDetails) -> SwitchDetails) = _state.update { s ->
        if (s is UiState.Content) s.copy(data = change(s.data)) else s
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
            val device = async { repository.device(deviceId) }
            val ports = async { repository.ports(deviceId) }
            val result = device.await().mapCatching { d -> SwitchDetails(d, ports.await().getOrThrow()) }
            _state.value = result.fold(
                onSuccess = { UiState.Content(it) },
                onFailure = { UiState.Error(it.toUserMessage()) },
            )
        }
    }
}
