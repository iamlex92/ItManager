package com.itmanager.app.ui.pcs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.export.ExportedFile
import com.itmanager.app.data.export.InventoryExporter
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.PcAction
import com.itmanager.app.data.model.GpoAppliedOnPc
import com.itmanager.app.data.model.PcInfo
import com.itmanager.app.data.model.PcKeyJob
import com.itmanager.app.data.model.PcOutcome
import com.itmanager.app.data.model.PcPlatform
import com.itmanager.app.data.model.PcResult
import com.itmanager.app.data.model.PcStatus
import com.itmanager.app.data.model.PcSweepHit
import com.itmanager.app.data.source.DcimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One computer being looked at: what was last run on it and what came back. */
data class PcSession(
    val host: String,
    val info: PcInfo? = null,
    val running: PcAction? = null,
    val result: PcResult? = null,
    val error: String? = null,
    /** The console: each command with its output, newest last. */
    val console: List<Pair<String, String>> = emptyList(),
    /** What the computer really applied (gpresult), once asked. */
    val applied: GpoAppliedOnPc? = null,
    val appliedLoading: Boolean = false,
    /** Windows or Astra Linux: what answered (WinRM or SSH), learnt from the first reply. */
    val platform: PcPlatform = PcPlatform.WINDOWS,
)

data class PcSweep(val prefix: String, val running: Boolean = false, val hits: List<PcSweepHit>? = null, val error: String? = null)

