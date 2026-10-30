package com.itmanager.app.ui.device

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.core.nfc.NfcController
import com.itmanager.app.data.model.Alert
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FloorInfo
import com.itmanager.app.data.model.PortDraft
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.data.model.roomAt
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.map.DeviceForm
import com.itmanager.app.ui.navigation.DeviceRoute
import com.itmanager.app.ui.navigation.MapFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the device card shows, gathered from several server calls. */
data class DeviceDetails(
    val device: Device,
    val floor: FloorInfo? = null,
    val floors: List<FloorInfo> = emptyList(),
    /** The room on the plan the device stands in. */
    val room: String? = null,
    val rack: Device? = null,
    val host: Device? = null,
    /** The switch the device is cabled to. */
    val uplink: Device? = null,
    /** Its own ports: a switch, a router, a patch panel. */
    val ports: List<SwitchPort> = emptyList(),
    val vlans: List<Vlan> = emptyList(),
    /** Devices mounted in this rack, top to bottom. */
    val rackMembers: List<Device> = emptyList(),
    /** Virtual machines running on this server. */
    val guests: List<Device> = emptyList(),
    /** Servers a virtual machine can be moved to. */
    val servers: List<Device> = emptyList(),
    /** Everything else in the inventory: what «Объединить с…» can fold into this device. */
    val others: List<Device> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val printer: Printer? = null,
) {
    val vlan: Vlan? get() = device.vlanId?.let { id -> vlans.firstOrNull { it.id == id } }
}

