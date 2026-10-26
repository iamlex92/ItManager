package com.itmanager.app.ui.map

import android.content.Context
import android.graphics.Picture
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.core.nfc.NfcController
import com.itmanager.app.data.model.ALL_DEVICES
import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.Drawing
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.FloorPlan
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.MapLayer
import com.itmanager.app.data.model.PlanRect
import com.itmanager.app.data.model.traceToSwitch
import com.itmanager.app.data.source.DcimRepository
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.navigation.MapFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A floor map plus lookups the canvas needs every frame, computed once per load. */
data class MapContent(
    val map: FloorMap,
    val devicesById: Map<String, Device>,
    /** VLAN carried by each access cable, or null for trunks between switches. */
    val linkVlan: Map<String, Int?>,
)

data class MapUiState(
    val content: UiState<MapContent> = UiState.Loading,
    val layer: MapLayer = MapLayer.L1,
    val vlanFilter: Int? = null,
    val selectedId: String? = null,
    val tracedLinks: Set<String> = emptySet(),
    val tracePath: List<String> = emptyList(),
    /** Floor plan from the server, already decoded; null while loading or when there is none. */
    val planPicture: Picture? = null,
    /** The map editor is open: markers can be dragged, cables drawn, the plan placed. */
    val editing: Boolean = false,
    val editor: MapEditorState = MapEditorState(),
)

enum class EditTool(val label: String, val hint: String) {
    MOVE("Объекты", "Перетащите устройство или коснитесь его, чтобы изменить. «+» добавляет новое."),
    LINK("Связи", "Коснитесь двух устройств, чтобы соединить их. Коснитесь кабеля, чтобы удалить."),
    DRAW("Карандаш", "Рисуйте одним пальцем, двумя двигайте карту."),
    PLAN("Подложка", "Загрузите план здания и подвиньте его пальцем под устройства."),
}

data class MapEditorState(
    val tool: EditTool = EditTool.MOVE,
    /** First device picked for a new cable. */
    val linkFrom: String? = null,
    val cable: String = CABLE_COPPER,
    val penColor: Long = PEN_COLORS.first(),
    /** Positions already dropped on the map while the server saves them, by device id. */
    val moved: Map<String, Offset> = emptyMap(),
    val form: DeviceForm? = null,
    val busy: Boolean = false,
    /** An error from the last change; the tool hint shows when there is none. */
    val error: String? = null,
)

/** The device form: [device] null adds a new object at [x], [y]. */
data class DeviceForm(val device: Device?, val x: Float, val y: Float, val error: String? = null)

const val CABLE_COPPER = "Cat6"
const val CABLE_FIBER = "OM3"
val PEN_COLORS = listOf(0xFFFFD60AL, 0xFFFF453AL, 0xFF30D158L, 0xFF0A84FFL, 0xFFFFFFFFL)

