package com.itmanager.app.ui.configdiff

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.ConfigDiff
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.navigation.ConfigDiffRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConfigDiffViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DcimRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ConfigDiffRoute>()

    private val _state = MutableStateFlow<UiState<ConfigDiff>>(UiState.Loading)
    val state: StateFlow<UiState<ConfigDiff>> = _state.asStateFlow()

    init {
        load()
    }

    /** Compares the latest stored copy with the live running-config (reads the switch, changes nothing). */
    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = repository.configDiff(route.deviceId).fold(
                onSuccess = { UiState.Content(it) },
                onFailure = { UiState.Error(it.toUserMessage()) },
            )
        }
    }

    /** Stores the current running-config as the new reference copy, then compares again. */
    fun backup() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            repository.backupConfig(route.deviceId)
                .onSuccess { load() }
                .onFailure { _state.value = UiState.Error(it.toUserMessage()) }
        }
    }
}
