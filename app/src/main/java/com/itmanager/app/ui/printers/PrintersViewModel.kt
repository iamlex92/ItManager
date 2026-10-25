package com.itmanager.app.ui.printers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Printer
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    init {
        viewModelScope.launch { repository.settings.collect { load() } }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
        repository.printers()
            // Printers that need a cartridge come first: that is why this screen gets opened.
            .onSuccess { list -> _state.value = UiState.Content(list.sortedWith(compareBy<Printer>({ !it.hasLowConsumable }, { it.name }))) }
            .onFailure { e -> _state.value = UiState.Error(e.toUserMessage()) }
    }
}
