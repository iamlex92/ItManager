package com.itmanager.app.ui.map

import android.content.Context
import android.graphics.Picture
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.UserFacingException
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.Faceplate
import com.itmanager.app.data.model.FaceplateMarks
import com.itmanager.app.data.model.FaceplatePort
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.source.DcimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A panel drawing ready to show: the parsed SVG, its marks and the live state of the ports. */
class FaceplateView(
    val faceplate: Faceplate,
    val picture: Picture,
    val ports: Map<String, SwitchPort>,
)

/** The faceplate editor of one device. Marks are edited locally and sent with «Сохранить». */
data class FaceplateEditorState(
    val device: Device,
    val loading: Boolean = true,
    val view: FaceplateView? = null,
    val ports: List<SwitchPort> = emptyList(),
    val marks: Map<String, FaceplatePort> = emptyMap(),
    val selectedPortId: String? = null,
    /** 1: ports one after another; 2: every other one (odd on top, even below). */
    val step: Int = 1,
    val markW: Float = FaceplateMarks.DEFAULT_W,
    val markH: Float = FaceplateMarks.DEFAULT_H,
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** Devices whose drawing can be copied here: same type, the same model first. */
    val copySources: List<Device> = emptyList(),
) {
    val selectedPort: SwitchPort? get() = ports.firstOrNull { it.id == selectedPortId }
}

