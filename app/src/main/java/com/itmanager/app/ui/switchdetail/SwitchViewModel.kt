package com.itmanager.app.ui.switchdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.core.nfc.NfcController
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DiagnosticKind
import com.itmanager.app.data.model.DiagnosticResult
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortLoad
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.TrafficSeries
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.data.model.isIpv4
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.navigation.SwitchRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SwitchDetails(val device: Device, val ports: List<SwitchPort>)

/** Whether the port panel is following the switch in real time. */
sealed interface LiveState {
    data object Connecting : LiveState
    data object Live : LiveState
    data class Offline(val reason: String) : LiveState
}

/** A write waiting for the user's explicit confirmation in a dialog. */
sealed interface PendingWrite {
    data class PortChangeWrite(val port: SwitchPort, val change: PortChange, val commands: List<String>) : PendingWrite
    data class BounceWrite(val port: SwitchPort) : PendingWrite
    data object SaveConfigWrite : PendingWrite
}

/** A ping or traceroute from this switch. Read-only, so it runs without a confirmation dialog. */
data class DiagnosticRun(
    val kind: DiagnosticKind,
    val running: Boolean = false,
    val result: DiagnosticResult? = null,
    val error: String? = null,
)

/** The utilization chart in the port sheet. */
data class PortTraffic(val portId: String, val series: TrafficSeries? = null, val error: String? = null)

/** Utilization charts and the load view cover the last day. */
const val TRAFFIC_HOURS = 24

