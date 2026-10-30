package com.itmanager.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.core.nfc.TagPayload
import com.itmanager.app.data.export.ExportFormat
import com.itmanager.app.data.export.ExportedFile
import com.itmanager.app.data.export.InventoryExporter
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceBulkChange
import com.itmanager.app.data.model.DeviceGroup
import com.itmanager.app.data.model.DeviceGrouping
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FloorInfo
import com.itmanager.app.data.model.Zone
import com.itmanager.app.data.model.groupDevices
import com.itmanager.app.data.model.isDown
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

/**
 * Kinds of device: the tiles the tab opens with and the chips over the list. What people look for, not the 15
 * inventory types. [always]: a tile even when there are none yet, so «Телефоны» is where people expect it.
 */
enum class DeviceFilter(val label: String, val types: Set<DeviceType>?, val always: Boolean = false) {
    ALL("Все устройства", null),
    COMPUTERS("Компьютеры", setOf(DeviceType.WORKSTATION), always = true),
    SERVERS("Серверы и ВМ", setOf(DeviceType.SERVER, DeviceType.VIRTUAL), always = true),
    PHONES("Телефоны", setOf(DeviceType.PHONE), always = true),
    NETWORK("Сетевое оборудование", setOf(DeviceType.SWITCH, DeviceType.ROUTER, DeviceType.FIREWALL, DeviceType.ACCESS_POINT), always = true),
    PRINTERS("МФУ и принтеры", setOf(DeviceType.PRINTER), always = true),
    CAMERAS("Камеры", setOf(DeviceType.CAMERA), always = true),
    POWER("ИБП", setOf(DeviceType.UPS)),
    PASSIVE("Кабельное и стойки", setOf(DeviceType.SOCKET, DeviceType.PATCH_PANEL, DeviceType.RACK)),
    OTHER("Прочее", setOf(DeviceType.UNKNOWN)),
    /** Not a kind of device: everything active that does not answer right now. */
    OFFLINE("Не в сети", null),
    ;

    /** The pictogram of a tile: its first kind's. */
    val type: DeviceType? get() = types?.first()

    fun accepts(device: Device): Boolean = when (this) {
        OFFLINE -> device.isDown
        else -> types == null || device.type in types
    }
}

data class DevicesUiState(
    val devices: UiState<List<Device>> = UiState.Loading,
    val query: String = "",
    val filter: DeviceFilter = DeviceFilter.ALL,
    val offlineCount: Int = 0,
    /** How many devices each chip would show for the current search. */
    val counts: Map<DeviceFilter, Int> = emptyMap(),
    val grouping: DeviceGrouping = DeviceGrouping.TYPE,
    /** The shown devices in sections; empty while loading. */
    val groups: List<DeviceGroup> = emptyList(),
    /** Devices picked for a group change; not empty means the list is in selection mode. */
    val selected: Set<String> = emptySet(),
    /** The tiles instead of the list: until a kind is opened or something is searched. */
    val home: Boolean = true,
    /** Per kind, of all devices: how many there are and how many do not answer — for the tiles. */
    val totals: Map<DeviceFilter, Int> = emptyMap(),
    val down: Map<DeviceFilter, Int> = emptyMap(),
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
    private val grouping = MutableStateFlow(DeviceGrouping.TYPE)
    private val home = MutableStateFlow(true)
    /** Floors and their rooms, for grouping by floor or room. */
    private val places = MutableStateFlow(Places())

    private data class Places(val floors: List<FloorInfo> = emptyList(), val zones: Map<String, List<Zone>> = emptyMap())

    private val _bulk = MutableStateFlow<BulkEditState?>(null)
    val bulk: StateFlow<BulkEditState?> = _bulk.asStateFlow()

    /** Short reports for a toast: what a group change did. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val view = combine(query, filter, selected, grouping, home) { q, f, picked, g, h -> ViewOptions(q, f, picked, g, h) }

    private data class ViewOptions(
        val query: String,
        val filter: DeviceFilter,
        val selected: Set<String>,
        val grouping: DeviceGrouping,
        val home: Boolean,
    )

    val state: StateFlow<DevicesUiState> = combine(all, view, places) { devices, v, p ->
        val found = (devices as? UiState.Content)?.data.orEmpty().filter { it.matches(v.query) }
        val shown = found.filter { v.filter.accepts(it) }
        val visible = when (devices) {
            is UiState.Content -> devices.copy(data = shown.sortedWith(compareBy<Device> { it.type.ordinal }.thenBy { it.name }))
            else -> devices
        }
        DevicesUiState(
            devices = visible,
            query = v.query,
            filter = v.filter,
            offlineCount = (devices as? UiState.Content)?.data?.count { it.isDown } ?: 0,
            selected = v.selected,
            counts = DeviceFilter.entries.associateWith { f -> found.count { f.accepts(it) } },
            grouping = v.grouping,
            groups = groupDevices(shown, v.grouping, p.floors, p.zones),
            home = v.home && v.query.isBlank(),
            totals = (devices as? UiState.Content)?.data.orEmpty().let { list ->
                DeviceFilter.entries.associateWith { f -> list.count { f.accepts(it) } }
            },
            down = (devices as? UiState.Content)?.data.orEmpty().let { list ->
                DeviceFilter.entries.associateWith { f -> list.count { f.accepts(it) && it.isDown } }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    init {
        viewModelScope.launch { repository.settings.collect { load() } }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        all.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
        if (grouping.value != DeviceGrouping.TYPE) loadPlaces(withRooms = grouping.value == DeviceGrouping.ROOM)
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

    /** A tile: that kind's list. */
    fun openKind(value: DeviceFilter) {
        filter.value = value
        home.value = false
    }

    /** Back from a list to the tiles, the search and the selection dropped. */
    fun backHome() {
        filter.value = DeviceFilter.ALL
        query.value = ""
        selected.value = emptySet()
        home.value = true
    }

    fun setGrouping(value: DeviceGrouping) {
        grouping.value = value
        if (value != DeviceGrouping.TYPE) viewModelScope.launch { loadPlaces(withRooms = value == DeviceGrouping.ROOM) }
    }

    /** Floors for grouping by floor; with [withRooms] also every floor's rooms, one request per floor. */
    private suspend fun loadPlaces(withRooms: Boolean) {
        val first = repository.floorMap().getOrNull() ?: return
        val zones = if (!withRooms) {
            places.value.zones
        } else {
            first.floors.associate { f ->
                f.id to if (f.id == first.plan.id) first.plan.zones else repository.floorMap(f.id).getOrNull()?.plan?.zones.orEmpty()
            }
        }
        places.value = Places(first.floors, zones)
    }

    fun showOnMap(deviceId: String) = mapFocus.request(deviceId)

    /** A device found by scanning its QR label; the label carries the same JSON as the NFC tag. */
    private val _scanned = MutableSharedFlow<Device>(extraBufferCapacity = 1)
    val scanned: SharedFlow<Device> = _scanned.asSharedFlow()

    fun openScanned(raw: String) {
        val payload = TagPayload.parse(raw.encodeToByteArray())
        if (payload == null) {
            _messages.tryEmit("Это не наклейка ItManager")
            return
        }
        viewModelScope.launch {
            repository.deviceForTag(payload.id, "").fold(
                onSuccess = { _scanned.emit(it) },
                onFailure = { _messages.emit(it.toUserMessage()) },
            )
        }
    }

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