@HiltViewModel
class FaceplateViewModel @Inject constructor(
    private val repository: DcimRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Drawings of the devices in the open rack, by device id. Devices without one are absent. */
    private val _rackPlates = MutableStateFlow<Map<String, FaceplateView>>(emptyMap())
    val rackPlates: StateFlow<Map<String, FaceplateView>> = _rackPlates.asStateFlow()

    private val _editor = MutableStateFlow<FaceplateEditorState?>(null)
    val editor: StateFlow<FaceplateEditorState?> = _editor.asStateFlow()

    private var rackJob: Job? = null
    private var rackMembers: List<Device> = emptyList()

    /** Loads the drawings of [members], the devices mounted in the rack being shown. */
    fun showRack(members: List<Device>) {
        if (members.map { it.id to it.isOnline } == rackMembers.map { it.id to it.isOnline } && _rackPlates.value.isNotEmpty()) return
        rackMembers = members
        rackJob?.cancel()
        rackJob = viewModelScope.launch {
            val loaded = coroutineScope {
                members.filter { it.type != DeviceType.RACK }.map { d -> async { d.id to loadView(d.id) } }.awaitAll()
            }
            _rackPlates.value = loaded.mapNotNull { (id, view) -> view?.let { id to it } }.toMap()
        }
    }

    fun closeRack() {
        rackJob?.cancel()
        rackMembers = emptyList()
        _rackPlates.value = emptyMap()
    }

    /** The drawing with its ports, or null when the device has none (or it cannot be read). */
    private suspend fun loadView(deviceId: String, known: Faceplate? = null): FaceplateView? {
        val plate = known ?: repository.faceplate(deviceId).getOrNull() ?: return null
        val bytes = repository.faceplateSvg(deviceId).getOrNull() ?: return null
        val picture = withContext(Dispatchers.Default) { PlanImport.picture(bytes) } ?: return null
        val ports = repository.ports(deviceId).getOrDefault(emptyList())
        return FaceplateView(plate, picture, ports.associateBy { it.id })
    }

    fun openEditor(device: Device, inventory: List<Device>) {
        val sources = inventory.filter { it.id != device.id && it.type == device.type }
            .sortedWith(compareBy({ it.model != device.model }, { it.name.lowercase() }))
        _editor.value = FaceplateEditorState(device, copySources = sources)
        viewModelScope.launch {
            val ports = repository.ports(device.id).getOrElse { e ->
                _editor.update { it?.copy(loading = false, error = e.toUserMessage()) }
                return@launch
            }.sortedBy { it.number }
            val plate = repository.faceplate(device.id).getOrElse { e ->
                _editor.update { it?.copy(loading = false, ports = ports, error = e.toUserMessage()) }
                return@launch
            }
            val view = plate?.let { loadView(device.id, it) }
            applyLoaded(ports, view)
        }
    }

    private fun applyLoaded(ports: List<SwitchPort>, view: FaceplateView?) {
        val marks = view?.faceplate?.ports.orEmpty().associateBy { it.portId }
        val last = marks.values.maxByOrNull { it.portNumber }
        _editor.update { s ->
            s?.copy(
                loading = false,
                busy = false,
                view = view,
                ports = ports,
                marks = marks,
                dirty = false,
                error = null,
                markW = last?.w ?: s.markW,
                markH = last?.h ?: s.markH,
                selectedPortId = ports.firstOrNull { it.id !in marks }?.id ?: ports.firstOrNull()?.id,
            )
        }
    }

    fun closeEditor() {
        _editor.value = null
        // The rack shows the drawing as saved.
        if (rackMembers.isNotEmpty()) {
            val members = rackMembers
            rackMembers = emptyList()
            showRack(members)
        }
    }

    fun selectPort(id: String) = _editor.update { it?.copy(selectedPortId = id) }

    fun setStep(step: Int) = _editor.update { it?.copy(step = step) }

    /** New size for marks: the selected mark changes at once, new marks take it too. */
    fun setMarkSize(w: Float, h: Float) = _editor.update { s ->
        s ?: return@update null
        val selected = s.selectedPortId?.let { s.marks[it] }
        val marks = if (selected != null) {
            s.marks + (selected.portId to FaceplateMarks.centredAt(s.selectedPort!!, selected.centerX, selected.centerY, w, h))
        } else {
            s.marks
        }
        s.copy(markW = w, markH = h, marks = marks, dirty = s.dirty || selected != null)
    }

    /** A tap on the drawing at fractions [fx], [fy]: picks the mark under it, or places the selected port there. */
    fun tap(fx: Float, fy: Float) = _editor.update { s ->
        s ?: return@update null
        val hit = s.marks.values.firstOrNull { it.contains(fx, fy) }
        if (hit != null && hit.portId != s.selectedPortId) return@update s.copy(selectedPortId = hit.portId)
        val port = s.selectedPort ?: return@update s
        val marks = s.marks + (port.id to FaceplateMarks.centredAt(port, fx, fy, s.markW, s.markH))
        val next = FaceplateMarks.next(s.ports, port, s.step, marks.keys)
        s.copy(marks = marks, selectedPortId = next?.id ?: port.id, dirty = true, error = null)
    }

    /** Fills the rest of the row from the spacing of the two marks before the selected port. */
    fun continueRow() = _editor.update { s ->
        s ?: return@update null
        val port = s.selectedPort ?: return@update s
        val filled = FaceplateMarks.continueRow(s.ports, s.marks, port, s.step)
            ?: return@update s.copy(error = "Сначала отметьте два порта перед выбранным (с тем же шагом): по ним считается шаг ряда.")
        val next = FaceplateMarks.next(s.ports, port, s.step, filled.keys)
        s.copy(marks = filled, selectedPortId = next?.id ?: port.id, dirty = true, error = null)
    }

    fun removeMark() = _editor.update { s ->
        s ?: return@update null
        val id = s.selectedPortId ?: return@update s
        if (id !in s.marks) return@update s
        s.copy(marks = s.marks - id, dirty = true)
    }

    fun clearMarks() = _editor.update { s -> s?.copy(marks = emptyMap(), dirty = true, selectedPortId = s.ports.firstOrNull()?.id) }

    fun saveMarks() {
        val s = _editor.value ?: return
        if (s.busy || s.view == null) return
        _editor.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            repository.saveFaceplatePorts(s.device.id, s.marks.values).fold(
                onSuccess = { plate ->
                    val view = s.view
                    _editor.update { it?.copy(busy = false, dirty = false, view = FaceplateView(plate, view.picture, view.ports)) }
                },
                onFailure = { e -> _editor.update { it?.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }

    /** A new SVG from the phone. Marks already placed stay where they were. */
    fun upload(uri: Uri) {
        val s = _editor.value ?: return
        _editor.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val data = input.readBytes()
                        if (data.size > MAX_SVG_BYTES) throw UserFacingException("SVG больше 2 МБ: упростите схему или сохраните её без встроенных картинок.")
                        data
                    } ?: throw UserFacingException("Не удалось открыть файл")
                }
            }.getOrElse { e ->
                _editor.update { it?.copy(busy = false, error = e.toUserMessage()) }
                return@launch
            }
            if (!PlanImport.looksLikeSvg(bytes) || PlanImport.picture(bytes) == null) {
                _editor.update { it?.copy(busy = false, error = "Это не SVG. Нужна схема лицевой панели в формате SVG.") }
                return@launch
            }
            // Unsaved marks go with the new drawing.
            val pending = _editor.value?.takeIf { it.dirty }?.marks?.values
            repository.uploadFaceplate(s.device.id, bytes)
                .mapCatching { plate -> if (pending != null) repository.saveFaceplatePorts(s.device.id, pending).getOrThrow() else plate }
                .fold(
                    onSuccess = { plate -> applyLoaded(s.ports, loadView(s.device.id, plate)) },
                    onFailure = { e -> _editor.update { it?.copy(busy = false, error = e.toUserMessage()) } },
                )
        }
    }

    fun copyFrom(source: Device) {
        val s = _editor.value ?: return
        _editor.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            repository.copyFaceplate(s.device.id, source.id).fold(
                onSuccess = { plate -> applyLoaded(s.ports, loadView(s.device.id, plate)) },
                onFailure = { e -> _editor.update { it?.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }

    fun deleteDrawing() {
        val s = _editor.value ?: return
        _editor.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            repository.deleteFaceplate(s.device.id).fold(
                onSuccess = { applyLoaded(s.ports, null) },
                onFailure = { e -> _editor.update { it?.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }

    private companion object {
        const val MAX_SVG_BYTES = 2 * 1024 * 1024
    }
}