@HiltViewModel
class SwitchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DcimRepository,
    private val nfc: NfcController,
) : ViewModel() {

    private val deviceId = savedStateHandle.toRoute<SwitchRoute>().deviceId

    private val _state = MutableStateFlow<UiState<SwitchDetails>>(UiState.Loading)
    val state: StateFlow<UiState<SwitchDetails>> = _state.asStateFlow()

    private val _live = MutableStateFlow<LiveState>(LiveState.Connecting)
    val live: StateFlow<LiveState> = _live.asStateFlow()

    private val _vlans = MutableStateFlow<List<Vlan>>(emptyList())
    val vlans: StateFlow<List<Vlan>> = _vlans.asStateFlow()

    private val _pending = MutableStateFlow<PendingWrite?>(null)
    val pending: StateFlow<PendingWrite?> = _pending.asStateFlow()

    /** True while a preview or a write is in flight; buttons wait instead of stacking requests. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _diagnostic = MutableStateFlow<DiagnosticRun?>(null)
    val diagnostic: StateFlow<DiagnosticRun?> = _diagnostic.asStateFlow()
    private var diagnosticJob: Job? = null

    /** Devices with an address, offered as ping targets so nobody has to type an IP on the shop floor. */
    private val _targets = MutableStateFlow<List<Device>>(emptyList())
    val targets: StateFlow<List<Device>> = _targets.asStateFlow()

    val isDemo: StateFlow<Boolean> = repository.settings.map { it.demoMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Per-port load for the "Загрузка" view, by port id; null until that view is first opened. */
    private val _load = MutableStateFlow<UiState<Map<String, PortLoad>>?>(null)
    val load: StateFlow<UiState<Map<String, PortLoad>>?> = _load.asStateFlow()

    private val _portTraffic = MutableStateFlow<PortTraffic?>(null)
    val portTraffic: StateFlow<PortTraffic?> = _portTraffic.asStateFlow()
    private var portTrafficJob: Job? = null

    fun loadTraffic(force: Boolean = false) {
        if (!force && _load.value is UiState.Content) return
        viewModelScope.launch {
            _load.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
            repository.switchTraffic(deviceId, TRAFFIC_HOURS).fold(
                onSuccess = { list -> _load.value = UiState.Content(list.associateBy { it.portId }) },
                onFailure = { e -> _load.value = UiState.Error(e.toUserMessage()) },
            )
        }
    }

    /** Loads the chart for the port whose sheet just opened. */
    fun openPortTraffic(portId: String) {
        if (_portTraffic.value?.portId == portId && _portTraffic.value?.series != null) return
        portTrafficJob?.cancel()
        _portTraffic.value = PortTraffic(portId)
        portTrafficJob = viewModelScope.launch {
            repository.portTraffic(deviceId, portId, TRAFFIC_HOURS, points = 96).fold(
                onSuccess = { series -> _portTraffic.value = PortTraffic(portId, series) },
                onFailure = { e -> _portTraffic.value = PortTraffic(portId, error = e.toUserMessage()) },
            )
        }
    }

    init {
        refresh()
        viewModelScope.launch { repository.vlans().onSuccess { _vlans.value = it.sortedBy { v -> v.id } } }
        viewModelScope.launch {
            repository.live(deviceId).collect(::apply)
        }
        viewModelScope.launch {
            repository.devices().onSuccess { all ->
                _targets.value = all.filter { it.id != deviceId && it.ip != null }.sortedBy { it.name }
            }
        }
    }

    fun bindNfc() {
        (_state.value as? UiState.Content)?.data?.device?.let(nfc::startBinding)
    }

    fun openDiagnostic(kind: DiagnosticKind) {
        diagnosticJob?.cancel()
        _diagnostic.value = DiagnosticRun(kind)
    }

    fun runDiagnostic(target: String) {
        val run = _diagnostic.value ?: return
        val ip = target.trim()
        if (!isIpv4(ip)) {
            _diagnostic.value = run.copy(error = "Нужен IPv4-адрес, например 10.50.0.31")
            return
        }
        diagnosticJob?.cancel()
        _diagnostic.value = run.copy(running = true, result = null, error = null)
        diagnosticJob = viewModelScope.launch {
            val result = repository.diagnostic(deviceId, run.kind, ip)
            _diagnostic.update { current ->
                current?.copy(
                    running = false,
                    result = result.getOrNull(),
                    error = result.exceptionOrNull()?.toUserMessage(),
                )
            }
        }
    }

    fun closeDiagnostic() {
        diagnosticJob?.cancel()
        _diagnostic.value = null
    }

    private fun apply(event: LiveEvent) {
        val target = when (event) {
            is LiveEvent.Snapshot -> event.deviceId
            is LiveEvent.PortChanged -> event.deviceId
            is LiveEvent.DeviceStatus -> event.deviceId
            else -> deviceId
        }
        if (target != deviceId) return
        when (event) {
            LiveEvent.Connected -> _live.value = LiveState.Live
            is LiveEvent.Disconnected -> _live.value = LiveState.Offline(event.reason)
            is LiveEvent.Snapshot -> updateDetails { d ->
                d.copy(
                    device = event.isOnline?.let { d.device.copy(isOnline = it) } ?: d.device,
                    ports = event.ports.sortedBy { it.number },
                )
            }
            is LiveEvent.PortChanged -> updateDetails { d ->
                d.copy(ports = d.ports.map { if (it.id == event.port.id) event.port else it })
            }
            is LiveEvent.DeviceStatus -> updateDetails { d -> d.copy(device = d.device.copy(isOnline = event.isOnline)) }
            // Alerts and printers have their own screens.
            is LiveEvent.AlertRaised, is LiveEvent.PrinterChanged, LiveEvent.InventoryChanged -> Unit
        }
    }

    /** Step 1 of a port change: ask for the exact commands, then show them for confirmation. */
    fun requestPortChange(port: SwitchPort, change: PortChange) = runBusy {
        repository.previewPortChange(deviceId, port.id, change)
            .onSuccess { _pending.value = PendingWrite.PortChangeWrite(port, change, it) }
            .onFailure { _messages.emit(it.toUserMessage()) }
    }

    fun requestBounce(port: SwitchPort) {
        _pending.value = PendingWrite.BounceWrite(port)
    }

    fun requestSaveConfig() {
        _pending.value = PendingWrite.SaveConfigWrite
    }

    fun dismissPending() {
        _pending.value = null
    }

    /** Step 2: the user confirmed in the dialog. [confirmUplink] is a separate tick for uplink ports. */
    fun confirmPending(confirmUplink: Boolean) {
        val write = _pending.value ?: return
        _pending.value = null
        runBusy {
            val result: Result<String> = when (write) {
                is PendingWrite.PortChangeWrite ->
                    repository.applyPortChange(deviceId, write.port.id, write.change, confirmUplink).map { r ->
                        updateDetails { d -> d.copy(ports = d.ports.map { if (it.id == r.port.id) r.port else it }) }
                        "Порт ${write.port.name} настроен"
                    }
                is PendingWrite.BounceWrite ->
                    repository.bouncePort(deviceId, write.port.id, confirmUplink).map { "Порт ${write.port.name} перезапущен" }
                PendingWrite.SaveConfigWrite ->
                    repository.writeMemory(deviceId).map { "Конфигурация сохранена в энергонезависимую память" }
            }
            _messages.emit(result.getOrElse { it.toUserMessage() })
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private inline fun updateDetails(crossinline change: (SwitchDetails) -> SwitchDetails) = _state.update { s ->
        if (s is UiState.Content) s.copy(data = change(s.data)) else s
    }

    fun refresh() {
        if (_load.value != null) loadTraffic(force = true)
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
