package com.itmanager.app.ui.topology

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Topology
import com.itmanager.app.data.model.TopologyLayout
import com.itmanager.app.data.model.TopologyLayouter
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.navigation.MapFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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
)

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
}