/** Server limits on drawings: lines per floor and x, y pairs per line. */
const val MAX_DRAWINGS = 500
const val MAX_STROKE_POINTS = 3_900

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: DcimRepository,
    private val mapFocus: MapFocus,
    private val nfc: NfcController,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    /** A device another tab asked to show; the screen centres on it and then calls [focusHandled]. */
    val focusRequest: StateFlow<String?> = mapFocus.deviceId

    private var planUrl: String? = null
    /** Editor writes go one at a time, so drawings and positions reach the server in the order made. */
    private val editLock = Mutex()

    init {
        viewModelScope.launch {
            repository.settings.collect { load() }
        }
        viewModelScope.launch {
            repository.live(ALL_DEVICES).collect { event ->
                if (event is LiveEvent.DeviceStatus) applyDeviceStatus(event.deviceId, event.isOnline)
            }
        }
    }

    /** Opens the binding sheet; the next tag held to the phone gets bound to [device]. */
    fun bindNfc(device: Device) = nfc.startBinding(device)

    private fun applyDeviceStatus(deviceId: String, isOnline: Boolean) = _state.update { s ->
        val content = (s.content as? UiState.Content)?.data ?: return@update s
        val device = content.devicesById[deviceId]?.takeIf { it.isOnline != isOnline } ?: return@update s
        val updated = device.copy(isOnline = isOnline)
        val map = content.map.copy(devices = content.map.devices.map { if (it.id == deviceId) updated else it })
        s.copy(content = (s.content as UiState.Content).copy(data = content.copy(map = map, devicesById = content.devicesById + (deviceId to updated))))
    }

    private suspend fun loadPlan(url: String?) {
        if (url == planUrl) return
        planUrl = url
        _state.update { it.copy(planPicture = null) }
        if (url == null) return
        repository.planBytes(url).onSuccess { bytes ->
            val picture = withContext(Dispatchers.Default) { PlanImport.picture(bytes) }
            // A plan that fails to load or decode is not fatal: the map still works on the grid.
            if (planUrl == url) _state.update { it.copy(planPicture = picture) }
        }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val previous = _state.value.content
        _state.update {
            it.copy(content = if (previous is UiState.Content) previous.copy(isRefreshing = true) else UiState.Loading)
        }
        repository.floorMap()
            .onSuccess { map ->
                val content = MapContent(map, map.devices.associateBy { it.id }, accessLinkVlans(map))
                // Dropped positions are on the server now; the reload carries them.
                _state.update { it.copy(content = UiState.Content(content), editor = it.editor.copy(moved = emptyMap())) }
                // Keep the selection only if the device still exists after a reload.
                select(_state.value.selectedId?.takeIf { it in content.devicesById })
                loadPlan(map.plan.imageUrl)
            }
            .onFailure { e -> _state.update { it.copy(content = UiState.Error(e.toUserMessage())) } }
    }

    fun setLayer(layer: MapLayer) = _state.update {
        it.copy(layer = layer, vlanFilter = if (layer == MapLayer.L1) null else it.vlanFilter)
    }

    fun setVlanFilter(vlanId: Int?) = _state.update { it.copy(vlanFilter = vlanId) }

    fun select(deviceId: String?) {
        val content = (_state.value.content as? UiState.Content)?.data
        if (deviceId == null || content == null) {
            _state.update { it.copy(selectedId = null, tracedLinks = emptySet(), tracePath = emptyList()) }
            return
        }
        val links = traceToSwitch(deviceId, content.devicesById, content.map.connections)
        _state.update {
            it.copy(
                selectedId = deviceId,
                tracedLinks = links.toSet(),
                tracePath = pathNames(deviceId, links, content),
            )
        }
    }

    fun focusHandled() = mapFocus.consume()

    // --- Map editor. Inventory only: nothing here talks to equipment. ---

    private val content get() = (_state.value.content as? UiState.Content)?.data

    fun setEditing(on: Boolean) {
        select(null)
        _state.update { it.copy(editing = on, editor = MapEditorState(tool = it.editor.tool, cable = it.editor.cable, penColor = it.editor.penColor)) }
    }

    fun setTool(tool: EditTool) = editor { it.copy(tool = tool, linkFrom = null, error = null) }
    fun setCable(cable: String) = editor { it.copy(cable = cable) }
    fun cancelLink() = editor { it.copy(linkFrom = null) }
    fun setPenColor(color: Long) = editor { it.copy(penColor = color) }
    fun dismissError() = editor { it.copy(error = null) }

    fun openNewDevice(x: Float, y: Float) = editor { it.copy(form = DeviceForm(null, x, y)) }
    fun openDevice(id: String) {
        val device = content?.devicesById?.get(id) ?: return
        editor { it.copy(form = DeviceForm(device, device.x, device.y)) }
    }
    fun closeForm() = editor { it.copy(form = null) }

    fun saveDevice(draft: DeviceDraft) = write {
        val form = _state.value.editor.form ?: return@write
        val result = if (form.device == null) {
            val floor = floor() ?: return@write
            repository.createDevice(floor.id, draft, form.x, form.y)
        } else {
            repository.updateDevice(form.device.id, draft)
        }
        result
            .onSuccess { editor { it.copy(form = null) } }
            .onFailure { e -> editor { s -> s.copy(form = s.form?.copy(error = e.toUserMessage())) } }
    }

    fun deleteDevice(id: String) = write {
        repository.deleteDevice(id)
            .onSuccess { editor { it.copy(form = null) } }
            .onFailure { e -> editor { s -> s.copy(form = s.form?.copy(error = e.toUserMessage())) } }
    }

    /** A marker dropped at [x], [y]: it stays there on screen while the server saves it. */
    fun moveDevice(id: String, x: Float, y: Float) {
        val plan = content?.map?.plan ?: return
        val clamped = Offset(x.coerceIn(0f, plan.widthPx), y.coerceIn(0f, plan.heightPx))
        editor { it.copy(moved = it.moved + (id to clamped)) }
        write {
            val floor = floor() ?: return@write
            repository.moveDevice(id, floor.id, clamped.x, clamped.y).onFailure { e ->
                editor { it.copy(moved = it.moved - id, error = e.toUserMessage()) }
            }
        }
    }

    /** Link tool: the first tap picks one end, the second makes the cable; the same device again cancels. */
    fun tapForLink(id: String) {
        val from = _state.value.editor.linkFrom
        when {
            from == null -> editor { it.copy(linkFrom = id, error = null) }
            from == id -> editor { it.copy(linkFrom = null) }
            else -> {
                editor { it.copy(linkFrom = null) }
                write { repository.linkDevices(from, id, _state.value.editor.cable).onFailure(::fail) }
            }
        }
    }

    fun deleteLink(connection: Connection) = write { repository.deleteConnection(connection.id).onFailure(::fail) }

    /** A finished pen line: [points] are x, y pairs and [width] the line width, both in map pixels. */
    fun addStroke(points: List<Float>, width: Float) {
        val plan = content?.map?.plan ?: return
        if (points.size < 4) return
        if (plan.drawings.size >= MAX_DRAWINGS) {
            editor { it.copy(error = "На этаже уже $MAX_DRAWINGS линий: удалите лишние.") }
            return
        }
        val color = _state.value.editor.penColor
        saveDrawings(plan.drawings + Drawing(color, width.coerceIn(1f, 40f), points.take(MAX_STROKE_POINTS * 2)))
    }

    fun undoStroke() {
        val drawings = content?.map?.plan?.drawings ?: return
        if (drawings.isNotEmpty()) saveDrawings(drawings.dropLast(1))
    }

    fun clearDrawings() = saveDrawings(emptyList())

    private fun saveDrawings(drawings: List<Drawing>) {
        updatePlan { it.copy(drawings = drawings) }
        write {
            val floor = floor() ?: return@write
            // The newest list wins: strokes drawn while this one waited are already in it.
            val latest = content?.map?.plan?.drawings ?: drawings
            repository.saveDrawings(floor, latest).onFailure { failAndReload(it) }
        }
    }

    fun uploadPlan(uri: Uri) = write {
        val file = withContext(Dispatchers.IO) { runCatching { PlanImport.read(context.contentResolver, uri) } }
            .getOrElse { fail(it); return@write }
        val floor = floor() ?: return@write
        repository.uploadPlan(floor, file).onSuccess(::applyPlan).onFailure(::fail)
    }

    fun removePlan() = write {
        val floor = floor() ?: return@write
        repository.removePlan(floor).onSuccess(::applyPlan).onFailure(::fail)
    }

    /** The plan dragged or scaled to [rect]; shown at once, saved behind. */
    fun placePlan(rect: PlanRect) {
        // The floor grows to keep the plan inside, as it does on the server.
        updatePlan { it.copy(imageRect = rect, widthPx = maxOf(it.widthPx, rect.right), heightPx = maxOf(it.heightPx, rect.bottom)) }
        write {
            val floor = floor() ?: return@write
            val latest = content?.map?.plan?.imageRect ?: rect
            repository.placePlan(floor, latest).onFailure { failAndReload(it) }
        }
    }

    fun scalePlan(factor: Float) {
        val rect = planRect() ?: return
        val w = rect.width * factor
        val h = rect.height * factor
        placePlan(PlanRect(rect.x + (rect.width - w) / 2f, rect.y + (rect.height - h) / 2f, w, h))
    }

    /** The plan back to the floor's top-left corner, as large as fits the floor. */
    fun fitPlan() {
        val plan = content?.map?.plan ?: return
        val rect = planRect() ?: return
        val k = minOf(plan.widthPx / rect.width, plan.heightPx / rect.height)
        placePlan(PlanRect(0f, 0f, rect.width * k, rect.height * k))
    }

    private fun planRect(): PlanRect? {
        val plan = content?.map?.plan ?: return null
        if (plan.imageUrl == null) return null
        return plan.imageRect ?: PlanRect(0f, 0f, plan.widthPx, plan.heightPx)
    }

    /** The stored floor to save to; a server without floors gets one on the first change. */
    private suspend fun floor(): FloorPlan? {
        val plan = content?.map?.plan ?: return null
        if (plan.stored) return plan
        return repository.ensureFloor(plan).onSuccess(::applyPlan).onFailure(::fail).getOrNull()
    }

    private fun applyPlan(plan: FloorPlan) {
        updatePlan { plan }
        viewModelScope.launch { loadPlan(plan.imageUrl) }
    }

    private fun updatePlan(change: (FloorPlan) -> FloorPlan) = _state.update { s ->
        val c = s.content as? UiState.Content ?: return@update s
        s.copy(content = c.copy(data = c.data.copy(map = c.data.map.copy(plan = change(c.data.map.plan)))))
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            editLock.withLock {
                editor { it.copy(busy = true) }
                try {
                    block()
                } finally {
                    editor { it.copy(busy = false) }
                }
            }
        }
    }

    private fun fail(e: Throwable) = editor { it.copy(error = e.toUserMessage()) }

    private suspend fun failAndReload(e: Throwable) {
        fail(e)
        load()
    }

    private fun editor(change: (MapEditorState) -> MapEditorState) = _state.update { it.copy(editor = change(it.editor)) }

    private fun pathNames(startId: String, linkIds: List<String>, content: MapContent): List<String> {
        if (linkIds.isEmpty()) return emptyList()
        val byId = content.map.connections.associateBy { it.id }
        val names = mutableListOf(content.devicesById[startId]?.name ?: return emptyList())
        var current = startId
        for (id in linkIds) {
            val link = byId[id] ?: break
            current = if (link.deviceAId == current) link.deviceBId else link.deviceAId
            names += content.devicesById[current]?.name ?: break
        }
        return names
    }

    private companion object {
        val PASSIVE = setOf(DeviceType.SOCKET, DeviceType.PATCH_PANEL)

        /**
         * Colours each access cable by the VLAN of the end device it ultimately serves,
         * walking through sockets and patch panels. Cables between switches stay uncoloured (trunks).
         */
        fun accessLinkVlans(map: FloorMap): Map<String, Int?> {
            val devices = map.devices.associateBy { it.id }
            val adjacency = HashMap<String, MutableList<Connection>>()
            for (c in map.connections) {
                adjacency.getOrPut(c.deviceAId) { mutableListOf() }.add(c)
                adjacency.getOrPut(c.deviceBId) { mutableListOf() }.add(c)
            }
            val result = HashMap<String, Int?>()
            for (end in map.devices) {
                if (end.type in PASSIVE || end.type == DeviceType.SWITCH) continue
                val vlan = end.vlanId ?: continue
                val visited = hashSetOf(end.id)
                val queue = ArrayDeque(listOf(end.id))
                while (queue.isNotEmpty()) {
                    val id = queue.removeFirst()
                    for (link in adjacency[id].orEmpty()) {
                        // A cable shared by several VLANs (a patch cord bundle) is drawn neutral.
                        if (!result.containsKey(link.id)) result[link.id] = vlan
                        else if (result[link.id] != vlan) result[link.id] = null
                        val next = if (link.deviceAId == id) link.deviceBId else link.deviceAId
                        if (devices[next]?.type?.let { it in PASSIVE } == true && visited.add(next)) queue.add(next)
                    }
                }
            }
            return result
        }
    }
}
