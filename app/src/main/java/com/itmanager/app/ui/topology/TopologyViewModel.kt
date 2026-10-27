package com.itmanager.app.ui.topology

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.Topology
import com.itmanager.app.data.model.TopologyLink
import com.itmanager.app.data.model.TopologyLayout
import com.itmanager.app.data.model.TopologyLayouter
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.navigation.MapFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TopologyUiState(
    val topology: UiState<Topology> = UiState.Loading,
    /** PCs, printers, cameras and the like; off shows only switches, routers and firewalls. */
    val showEndDevices: Boolean = true,
    val layout: TopologyLayout? = null,
    val selectedId: String? = null,
    /** A change is being saved. */
    val busy: Boolean = false,
    /** The device form opened from the topology: the device with its servers to pick a host from. */
    val form: TopologyForm? = null,
)

data class TopologyForm(val device: Device, val hosts: List<Device>, val error: String? = null)

@HiltViewModel
class TopologyViewModel @Inject constructor(
    private val repository: DcimRepository,
    private val mapFocus: MapFocus,
) : ViewModel() {

    private val _state = MutableStateFlow(TopologyUiState())
    val state: StateFlow<TopologyUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { repository.settings.collect { load() } }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.update { s -> if (s.topology is UiState.Content) s.copy(topology = s.topology.copy(isRefreshing = true)) else s }
        repository.topology().fold(
            onSuccess = { t ->
                _state.update { s ->
                    s.copy(
                        topology = UiState.Content(t),
                        layout = TopologyLayouter.layout(t, s.showEndDevices),
                        selectedId = s.selectedId?.takeIf { id -> t.nodes.any { it.id == id } },
                    )
                }
            },
            onFailure = { e ->
                _state.update { s ->
                    val current = s.topology
                    if (current is UiState.Content) s.copy(topology = current.copy(isRefreshing = false)) else s.copy(topology = UiState.Error(e.toUserMessage()))
                }
            },
        )
    }

    fun setShowEndDevices(show: Boolean) = _state.update { s ->
        val t = (s.topology as? UiState.Content)?.data
        val layout = t?.let { TopologyLayouter.layout(it, show) }
        s.copy(
            showEndDevices = show,
            layout = layout,
            selectedId = s.selectedId?.takeIf { layout?.places?.containsKey(it) == true },
        )
    }

    fun select(id: String?) = _state.update { it.copy(selectedId = id) }

    fun showOnMap(deviceId: String) = mapFocus.request(deviceId)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun busy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** A cable between two devices; the server takes a free port on each side. Inventory only. */
    fun connect(aId: String, bId: String, cable: String) = busy {
        repository.linkDevices(aId, bId, cable)
            .onSuccess { _messages.tryEmit("Соединено") }
            .onFailure { _messages.tryEmit(it.toUserMessage()) }
    }

    /** Unplugs [selfId] from this link: removes the cable at its port, the rest of the cabling stays. */
    fun disconnect(link: TopologyLink, selfId: String) = busy {
        val cable = link.cableAt(selfId) ?: return@busy
        repository.deleteConnection(cable)
            .onSuccess { _messages.tryEmit("Кабель удалён") }
            .onFailure { _messages.tryEmit(it.toUserMessage()) }
    }

    fun openEdit(deviceId: String) = busy {
        val all = repository.devices().getOrElse { _messages.tryEmit(it.toUserMessage()); return@busy }
        val device = all.firstOrNull { it.id == deviceId } ?: return@busy
        _state.update {
            it.copy(form = TopologyForm(device, all.filter { d -> d.type == DeviceType.SERVER }.sortedBy { d -> d.name }))
        }
    }

    fun closeEdit() = _state.update { it.copy(form = null) }

    fun saveDevice(draft: DeviceDraft) = busy {
        val form = _state.value.form ?: return@busy
        repository.updateDevice(form.device.id, draft).fold(
            onSuccess = { _state.update { it.copy(form = null) } },
            onFailure = { e -> _state.update { s -> s.copy(form = s.form?.copy(error = e.toUserMessage())) } },
        )
    }

    fun deleteDevice(id: String) = busy {
        repository.deleteDevice(id).fold(
            onSuccess = { _state.update { it.copy(form = null, selectedId = null) } },
            onFailure = { e -> _state.update { s -> s.copy(form = s.form?.copy(error = e.toUserMessage())) } },
        )
    }
}
