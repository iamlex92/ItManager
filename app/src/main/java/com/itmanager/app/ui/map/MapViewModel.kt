package com.itmanager.app.ui.map

import android.graphics.Picture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caverock.androidsvg.SVG
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.core.nfc.NfcController
import com.itmanager.app.data.model.ALL_DEVICES
import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.MapLayer
import com.itmanager.app.data.model.traceToSwitch
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.navigation.MapFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A floor map plus lookups the canvas needs every frame, computed once per load. */
data class MapContent(
    val map: FloorMap,
    val devicesById: Map<String, Device>,
    /** VLAN carried by each access cable, or null for trunks between switches. */
    val linkVlan: Map<String, Int?>,
)

data class MapUiState(
    val content: UiState<MapContent> = UiState.Loading,
    val layer: MapLayer = MapLayer.L1,
    val vlanFilter: Int? = null,
    val selectedId: String? = null,
    val tracedLinks: Set<String> = emptySet(),
    val tracePath: List<String> = emptyList(),
    /** Vector floor plan from the server, already parsed; null while loading or when there is none. */
    val planPicture: Picture? = null,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: DcimRepository,
    private val mapFocus: MapFocus,
    private val nfc: NfcController,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    /** A device another tab asked to show; the screen centres on it and then calls [focusHandled]. */
    val focusRequest: StateFlow<String?> = mapFocus.deviceId

    private var planUrl: String? = null

    init {
        viewModelScope.launch {
            repository.settings.collect { load() }
        }
        viewModelScope.launch {
            repository.live(ALL_DEVICES).collect { event ->
                if (event is LiveEvent.DeviceStatus) applyDeviceStatus(event.deviceId, event.isOnline)
            }
        }
    }

    /** Opens the binding sheet; the next tag held to the phone gets bound to [device]. */
    fun bindNfc(device: Device) = nfc.startBinding(device)

    private fun applyDeviceStatus(deviceId: String, isOnline: Boolean) = _state.update { s ->
        val content = (s.content as? UiState.Content)?.data ?: return@update s
        val device = content.devicesById[deviceId]?.takeIf { it.isOnline != isOnline } ?: return@update s
        val updated = device.copy(isOnline = isOnline)
        val map = content.map.copy(devices = content.map.devices.map { if (it.id == deviceId) updated else it })
        s.copy(content = (s.content as UiState.Content).copy(data = content.copy(map = map, devicesById = content.devicesById + (deviceId to updated))))
    }

    private suspend fun loadPlan(url: String?) {
        if (url == planUrl) return
        planUrl = url
        _state.update { it.copy(planPicture = null) }
        if (url == null) return
        repository.planSvg(url).onSuccess { svg ->
            val picture = withContext(Dispatchers.Default) {
                runCatching { SVG.getFromString(svg).renderToPicture() }.getOrNull()
            }
            // A plan that fails to load or parse is not fatal: the map still works on the grid.
            if (planUrl == url) _state.update { it.copy(planPicture = picture) }
        }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val previous = _state.value.content
        _state.update {
            it.copy(content = if (previous is UiState.Content) previous.copy(isRefreshing = true) else UiState.Loading)
        }
        repository.floorMap()
            .onSuccess { map ->
                val content = MapContent(map, map.devices.associateBy { it.id }, accessLinkVlans(map))
                _state.update { it.copy(content = UiState.Content(content)) }
                // Keep the selection only if the device still exists after a reload.
                select(_state.value.selectedId?.takeIf { it in content.devicesById })
                loadPlan(map.plan.imageUrl)
            }
            .onFailure { e -> _state.update { it.copy(content = UiState.Error(e.toUserMessage())) } }
    }

    fun setLayer(layer: MapLayer) = _state.update {
        it.copy(layer = layer, vlanFilter = if (layer == MapLayer.L1) null else it.vlanFilter)
    }

    fun setVlanFilter(vlanId: Int?) = _state.update { it.copy(vlanFilter = vlanId) }

    fun select(deviceId: String?) {
        val content = (_state.value.content as? UiState.Content)?.data
        if (deviceId == null || content == null) {
            _state.update { it.copy(selectedId = null, tracedLinks = emptySet(), tracePath = emptyList()) }
            return
        }
        val links = traceToSwitch(deviceId, content.devicesById, content.map.connections)
        _state.update {
            it.copy(
                selectedId = deviceId,
                tracedLinks = links.toSet(),
                tracePath = pathNames(deviceId, links, content),
            )
        }
    }

    fun focusHandled() = mapFocus.consume()

    private fun pathNames(startId: String, linkIds: List<String>, content: MapContent): List<String> {
        if (linkIds.isEmpty()) return emptyList()
        val byId = content.map.connections.associateBy { it.id }
        val names = mutableListOf(content.devicesById[startId]?.name ?: return emptyList())
        var current = startId
        for (id in linkIds) {
            val link = byId[id] ?: break
            current = if (link.deviceAId == current) link.deviceBId else link.deviceAId
            names += content.devicesById[current]?.name ?: break
        }
        return names
    }

    private companion object {
        val PASSIVE = setOf(DeviceType.SOCKET, DeviceType.PATCH_PANEL)

        /**
         * Colours each access cable by the VLAN of the end device it ultimately serves,
         * walking through sockets and patch panels. Cables between switches stay uncoloured (trunks).
         */
        fun accessLinkVlans(map: FloorMap): Map<String, Int?> {
            val devices = map.devices.associateBy { it.id }
            val adjacency = HashMap<String, MutableList<Connection>>()
            for (c in map.connections) {
                adjacency.getOrPut(c.deviceAId) { mutableListOf() }.add(c)
                adjacency.getOrPut(c.deviceBId) { mutableListOf() }.add(c)
            }
            val result = HashMap<String, Int?>()
            for (end in map.devices) {
                if (end.type in PASSIVE || end.type == DeviceType.SWITCH) continue
                val vlan = end.vlanId ?: continue
                val visited = hashSetOf(end.id)
                val queue = ArrayDeque(listOf(end.id))
                while (queue.isNotEmpty()) {
                    val id = queue.removeFirst()
                    for (link in adjacency[id].orEmpty()) {
                        // A cable shared by several VLANs (a patch cord bundle) is drawn neutral.
                        if (!result.containsKey(link.id)) result[link.id] = vlan
                        else if (result[link.id] != vlan) result[link.id] = null
                        val next = if (link.deviceAId == id) link.deviceBId else link.deviceAId
                        if (devices[next]?.type?.let { it in PASSIVE } == true && visited.add(next)) queue.add(next)
                    }
                }
            }
            return result
        }
    }
}
