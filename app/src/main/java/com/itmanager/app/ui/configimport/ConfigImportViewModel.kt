package com.itmanager.app.ui.configimport

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.ConfigImportResult
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.navigation.ConfigImportRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConfigImportState(
    val text: String = "",
    val busy: Boolean = false,
    val result: ConfigImportResult? = null,
    val error: String? = null,
)

@HiltViewModel
class ConfigImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DcimRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ConfigImportRoute>()
    private val deviceId = route.deviceId

    private val _state = MutableStateFlow(ConfigImportState())
    val state: StateFlow<ConfigImportState> = _state.asStateFlow()

    init {
        if (route.fromSwitch) fromSwitch()
    }

    fun setText(text: String) = _state.update { it.copy(text = text, result = null, error = null) }

    /** Takes a copy of the running-config (stored on the server, read-only on the switch) and previews it. */
    fun fromSwitch() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, result = null) }
            repository.backupConfig(deviceId).fold(
                onSuccess = { text ->
                    _state.update { it.copy(text = text, busy = false) }
                    preview()
                },
                onFailure = { e -> _state.update { it.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }

    /** Shows what the text would change in the inventory; writes nothing. */
    fun preview() = run(apply = false)

    /** Saves the previewed changes into the inventory. The switch itself is never touched. */
    fun apply() = run(apply = true)

    private fun run(apply: Boolean) {
        val text = _state.value.text
        if (text.isBlank() || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            repository.importConfig(deviceId, text, apply).fold(
                onSuccess = { r -> _state.update { it.copy(busy = false, result = r) } },
                onFailure = { e -> _state.update { it.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }
}
