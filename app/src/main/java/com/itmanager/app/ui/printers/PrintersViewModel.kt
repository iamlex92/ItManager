package com.itmanager.app.ui.printers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.ALL_DEVICES
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrintersViewModel @Inject constructor(
    private val repository: DcimRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Printer>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Printer>>> = _state.asStateFlow()

    /** Printers being polled on request right now. */
    private val _polling = MutableStateFlow<Set<String>>(emptySet())
    val polling: StateFlow<Set<String>> = _polling.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { repository.settings.collect { load() } }
        // The server pushes each SNMP reading as it arrives.
        viewModelScope.launch {
            repository.live(ALL_DEVICES).collect { event ->
                if (event is LiveEvent.PrinterChanged) replace(event.printer)
            }
        }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    /** Reads the printer over SNMP now (read-only), instead of waiting for the server's next poll. */
    fun poll(printer: Printer) {
        if (printer.id in _polling.value) return
        _polling.update { it + printer.id }
        viewModelScope.launch {
            repository.refreshPrinter(printer.id)
                .onSuccess(::replace)
                .onFailure { _messages.emit(it.toUserMessage()) }
            _polling.update { it - printer.id }
        }
    }

    private fun replace(printer: Printer) = _state.update { s ->
        if (s is UiState.Content) s.copy(data = sorted(s.data.map { if (it.id == printer.id) printer else it })) else s
    }

    private suspend fun load() {
        _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
        repository.printers()
            .onSuccess { list -> _state.value = UiState.Content(sorted(list)) }
            .onFailure { e -> _state.value = UiState.Error(e.toUserMessage()) }
    }

    // Printers that need a cartridge come first: that is why this screen gets opened. Silent ones next.
    private fun sorted(list: List<Printer>) =
        list.sortedWith(compareBy<Printer>({ !it.hasLowConsumable }, { it.isOnline }, { it.name }))
}
