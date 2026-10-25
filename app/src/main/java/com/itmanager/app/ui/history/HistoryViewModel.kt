package com.itmanager.app.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.DeviceHistoryEntry
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.navigation.HistoryRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DcimRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<HistoryRoute>()
    val switchName: String = route.switchName

    private val _state = MutableStateFlow<UiState<List<DeviceHistoryEntry>>>(UiState.Loading)
    val state: StateFlow<UiState<List<DeviceHistoryEntry>>> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
            _state.value = repository.deviceHistory(route.deviceId).fold(
                onSuccess = { UiState.Content(it) },
                onFailure = { UiState.Error(it.toUserMessage()) },
            )
        }
    }
}