@HiltViewModel
class DeviceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DcimRepository,
    private val mapFocus: MapFocus,
    private val nfc: NfcController,
) : ViewModel() {

    val deviceId = savedStateHandle.toRoute<DeviceRoute>().deviceId

    private val _state = MutableStateFlow<UiState<DeviceDetails>>(UiState.Loading)
    val state: StateFlow<UiState<DeviceDetails>> = _state.asStateFlow()

    /** The edit form; null while closed. */
    private val _form = MutableStateFlow<DeviceForm?>(null)
    val form: StateFlow<DeviceForm?> = _form.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Fires once the device is deleted: the card closes. */
    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    private val _pollingPrinter = MutableStateFlow(false)
    val pollingPrinter: StateFlow<Boolean> = _pollingPrinter.asStateFlow()

    init {
        viewModelScope.launch { repository.settings.collect { load() } }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
        val device = repository.device(deviceId).getOrElse { e ->
            _state.value = UiState.Error(e.toUserMessage())
            return
        }
        val details = gather(device)
        _state.value = UiState.Content(details)
    }

    /** The rest of the card loads in parallel; a part that fails is simply left out. */
    private suspend fun gather(device: Device): DeviceDetails {
        val all = viewModelScope.async { repository.devices().getOrNull().orEmpty() }
        val map = viewModelScope.async { repository.floorMap(device.floorId).getOrNull() }
        val vlans = viewModelScope.async { repository.vlans().getOrNull().orEmpty() }
        val alerts = viewModelScope.async { repository.alerts().getOrNull().orEmpty() }
        val ports = viewModelScope.async {
            if (device.type.hasPorts) repository.ports(device.id).getOrNull().orEmpty() else emptyList()
        }
        val printer = viewModelScope.async {
            if (device.type == DeviceType.PRINTER) repository.printers().getOrNull()?.firstOrNull { it.id == device.id } else null
        }
        val devices = all.await()
        val byId = devices.associateBy { it.id }
        val floorMap = map.await()
        return DeviceDetails(
            device = device,
            floor = floorMap?.floors?.firstOrNull { it.id == device.floorId },
            floors = floorMap?.floors.orEmpty(),
            room = floorMap?.plan?.takeIf { it.id == device.floorId }?.zones?.roomAt(device.x, device.y)?.name,
            rack = device.rackId?.let(byId::get),
            host = device.hostId?.let(byId::get),
            uplink = device.switchId?.let(byId::get),
            ports = ports.await().sortedBy { it.number },
            vlans = vlans.await().sortedBy { it.id },
            rackMembers = devices.filter { it.rackId == device.id }.sortedByDescending { it.rackU ?: 0 },
            guests = devices.filter { it.hostId == device.id }.sortedBy { it.name.lowercase() },
            servers = devices.filter { it.type == DeviceType.SERVER && it.id != device.id }.sortedBy { it.name.lowercase() },
            others = devices.filter { it.id != device.id }.sortedBy { it.name.lowercase() },
            alerts = alerts.await().filter { it.deviceId == device.id && !it.isResolved },
            printer = printer.await(),
        )
    }

    private val details: DeviceDetails? get() = (_state.value as? UiState.Content)?.data

    // --- Actions ---

    fun showOnMap() = mapFocus.request(deviceId)

    fun bindNfc() {
        details?.device?.let(nfc::startBinding)
    }

    fun pollPrinter() {
        val printer = details?.printer ?: return
        _pollingPrinter.value = true
        viewModelScope.launch {
            repository.refreshPrinter(printer.id)
                .onSuccess { load() }
                .onFailure { _messages.emit(it.toUserMessage()) }
            _pollingPrinter.value = false
        }
    }

    // --- Edit form ---

    fun openEdit() {
        val device = details?.device ?: return
        _form.value = DeviceForm(device, device.x, device.y, device.floorId)
    }

    fun setFormFloor(floorId: String) = _form.update { it?.copy(floorId = floorId) }

    fun closeEdit() {
        _form.value = null
    }

    fun save(draft: DeviceDraft) {
        val form = _form.value ?: return
        val device = form.device ?: return
        write {
            repository.updateDevice(device.id, draft)
                .onSuccess {
                    // Moved to another floor: it keeps its place on the plan there.
                    if (form.floorId != null && form.floorId != device.floorId) {
                        repository.moveDevice(device.id, form.floorId, device.x, device.y)
                    }
                    _form.value = null
                    load()
                }
                .onFailure { e -> _form.update { it?.copy(error = e.toUserMessage()) } }
        }
    }

    fun delete(id: String) = write {
        repository.deleteDevice(id)
            .onSuccess {
                _form.value = null
                _deleted.emit(Unit)
            }
            .onFailure { e -> _form.update { it?.copy(error = e.toUserMessage()) } }
    }

    /** [source] is this same device entered twice: its ports, cables, history and addresses move here, it is removed. */
    fun merge(source: Device) = write {
        repository.mergeDevice(deviceId, source.id)
            .onSuccess {
                _messages.emit("${source.name} объединено с ${it.name}")
                load()
            }
            .onFailure { _messages.emit(it.toUserMessage()) }
    }

    /** The card's comment, saved on its own. [done] gets null on success or the error text. */
    fun saveComment(text: String, done: (String?) -> Unit) {
        val device = details?.device ?: return
        write {
            repository.updateDevice(device.id, device.toDraft().copy(comment = text.trim().ifEmpty { null }))
                .onSuccess {
                    done(null)
                    load()
                }
                .onFailure { done(it.toUserMessage()) }
        }
    }

    /**
     * Puts the switch port the device is cabled to into access [vlanId]. The inventory record only, like the
     * config import: nothing is sent to the switch.
     */
    fun setPortVlan(vlanId: Int) {
        val device = details?.device ?: return
        val switchId = device.switchId
        val portId = device.switchPortId
        if (switchId == null || portId == null) {
            _messages.tryEmit("Устройство не подключено кабелем к коммутатору")
            return
        }
        write {
            val port = repository.ports(switchId).getOrElse { _messages.emit(it.toUserMessage()); return@write }
                .firstOrNull { it.id == portId }
                ?: run { _messages.emit("Порт не найден на сервере"); return@write }
            val draft = PortDraft(
                number = port.number, name = port.name, isUplink = port.isUplink, mode = PortMode.ACCESS,
                accessVlan = vlanId, taggedVlans = emptyList(), speedMbps = port.speedMbps, poeEnabled = port.poeEnabled,
            )
            repository.updatePortRecord(portId, draft)
                .onSuccess {
                    _messages.emit("Порт ${port.name} переведён в VLAN $vlanId")
                    load()
                }
                .onFailure { _messages.emit(it.toUserMessage()) }
        }
    }

    private fun write(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}

/** Devices whose own ports are worth a tab in the card. */
val DeviceType.hasPorts: Boolean
    get() = this == DeviceType.SWITCH || this == DeviceType.ROUTER || this == DeviceType.FIREWALL || this == DeviceType.PATCH_PANEL
