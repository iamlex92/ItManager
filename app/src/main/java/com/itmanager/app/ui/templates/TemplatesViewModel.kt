package com.itmanager.app.ui.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.DeviceTemplate
import com.itmanager.app.data.model.DeviceTemplateDraft
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TemplatesViewModel @Inject constructor(
    private val repository: DcimRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<DeviceTemplate>>>(UiState.Loading)
    val state: StateFlow<UiState<List<DeviceTemplate>>> = _state.asStateFlow()

    /** One-line outcome of the last action: an error or "device added". Shown once. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = repository.deviceTemplates().fold(
                onSuccess = { UiState.Content(it) },
                onFailure = { UiState.Error(it.toUserMessage()) },
            )
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun save(draft: DeviceTemplateDraft) {
        viewModelScope.launch {
            repository.saveDeviceTemplate(draft).fold(
                onSuccess = { load() },
                onFailure = { _message.value = it.toUserMessage() },
            )
        }
    }

    fun delete(template: DeviceTemplate) {
        viewModelScope.launch {
            repository.deleteDeviceTemplate(template.id).fold(
                onSuccess = { load() },
                onFailure = { _message.value = it.toUserMessage() },
            )
        }
    }

    fun createDevice(template: DeviceTemplate, name: String, ip: String) {
        viewModelScope.launch {
            repository.createDeviceFromTemplate(template.id, name, ip).fold(
                onSuccess = { _message.value = "Добавлено: ${it.name}" },
                onFailure = { _message.value = it.toUserMessage() },
            )
        }
    }
}
