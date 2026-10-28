package com.itmanager.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.data.model.Credential
import com.itmanager.app.data.model.CredentialKind
import com.itmanager.app.data.source.DcimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The stored SSH logins a switch can use, for the device form; loaded once when the form shows a switch. */
@HiltViewModel
class SshLoginsViewModel @Inject constructor(private val repository: DcimRepository) : ViewModel() {
    private val _logins = MutableStateFlow<List<Credential>>(emptyList())
    val logins: StateFlow<List<Credential>> = _logins.asStateFlow()
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            repository.credentials().onSuccess { all -> _logins.value = all.filter { it.kind == CredentialKind.SSH } }
        }
    }
}
