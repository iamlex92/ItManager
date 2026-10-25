package com.itmanager.app.ui.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.AuditEntry
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuditViewModel @Inject constructor(
    private val repository: DcimRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow<AuditKind?>(null)
    val filter: StateFlow<AuditKind?> = _filter.asStateFlow()

    private val _state = MutableStateFlow<UiState<List<AuditEntry>>>(UiState.Loading)
    val state: StateFlow<UiState<List<AuditEntry>>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.settings, _filter) { _, kind -> kind }.collect { load(it) }
        }
    }

    fun setFilter(kind: AuditKind?) {
        _filter.value = kind
    }

    fun refresh() {
        viewModelScope.launch { load(_filter.value) }
    }

    private suspend fun load(kind: AuditKind?) {
        _state.update { if (it is UiState.Content) it.copy(isRefreshing = true) else UiState.Loading }
        repository.audit(kind)
            .onSuccess { _state.value = UiState.Content(it) }
            .onFailure { _state.value = UiState.Error(it.toUserMessage()) }
    }
}
