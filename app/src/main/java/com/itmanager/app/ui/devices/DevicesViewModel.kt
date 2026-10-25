package com.itmanager.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.navigation.MapFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Filter chips: what an IT manager looks for, with passive cabling grouped together. */
enum class DeviceFilter(val label: String, val types: Set<DeviceType>?) {
    ALL("Все", null),
    SWITCHES("Коммутаторы", setOf(DeviceType.SWITCH)),
    HOSTS("Рабочие места", setOf(DeviceType.WORKSTATION, DeviceType.SERVER)),
    PRINTERS("МФУ", setOf(DeviceType.PRINTER)),
    CAMERAS("Камеры", setOf(DeviceType.CAMERA, DeviceType.ACCESS_POINT)),
    PASSIVE("Кабельное", setOf(DeviceType.SOCKET, DeviceType.PATCH_PANEL)),
}

data class DevicesUiState(
    val devices: UiState<List<Device>> = UiState.Loading,
    val query: String = "",
    val filter: DeviceFilter = DeviceFilter.ALL,
    val offlineCount: Int = 0,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val repository: DcimRepository,
    private val mapFocus: MapFocus,
) : ViewModel() {

    private val all = MutableStateFlow<UiState<List<Device>>>(UiState.Loading)
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(DeviceFilter.ALL)

    val state: StateFlow<DevicesUiState> = combine(all, query, filter) { devices, q, f ->
        val visible = when (devices) {
            is UiState.Content -> devices.copy(
                data = devices.data
                    .filter { f.types == null || it.type in f.types }
                    .filter { it.matches(q) }
                    .sortedWith(compareBy<Device> { it.type.ordinal }.thenBy { it.name }),
            )
            else -> devices
        }
        val offline = (devices as? UiState.Content)?.data
            ?.count { !it.isOnline && it.type != DeviceType.SOCKET && it.type != DeviceType.PATCH_PANEL } ?: 0
        DevicesUiState(visible, q, f, offline)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    init {
        viewModelScope.launch { repository.settings.collect { load() } }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        all.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
        repository.devices()
            .onSuccess { list -> all.value = UiState.Content(list) }
            .onFailure { e -> all.value = UiState.Error(e.toUserMessage()) }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setFilter(value: DeviceFilter) {
        filter.value = value
    }

    fun showOnMap(deviceId: String) = mapFocus.request(deviceId)
}
