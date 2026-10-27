package com.itmanager.app.ui.ipam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.IpamCheck
import com.itmanager.app.data.model.IpamOverview
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IpamCheckState(
    val ip: String = "",
    val busy: Boolean = false,
    val result: IpamCheck? = null,
    val error: String? = null,
)

@HiltViewModel
class IpamViewModel @Inject constructor(
    private val repository: DcimRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<IpamOverview>>(UiState.Loading)
    val state: StateFlow<UiState<IpamOverview>> = _state.asStateFlow()

    private val _check = MutableStateFlow(IpamCheckState())
    val check: StateFlow<IpamCheckState> = _check.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = repository.ipam().fold(
                onSuccess = { UiState.Content(it) },
                onFailure = { UiState.Error(it.toUserMessage()) },
            )
        }
    }

    fun setIp(ip: String) = _check.update { it.copy(ip = ip, result = null, error = null) }

    /** Asks whether the address is free and inside a known subnet. Assigns nothing. */
    fun checkAddress() {
        val ip = _check.value.ip.trim()
        if (ip.isEmpty() || _check.value.busy) return
        viewModelScope.launch {
            _check.update { it.copy(busy = true, error = null) }
            repository.ipamCheck(ip).fold(
                onSuccess = { r -> _check.update { it.copy(busy = false, result = r) } },
                onFailure = { e -> _check.update { it.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }
}
