package com.itmanager.app.ui.switchdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Device
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

@HiltViewModel
class SwitchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DcimRepository,
) : ViewModel() {

    private val deviceId = savedStateHandle.toRoute<SwitchRoute>().deviceId

    private val _state = MutableStateFlow<UiState<SwitchDetails>>(UiState.Loading)
    val state: StateFlow<UiState<SwitchDetails>> = _state.asStateFlow()

    init {
        refresh()
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
