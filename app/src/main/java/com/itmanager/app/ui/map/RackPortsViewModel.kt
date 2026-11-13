package com.itmanager.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.PortDraft
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.source.DcimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The ports of the devices standing in the open rack: what the rack view draws on switches and patch panels, and the
 * labels written on the rear of a patch panel («куда идёт кабель»). One place for both entry points of the rack view.
 */
@HiltViewModel
class RackPortsViewModel @Inject constructor(private val repository: DcimRepository) : ViewModel() {

    private val _ports = MutableStateFlow<Map<String, List<SwitchPort>>>(emptyMap())
    val ports: StateFlow<Map<String, List<SwitchPort>>> = _ports.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var loaded: Set<String> = emptySet()

    /** Loads the ports of [deviceIds]; the same set again is not asked for twice. */
    fun load(deviceIds: Set<String>) {
        if (deviceIds == loaded) return
        loaded = deviceIds
        viewModelScope.launch {
            val out = HashMap<String, List<SwitchPort>>()
            for (id in deviceIds) {
                repository.ports(id).onSuccess { list -> out[id] = list.sortedBy { it.number } }
            }
            _ports.value = out
        }
    }

    /** Writes [text] as the label of [port] (an empty text clears it). Inventory only. */
    fun label(deviceId: String, port: SwitchPort, text: String) {
        val draft = PortDraft(
            number = port.number,
            name = port.name,
            isUplink = port.isUplink,
            mode = port.mode,
            accessVlan = port.accessVlan,
            taggedVlans = port.taggedVlans,
            speedMbps = port.speedMbps,
            poeEnabled = port.poeEnabled,
            label = text.trim(),
        )
        viewModelScope.launch {
            repository.updatePortRecord(port.id, draft)
                .onSuccess { updated ->
                    _error.value = null
                    _ports.update { all ->
                        all + (deviceId to all[deviceId].orEmpty().map { if (it.id == updated.id) updated else it })
                    }
                }
                .onFailure { e -> _error.value = e.toUserMessage() }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
