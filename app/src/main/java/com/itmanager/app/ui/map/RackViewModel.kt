package com.itmanager.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.RackStyle
import com.itmanager.app.data.source.DcimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The front view of one rack, wherever it is opened from: the map or the rack's own card. */
@HiltViewModel
class RackViewModel @Inject constructor(private val repository: DcimRepository) : ViewModel() {

    private val _rack = MutableStateFlow<RackViewState?>(null)
    val rack: StateFlow<RackViewState?> = _rack.asStateFlow()

    fun open(id: String) {
        _rack.value = RackViewState(id)
        viewModelScope.launch { refresh() }
    }

    fun close() {
        _rack.value = null
    }

    private suspend fun refresh() {
        repository.devices()
            .onSuccess { list -> _rack.update { it?.copy(devices = list, busy = false) } }
            .onFailure { e -> _rack.update { it?.copy(busy = false, error = e.toUserMessage()) } }
    }

    /** Mounts [deviceId] from unit [u], [h] units high; null [rackId] takes it out of its rack. */
    fun mount(deviceId: String, rackId: String?, u: Int?, h: Int?) {
        val device = _rack.value?.devices?.firstOrNull { it.id == deviceId } ?: return
        write { repository.updateDevice(deviceId, device.toDraft().copy(rackId = rackId, rackU = u, rackH = h)) }
    }

    /** A new patch panel with [ports] ports from unit [u], [h] units high. */
    fun createPatchPanel(rackId: String, name: String, ports: Int, u: Int, h: Int) {
        write { repository.createPatchPanel(rackId, name, ports, u, h) }
    }

    /** The rack's own settings: name, height in units and how it is drawn. */
    fun saveRack(rack: Device, name: String, units: Int, style: RackStyle) {
        val draft = rack.toDraft().copy(
            name = name.trim().ifEmpty { rack.name },
            rackUnits = units,
            metadata = style.writeTo(rack.metadata),
        )
        write { repository.updateDevice(rack.id, draft) }
    }

    private fun write(block: suspend () -> Result<*>) {
        _rack.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            block()
                .onFailure { e -> _rack.update { it?.copy(busy = false, error = e.toUserMessage()) } }
                .onSuccess { refresh() }
        }
    }
}

/**
 * The rack view with the drawings of its devices and their faceplate editor, over whatever screen opened it.
 * [onOpenDevice]: a tap on a mounted switch opens its page.
 */
@Composable
fun RackHost(
    onOpenDevice: (String) -> Unit,
    rackViewModel: RackViewModel,
    faceplates: FaceplateViewModel = hiltViewModel(),
    /** The rack itself when the inventory list has not come yet. */
    fallback: (String) -> Device? = { null },
) {
    val rackState by rackViewModel.rack.collectAsStateWithLifecycle()
    val plates by faceplates.rackPlates.collectAsStateWithLifecycle()
    val editor by faceplates.editor.collectAsStateWithLifecycle()
    // The panel drawings follow the devices mounted in the open rack.
    val members = rackState?.let { r -> r.devices.filter { it.rackId == r.rackId } }
    LaunchedEffect(members) {
        if (members == null) faceplates.closeRack() else faceplates.showRack(members)
    }
    rackState?.let { state ->
        val rack = state.devices.firstOrNull { it.id == state.rackId } ?: fallback(state.rackId)
        if (rack != null) {
            RackDialog(
                plates = plates,
                onEditFaceplate = { d -> faceplates.openEditor(d, state.devices) },
                rack = rack,
                state = state,
                onMount = { id, u, h -> rackViewModel.mount(id, rack.id, u, h) },
                onRemove = { id -> rackViewModel.mount(id, null, null, null) },
                onOpenSwitch = { id ->
                    rackViewModel.close()
                    onOpenDevice(id)
                },
                onSaveRack = { name, units, style -> rackViewModel.saveRack(rack, name, units, style) },
                onCreatePanel = { name, ports, u, h -> rackViewModel.createPatchPanel(rack.id, name, ports, u, h) },
                onDismiss = rackViewModel::close,
            )
        }
    }
    // Over the rack view it was opened from.
    editor?.let { e -> FaceplateEditorDialog(e, faceplates) }
}
