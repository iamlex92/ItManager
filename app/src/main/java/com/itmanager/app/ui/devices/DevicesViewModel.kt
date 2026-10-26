package com.itmanager.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.export.ExportFormat
import com.itmanager.app.data.export.ExportedFile
import com.itmanager.app.data.export.InventoryExporter
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceBulkChange
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FloorInfo
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.navigation.MapFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    SWITCHES("Сеть", setOf(DeviceType.SWITCH, DeviceType.ROUTER, DeviceType.FIREWALL)),
    HOSTS("Компьютеры", setOf(DeviceType.WORKSTATION, DeviceType.SERVER, DeviceType.VIRTUAL)),
    PRINTERS("МФУ", setOf(DeviceType.PRINTER)),
    CAMERAS("Камеры", setOf(DeviceType.CAMERA, DeviceType.ACCESS_POINT)),
    PASSIVE("Кабельное", setOf(DeviceType.SOCKET, DeviceType.PATCH_PANEL)),
    OTHER("Прочее", setOf(DeviceType.UPS, DeviceType.PHONE, DeviceType.UNKNOWN)),
}

data class DevicesUiState(
    val devices: UiState<List<Device>> = UiState.Loading,
    val query: String = "",
    val filter: DeviceFilter = DeviceFilter.ALL,
    val offlineCount: Int = 0,
    /** Devices picked for a group change; not empty means the list is in selection mode. */
    val selected: Set<String> = emptySet(),
)

/** The group edit dialog: floors and servers to choose from, and how the last attempt went. */
data class BulkEditState(
    val floors: List<FloorInfo> = emptyList(),
    val hosts: List<Device> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val repository: DcimRepository,
    private val mapFocus: MapFocus,
    private val exporter: InventoryExporter,
) : ViewModel() {

    /** The format being exported right now, or null. */
    private val _exporting = MutableStateFlow<ExportFormat?>(null)
    val exporting: StateFlow<ExportFormat?> = _exporting.asStateFlow()

    /** A ready file to share, or why it could not be made. */
    private val _exports = MutableSharedFlow<Result<ExportedFile>>(extraBufferCapacity = 1)
    val exports: SharedFlow<Result<ExportedFile>> = _exports.asSharedFlow()

    fun export(format: ExportFormat) {
        if (_exporting.value != null) return
        _exporting.value = format
        viewModelScope.launch {
            val result = exporter.export(format)
            _exports.emit(result.fold({ Result.success(it) }, { Result.failure(Exception(it.toUserMessage(), it)) }))
            _exporting.value = null
        }
    }

    private val all = MutableStateFlow<UiState<List<Device>>>(UiState.Loading)
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(DeviceFilter.ALL)
    private val selected = MutableStateFlow<Set<String>>(emptySet())

    private val _bulk = MutableStateFlow<BulkEditState?>(null)
    val bulk: StateFlow<BulkEditState?> = _bulk.asStateFlow()

    /** Short reports for a toast: what a group change did. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val state: StateFlow<DevicesUiState> = combine(all, query, filter, selected) { devices, q, f, picked ->
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
        DevicesUiState(visible, q, f, offline, picked)
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

    // Selection mode: a long press starts it, taps add or remove, an empty selection ends it.

    fun toggle(id: String) {
        selected.update { if (id in it) it - id else it + id }
    }

    /** Picks every device the search and the filter show now. */
    fun selectAllVisible() {
        val shown = (state.value.devices as? UiState.Content)?.data.orEmpty().map { it.id }
        selected.update { it + shown }
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    fun openBulkEdit() {
        val devices = (all.value as? UiState.Content)?.data.orEmpty()
        _bulk.value = BulkEditState(hosts = devices.filter { it.type == DeviceType.SERVER }.sortedBy { it.name })
        viewModelScope.launch {
            repository.floorMap().onSuccess { map -> _bulk.update { it?.copy(floors = map.floors) } }
        }
    }

    fun closeBulkEdit() {
        _bulk.value = null
    }

    fun applyBulk(change: DeviceBulkChange) {
        val ids = selected.value.toList()
        if (ids.isEmpty() || change.isEmpty) return
        _bulk.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            repository.bulkUpdateDevices(ids, change)
                .onSuccess { n ->
                    _bulk.value = null
                    selected.value = emptySet()
                    _messages.emit("Изменено устройств: $n")
                    load()
                }
                .onFailure { e -> _bulk.update { it?.copy(busy = false, error = e.toUserMessage()) } }
        }
    }

    fun deleteSelected() {
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.bulkDeleteDevices(ids)
                .onSuccess { n ->
                    selected.value = emptySet()
                    _messages.emit("Удалено устройств: $n")
                    load()
                }
                .onFailure { e -> _messages.emit(e.toUserMessage()) }
        }
    }
}
