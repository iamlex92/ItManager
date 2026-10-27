package com.itmanager.app.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.ImportReport
import com.itmanager.app.data.model.NetworkScan
import com.itmanager.app.data.model.ScanRanges
import com.itmanager.app.data.model.ScanStatus
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.data.source.DcimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScannerUiState(
    val subnets: String = "",
    val community: String = "public",
    /** VLAN subnets offered as one-tap ranges. */
    val suggestions: List<Vlan> = emptyList(),
    val starting: Boolean = false,
    val scan: NetworkScan? = null,
    /** IPs ticked for "add to inventory". */
    val selected: Set<String> = emptySet(),
    val withLinks: Boolean = true,
    val importing: Boolean = false,
    val report: ImportReport? = null,
    val error: String? = null,
) {
    val running: Boolean get() = starting || scan?.status == ScanStatus.RUNNING
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val repository: DcimRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private var polling: Job? = null

    init {
        viewModelScope.launch {
            repository.vlans().onSuccess { vlans -> _state.update { it.copy(suggestions = vlans.filter { v -> v.subnet.isNotBlank() }.sortedBy { v -> v.id }) } }
        }
        // Shows the last scan again when the screen is reopened, and resumes following one still running.
        viewModelScope.launch {
            repository.latestScan().onSuccess { scan ->
                if (scan != null && _state.value.scan == null) {
                    _state.update { it.copy(scan = scan, subnets = scan.subnets.joinToString(", "), selected = defaultSelection(scan)) }
                    if (scan.status == ScanStatus.RUNNING) follow(scan.id)
                }
            }
        }
    }

    fun setSubnets(value: String) = _state.update { it.copy(subnets = value, error = null) }

    fun setCommunity(value: String) = _state.update { it.copy(community = value.trim(), error = null) }

    /** Adds the VLAN's subnet to the list, or takes it out when it is already there. */
    fun toggleSuggestion(subnet: String) = _state.update { s ->
        val items = s.subnets.split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val next = if (subnet in items) items - subnet else items + subnet
        s.copy(subnets = next.joinToString(", "), error = null)
    }

    fun start() {
        val s = _state.value
        if (s.running || s.importing) return
        val subnets = ScanRanges.parse(s.subnets).getOrElse { e ->
            _state.update { it.copy(error = e.message) }
            return
        }
        if (s.community.isBlank()) {
            _state.update { it.copy(error = "Укажите SNMP community (обычно public).") }
            return
        }
        _state.update { it.copy(starting = true, error = null, report = null, selected = emptySet()) }
        viewModelScope.launch {
            repository.startScan(subnets, s.community)
                .onSuccess { scan ->
                    _state.update { it.copy(starting = false, scan = scan) }
                    follow(scan.id)
                }
                .onFailure { e -> _state.update { it.copy(starting = false, error = e.toUserMessage()) } }
        }
    }

    private fun follow(id: String) {
        polling?.cancel()
        polling = viewModelScope.launch {
            var failures = 0
            while (true) {
                delay(POLL_MS)
                repository.scan(id)
                    .onSuccess { scan ->
                        failures = 0
                        _state.update {
                            it.copy(scan = scan, selected = if (scan.status == ScanStatus.DONE) defaultSelection(scan) else it.selected)
                        }
                        if (scan.status != ScanStatus.RUNNING) return@launch
                    }
                    .onFailure { e ->
                        if (++failures >= 3) {
                            _state.update { it.copy(error = e.toUserMessage()) }
                            return@launch
                        }
                    }
            }
        }
    }

    fun toggle(ip: String) = _state.update { s ->
        s.copy(selected = if (ip in s.selected) s.selected - ip else s.selected + ip)
    }

    fun selectAllNew(select: Boolean) = _state.update { s ->
        s.copy(selected = if (select) s.scan?.newDevices?.mapTo(HashSet()) { it.ip }.orEmpty() else emptySet())
    }

    fun setWithLinks(value: Boolean) = _state.update { it.copy(withLinks = value) }

    fun import() {
        val s = _state.value
        val scan = s.scan ?: return
        if (s.importing || s.selected.isEmpty() || scan.status != ScanStatus.DONE) return
        _state.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            repository.importScan(scan.id, s.selected.toList(), s.withLinks)
                .onSuccess { report ->
                    _state.update { it.copy(importing = false, report = report, selected = emptySet()) }
                    // Fresh "known" marks for what was just added.
                    repository.scan(scan.id).onSuccess { fresh -> _state.update { it.copy(scan = fresh) } }
                }
                .onFailure { e -> _state.update { it.copy(importing = false, error = e.toUserMessage()) } }
        }
    }

    fun dismissReport() = _state.update { it.copy(report = null) }

    private fun defaultSelection(scan: NetworkScan): Set<String> = scan.newDevices.mapTo(HashSet()) { it.ip }

    private companion object {
        const val POLL_MS = 700L
    }
}