@HiltViewModel
class PcsViewModel @Inject constructor(
    private val repository: DcimRepository,
    private val exporter: InventoryExporter,
) : ViewModel() {

    private val _status = MutableStateFlow<PcStatus?>(null)
    val status: StateFlow<PcStatus?> = _status.asStateFlow()

    private val _statusError = MutableStateFlow<String?>(null)
    val statusError: StateFlow<String?> = _statusError.asStateFlow()

    private val _session = MutableStateFlow<PcSession?>(null)
    val session: StateFlow<PcSession?> = _session.asStateFlow()

    private val _sweep = MutableStateFlow<PcSweep?>(null)
    val sweep: StateFlow<PcSweep?> = _sweep.asStateFlow()

    private val _keys = MutableStateFlow<PcKeyJob?>(null)
    val keys: StateFlow<PcKeyJob?> = _keys.asStateFlow()

    private val _keysError = MutableStateFlow<String?>(null)
    val keysError: StateFlow<String?> = _keysError.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _exports = MutableSharedFlow<Result<ExportedFile>>(extraBufferCapacity = 1)
    val exports: SharedFlow<Result<ExportedFile>> = _exports.asSharedFlow()

    private var keysPoll: Job? = null
    private var opened = false

    /** Remote help asked for together with opening the computer: started once its details are in. */
    private var assistAfterInfo = false

    init {
        loadStatus()
    }

    fun loadStatus() {
        viewModelScope.launch {
            repository.pcStatus()
                .onSuccess { _status.value = it; _statusError.value = null }
                .onFailure { _statusError.value = it.toUserMessage() }
        }
    }

    /** Opened from a device card: once per screen, so coming back from the share sheet does not run it again. */
    fun openOnce(host: String, assist: Boolean = false) {
        if (opened || host.isBlank()) return
        opened = true
        assistAfterInfo = assist
        open(host)
    }

    fun open(host: String) {
        val name = host.trim()
        if (name.isEmpty()) return
        _session.value = PcSession(name)
        run(PcAction.INFO)
    }

    fun close() {
        _session.value = null
    }

    /** [authMethod] is how the operator confirmed an action that needs it; reads pass NOT_REQUIRED. */
    fun run(action: PcAction, arg: String? = null, authMethod: AuthMethod = AuthMethod.NOT_REQUIRED) {
        val s = _session.value ?: return
        if (s.running != null) return
        val host = s.host
        _session.value = s.copy(running = action, error = null)
        viewModelScope.launch {
            repository.pcAction(host, action, arg, authMethod)
                .onSuccess { r -> applyResult(host, action, arg, r) }
                .onFailure { e ->
                    _session.update { if (it?.host == host) it.copy(running = null, error = e.toUserMessage()) else it }
                }
        }
    }

    private fun applyResult(host: String, action: PcAction, arg: String?, r: PcResult) {
        val current = (_session.value?.takeIf { it.host == host } ?: return).copy(platform = r.platform)
        if (!r.ok) {
            _session.value = current.copy(running = null, error = r.error.ifBlank { "Компьютер не ответил" })
            return
        }
        when (val o = r.outcome) {
            is PcOutcome.Info -> {
                _session.value = current.copy(running = null, info = o.info, result = r)
                if (assistAfterInfo) {
                    assistAfterInfo = false
                    run(PcAction.ASSIST)
                }
            }
            is PcOutcome.Stopped -> {
                _messages.tryEmit(if (o.count > 0) "Завершено процессов ${o.name}: ${o.count}" else "Процесс ${o.name} не найден")
                _session.value = current.copy(running = null)
                run(PcAction.PROCESSES)
            }
            is PcOutcome.Text -> if (action == PcAction.ADMIN_ADD || action == PcAction.ADMIN_REMOVE) {
                _messages.tryEmit(o.text.ifBlank { "Готово" })
                _session.value = current.copy(running = null)
                run(PcAction.INFO)
            } else if (action == PcAction.COMMAND) {
                val out = (if (r.simulated) "[эмуляция]\n" else "") + o.text
                _session.value = current.copy(running = null, console = (current.console + (arg.orEmpty() to out)).takeLast(CONSOLE_KEEP))
            } else {
                if (action == PcAction.REBOOT) _messages.tryEmit("Команда перезагрузки отправлена")
                _session.value = current.copy(running = null, result = r)
            }
            else -> _session.value = current.copy(running = null, result = r)
        }
    }

    fun fail(message: String) = _session.update { it?.copy(error = message) }

    /** Group policies the computer applied at its last refresh (gpresult over WinRM). */
    fun loadApplied() {
        val host = _session.value?.host ?: return
        _session.update { it?.copy(appliedLoading = true, error = null) }
        viewModelScope.launch {
            repository.gpoApplied(host)
                .onSuccess { a -> _session.update { if (it?.host == host) it.copy(applied = a, appliedLoading = false) else it } }
                .onFailure { e -> _session.update { if (it?.host == host) it.copy(appliedLoading = false, error = e.toUserMessage()) else it } }
        }
    }

    fun clearConsole() = _session.update { it?.copy(console = emptyList()) }

    // --- subnet sweep ---

    fun sweep(prefix: String) {
        val p = prefix.trim()
        if (p.isEmpty() || _sweep.value?.running == true) return
        _sweep.value = PcSweep(p, running = true)
        viewModelScope.launch {
            repository.pcSweep(p)
                .onSuccess { hits -> _sweep.update { it?.copy(running = false, hits = hits) } }
                .onFailure { e -> _sweep.update { it?.copy(running = false, error = e.toUserMessage()) } }
        }
    }

    // --- key inventory ---

    /** [source]: «ad» for every computer in the directory, «subnet» for a /24 by [prefix]. */
    fun startKeys(source: String, prefix: String?) {
        if (_keys.value?.running == true) return
        _keysError.value = null
        viewModelScope.launch {
            repository.pcKeysStart(source, prefix?.trim()?.takeIf { it.isNotEmpty() })
                .onSuccess { job -> _keys.value = job; poll(job.id) }
                .onFailure { _keysError.value = it.toUserMessage() }
        }
    }

    private fun poll(id: String) {
        keysPoll?.cancel()
        keysPoll = viewModelScope.launch {
            while (_keys.value?.running == true) {
                delay(POLL_MS)
                repository.pcKeys(id)
                    .onSuccess { _keys.value = it }
                    .onFailure { _keysError.value = it.toUserMessage(); return@launch }
            }
        }
    }

    fun shareKeys() {
        val job = _keys.value ?: return
        viewModelScope.launch { _exports.emit(exporter.keysInventory(job.id)) }
    }

    private companion object {
        const val POLL_MS = 1_500L
        const val CONSOLE_KEEP = 20
    }
}
