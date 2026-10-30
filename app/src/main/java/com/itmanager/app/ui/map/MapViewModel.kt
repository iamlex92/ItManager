package com.itmanager.app.ui.map

import android.content.Context
import android.graphics.Picture
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.network.toUserMessage
import com.itmanager.app.data.model.ALL_DEVICES
import com.itmanager.app.data.model.Annotation
import com.itmanager.app.data.model.AnnotationKind
import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.DeviceTemplate
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.MapPoint
import com.itmanager.app.data.model.Drawing
import com.itmanager.app.data.model.CableRoute
import com.itmanager.app.data.model.FloorDraft
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.FloorPlan
import com.itmanager.app.data.model.LiveEvent
import com.itmanager.app.data.model.MapLayer
import com.itmanager.app.data.model.PlanRect
import com.itmanager.app.data.model.RackStyle
import com.itmanager.app.data.model.Waypoint
import com.itmanager.app.data.model.Zone
import com.itmanager.app.data.model.removed
import com.itmanager.app.data.model.routeOn
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
    /** Devices on this floor. */
    val devicesById: Map<String, Device>,
    /** VLAN carried by each access cable, or null for trunks between switches. */
    val linkVlan: Map<String, Int?>,
) {
    /** This floor's devices and the far ends of cables to other floors, for tracing a path. */
    val allDevices: Map<String, Device> get() = devicesById + map.remoteDevices

    /** Each cable as it runs on this floor; [waypoints] overrides routes being edited. */
    fun routes(waypoints: Map<String, List<Waypoint>> = emptyMap()): List<CableRoute> {
        val floorId = map.plan.id
        return map.connections.mapNotNull { link ->
            val l = waypoints[link.id]?.let { link.copy(waypoints = it) } ?: link
            routeOn(l, floorId, devicesById) { id -> map.remoteDevices[id]?.floorId }
        }
    }

    /** The map without the chosen device types, and the cables that end at them: the legend hides kinds this way. */
    fun without(hidden: Set<DeviceType>): MapContent {
        if (hidden.isEmpty()) return this
        val gone = map.devices.filter { it.type in hidden }.map { it.id }.toSet()
        val links = map.connections.filter { it.deviceAId !in gone && it.deviceBId !in gone }
        return copy(
            map = map.copy(devices = map.devices.filter { it.id !in gone }, connections = links),
            devicesById = devicesById - gone,
            linkVlan = linkVlan.filterKeys { id -> links.any { it.id == id } },
        )
    }

    fun floorTitle(id: String?): String = map.floors.firstOrNull { it.id == id }?.title ?: "другой этаж"
}

data class MapUiState(
    val content: UiState<MapContent> = UiState.Loading,
    /** The floor picked in the switcher; null: the lowest one. */
    val floorId: String? = null,
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
    /** The rack whose front view is open. */
    val rack: RackViewState? = null,
    /** «Обновить»: the server is checking every device now. */
    val checking: Boolean = false,
    /** A one-time message for a toast, cleared once shown. */
    val notice: String? = null,
)

/** The open rack view: every device of the inventory, so unmounted ones can be picked for a free unit. */
data class RackViewState(
    val rackId: String,
    val devices: List<Device> = emptyList(),
    val busy: Boolean = true,
    val error: String? = null,
)

/** [short]: the caption under the tool's icon in the editor bar. */
enum class EditTool(val label: String, val short: String, val hint: String) {
    MOVE("Объекты", "Объекты", "Перетащите устройство или коснитесь его, чтобы изменить. «+» добавляет новое."),
    LINK("Связи", "Кабели", "Коснитесь двух устройств, чтобы соединить их. Коснитесь кабеля, чтобы проложить его по своей трассе."),
    DRAW("Карандаш", "Рисунок", "Рисуйте одним пальцем, двумя двигайте карту."),
    ZONES("Помещения", "Комнаты", "Коснитесь карты, чтобы поставить углы зоны. Коснитесь готовой зоны, чтобы изменить её."),
    MARKS("Метки", "Метки", "Коснитесь карты, чтобы поставить метку; стрелку ставят двумя касаниями: откуда и куда. Метку можно перетащить."),
    PLAN("Подложка", "План", "Загрузите план здания и подвиньте его пальцем под устройства."),
}

/** The mark form: a new mark (not yet on the floor) or one picked on the map. */
data class MarkEdit(val annotation: Annotation, val isNew: Boolean)

data class MapEditorState(
    val tool: EditTool = EditTool.MOVE,
    /** First device picked for a new cable. */
    val linkFrom: String? = null,
    val cable: String = CABLE_COPPER,
    val penColor: Long = PEN_COLORS.first(),
    /** Positions already dropped on the map while the server saves them, by device id. */
    val moved: Map<String, Offset> = emptyMap(),
    /** The cable whose route is being edited, and its bend picked for deletion (index in its waypoints). */
    val selectedLink: String? = null,
    val selectedPoint: Int? = null,
    /** Routes already changed on the map while the server saves them, by cable id. */
    val routes: Map<String, List<Waypoint>> = emptyMap(),
    val form: DeviceForm? = null,
    /** Zone tool: corners placed so far (x, y pairs, map pixels) and the zone picked for editing. */
    val zoneDraft: List<Float> = emptyList(),
    val selectedZone: String? = null,
    /** Marks tool: the kind and colour of the next mark, the tail of an arrow being placed, the form. */
    val markKind: AnnotationKind = AnnotationKind.BADGE,
    val markColor: Long = MARK_COLORS.first(),
    val arrowStart: Offset? = null,
    val markEdit: MarkEdit? = null,
    val busy: Boolean = false,
    /** An error from the last change; the tool hint shows when there is none. */
    val error: String? = null,
)

/** The device form: [device] null adds a new object at [x], [y]. [floorId]: the floor it stands on. */
data class DeviceForm(
    val device: Device?,
    val x: Float,
    val y: Float,
    val floorId: String?,
    val error: String? = null,
    /** The category a new object starts with, e.g. a rack added with its own button. */
    val presetType: DeviceType? = null,
)

const val CABLE_COPPER = "Cat6"
const val CABLE_FIBER = "OM3"
val CABLE_COLORS = listOf(0xFFFF453AL, 0xFFFF9F0AL, 0xFFFFD60AL, 0xFF30D158L, 0xFF0A84FFL, 0xFFBF5AF2L, 0xFFFFFFFFL)
val PEN_COLORS = listOf(0xFFFFD60AL, 0xFFFF453AL, 0xFF30D158L, 0xFF0A84FFL, 0xFFFFFFFFL)

/** Server limits on drawings: lines per floor and x, y pairs per line. */
const val MAX_DRAWINGS = 500
const val MAX_STROKE_POINTS = 3_900

/** Server limits on zones: zones per floor and corners per zone. */
const val MAX_ZONES = 200
const val MAX_ANNOTATIONS = 500
val MARK_COLORS = listOf(0xFFFFD60A, 0xFF0A84FF, 0xFF30D158, 0xFFFF453A, 0xFFFFFFFF)
const val MAX_ZONE_CORNERS = 900

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: DcimRepository,
    private val mapFocus: MapFocus,
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
                when (event) {
                    is LiveEvent.DeviceStatus -> applyDeviceStatus(event.deviceId, event.isOnline)
                    // Every screen reloads: the map, the device list, the runbooks.
                    LiveEvent.InventoryChanged -> repository.inventoryChanged()
                    else -> Unit
                }
            }
        }
    }

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

    /** Asks the server to check every device now (switch ports, ping); the map reloads with what it found. */
    fun refreshStatus() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val notice = repository.refreshStatus().fold(
                onSuccess = { r ->
                    buildString {
                        append("В сети ${r.online} из ${r.checked}")
                        if (r.changed > 0) append(", изменилось: ${r.changed}")
                        if (r.errors.isNotEmpty()) append(". ").append(r.errors.joinToString("; "))
                    }
                },
                onFailure = { e ->
                    load()
                    e.toUserMessage()
                },
            )
            _state.update { it.copy(checking = false, notice = notice) }
        }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    private suspend fun load() {
        val previous = _state.value.content
        _state.update {
            it.copy(content = if (previous is UiState.Content) previous.copy(isRefreshing = true) else UiState.Loading)
        }
        val floorId = _state.value.floorId
        repository.floorMap(floorId)
            .onSuccess { map ->
                // The floor was switched again while this one loaded: the newer load wins.
                if (_state.value.floorId != floorId) return
                val content = MapContent(map, map.devices.associateBy { it.id }, accessLinkVlans(map))
                // Dropped positions and routes are on the server now; the reload carries them.
                _state.update { s ->
                    s.copy(
                        content = UiState.Content(content),
                        editor = s.editor.copy(
                            moved = emptyMap(),
                            routes = emptyMap(),
                            selectedLink = s.editor.selectedLink?.takeIf { id -> map.connections.any { it.id == id } },
                        ),
                    )
                }
                // Keep the selection only if the device still exists after a reload.
                select(_state.value.selectedId?.takeIf { it in content.devicesById })
                loadPlan(map.plan.imageUrl)
            }
            .onFailure { e -> _state.update { it.copy(content = UiState.Error(e.toUserMessage())) } }
    }

    /** Shows another floor: its plan, devices and cables. */
    fun selectFloor(id: String) {
        if (id == currentFloorId()) return
        select(null)
        _state.update {
            it.copy(
                floorId = id,
                planPicture = null,
                editor = it.editor.copy(selectedLink = null, selectedPoint = null, linkFrom = null),
            )
        }
        planUrl = null
        viewModelScope.launch { load() }
    }

    private fun currentFloorId(): String? = content?.map?.plan?.id

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
        val links = traceToSwitch(deviceId, content.allDevices, content.map.connections)
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

    fun setTool(tool: EditTool) = editor {
        it.copy(tool = tool, linkFrom = null, error = null, selectedLink = null, selectedPoint = null, zoneDraft = emptyList(), selectedZone = null, arrowStart = null)
    }
    fun setCable(cable: String) = editor { it.copy(cable = cable) }
    fun cancelLink() = editor { it.copy(linkFrom = null) }
    fun setPenColor(color: Long) = editor { it.copy(penColor = color) }
    fun dismissError() = editor { it.copy(error = null) }

    fun openNewDevice(x: Float, y: Float, type: DeviceType? = null) =
        editor { it.copy(form = DeviceForm(null, x, y, currentFloorId(), presetType = type)) }
    fun openDevice(id: String) {
        val device = content?.devicesById?.get(id) ?: return
        editor { it.copy(form = DeviceForm(device, device.x, device.y, currentFloorId())) }
    }
    fun setFormFloor(floorId: String) = editor { it.copy(form = it.form?.copy(floorId = floorId)) }
    fun closeForm() = editor { it.copy(form = null) }

    fun saveDevice(draft: DeviceDraft) = write {
        val form = _state.value.editor.form ?: return@write
        val result = if (form.device == null) {
            val floor = floor() ?: return@write
            val known = content?.map?.floors.orEmpty().map { it.id }
            repository.createDevice(form.floorId?.takeIf { it in known } ?: floor.id, draft, form.x, form.y)
        } else {
            repository.updateDevice(form.device.id, draft).onSuccess {
                // Moved to another floor: it keeps its place on the plan there.
                if (form.floorId != null && form.floorId != currentFloorId()) {
                    repository.moveDevice(form.device.id, form.floorId, form.device.x, form.device.y)
                }
            }
        }
        result
            .onSuccess { editor { it.copy(form = null) } }
            .onFailure { e -> editor { s -> s.copy(form = s.form?.copy(error = e.toUserMessage())) } }
    }

    private val _templates = MutableStateFlow<List<DeviceTemplate>>(emptyList())
    /** Device templates for «+ Из шаблона»; loaded when the picker opens. */
    val templates: StateFlow<List<DeviceTemplate>> = _templates.asStateFlow()

    fun loadTemplates() {
        viewModelScope.launch {
            repository.deviceTemplates().onSuccess { list -> _templates.value = list.sortedBy { it.name.lowercase() } }
        }
    }

    /** A device made from [template] and put on this floor at [x], [y]. [done] gets null on success or the error. */
    fun createFromTemplate(template: DeviceTemplate, name: String, ip: String?, x: Float, y: Float, done: (String?) -> Unit) = write {
        val floor = floor() ?: return@write
        repository.createDeviceFromTemplate(template.id, name, ip, MapPoint(floor.id, x, y))
            .onSuccess { done(null) }
            .onFailure { done(it.toUserMessage()) }
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

    /** Gives the selected cable a colour on the map; null gives the automatic one back. */
    fun recolorLink(colorArgb: Long?) {
        val id = _state.value.editor.selectedLink ?: return
        write { repository.recolorConnection(id, colorArgb).onFailure(::fail) }
    }

    fun deleteLink(connection: Connection) {
        editor { it.copy(selectedLink = null, selectedPoint = null) }
        write { repository.deleteConnection(connection.id).onFailure(::fail) }
    }

    // --- Cable routes: bends dragged on the map, inventory only. ---

    /** Link tool: a tapped cable shows its bends to drag; tapping empty map lets it go. */
    fun selectLink(id: String?) = editor { it.copy(selectedLink = id, selectedPoint = null, linkFrom = null, error = null) }

    fun selectPoint(index: Int?) = editor { it.copy(selectedPoint = index) }

    /** The selected cable's new bends, all floors at once: shown at once, saved behind. */
    fun setRoute(linkId: String, waypoints: List<Waypoint>) {
        editor { it.copy(routes = it.routes + (linkId to waypoints), selectedPoint = null) }
        write {
            val latest = _state.value.editor.routes[linkId] ?: waypoints
            repository.routeConnection(linkId, latest).onFailure { e ->
                editor { it.copy(routes = it.routes - linkId, error = e.toUserMessage()) }
            }
        }
    }

    fun deletePoint() {
        val s = _state.value.editor
        val id = s.selectedLink ?: return
        val index = s.selectedPoint ?: return
        setRoute(id, waypointsOf(id).removed(index))
    }

    /** Removes the bends on this floor; bends on other floors stay. */
    fun straighten() {
        val id = _state.value.editor.selectedLink ?: return
        val route = content?.routes(_state.value.editor.routes)?.firstOrNull { it.link.id == id } ?: return
        val here = route.points.mapNotNull { p -> p.index.takeIf { it in route.link.waypoints.indices } }.toSet()
        setRoute(id, route.link.waypoints.filterIndexed { i, _ -> i !in here })
    }

    private fun waypointsOf(id: String): List<Waypoint> =
        _state.value.editor.routes[id] ?: content?.map?.connections?.firstOrNull { it.id == id }?.waypoints.orEmpty()

    // --- Floors of the building. ---

    fun createFloor(draft: FloorDraft) = write {
        repository.createFloor(draft).onSuccess { selectFloor(it.id) }.onFailure(::fail)
    }

    fun renameFloor(id: String, draft: FloorDraft) = write { repository.renameFloor(id, draft).onFailure(::fail) }

    fun deleteFloor(id: String) = write {
        repository.deleteFloor(id)
            .onSuccess { if (_state.value.floorId == id) _state.update { it.copy(floorId = null) } }
            .onFailure(::fail)
    }

    /** Swaps the numbers of [id] and its neighbour below ([up] false) or above, so the order changes. */
    fun moveFloor(id: String, up: Boolean) = write {
        val floors = content?.map?.floors ?: return@write
        val i = floors.indexOfFirst { it.id == id }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in floors.indices) return@write
        val a = floors[i]
        val b = floors[j]
        // Equal numbers (two buildings) still need an order: step past the neighbour.
        val (na, nb) = if (a.number != b.number) b.number to a.number else (if (up) b.number + 1 else b.number - 1) to b.number
        repository.renameFloor(a.id, FloorDraft(a.building, na, a.name))
            .onSuccess { repository.renameFloor(b.id, FloorDraft(b.building, nb, b.name)).onFailure(::fail) }
            .onFailure(::fail)
    }

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

    fun setMarkKind(kind: AnnotationKind) = editor { it.copy(markKind = kind, arrowStart = null) }
    fun setMarkColor(color: Long) = editor { it.copy(markColor = color) }
    fun cancelArrow() = editor { it.copy(arrowStart = null) }
    fun closeMark() = editor { it.copy(markEdit = null) }

    /**
     * Marks tool, a tap at [x], [y] (map pixels) on the mark [hitId] or on empty map: opens that mark, places the
     * tail of a new arrow, finishes the arrow, or opens the form of a new number or caption there.
     */
    fun tapMarks(hitId: String?, x: Float, y: Float) {
        val e = _state.value.editor
        val plan = content?.map?.plan ?: return
        if (hitId != null && e.arrowStart == null) {
            plan.annotations.firstOrNull { it.id == hitId }?.let { a -> editor { it.copy(markEdit = MarkEdit(a, isNew = false)) } }
            return
        }
        if (plan.annotations.size >= MAX_ANNOTATIONS) {
            editor { it.copy(error = "На этаже уже $MAX_ANNOTATIONS меток: удалите лишние.") }
            return
        }
        val id = "m" + java.util.UUID.randomUUID().toString().take(8)
        when {
            e.markKind != AnnotationKind.ARROW ->
                editor { it.copy(markEdit = MarkEdit(Annotation(id, e.markKind, x, y, "", e.markColor), isNew = true), error = null) }
            e.arrowStart == null -> editor { it.copy(arrowStart = Offset(x, y), error = null) }
            else -> {
                val start = e.arrowStart ?: return
                val arrow = Annotation(id, AnnotationKind.ARROW, start.x, start.y, "", e.markColor, x2 = x, y2 = y)
                editor { it.copy(arrowStart = null, markEdit = MarkEdit(arrow, isNew = true), error = null) }
            }
        }
    }

    /** «+ Метка» from the add menu: the marks tool, with a new room number at [x], [y]. */
    fun addMarkAt(x: Float, y: Float) {
        setTool(EditTool.MARKS)
        editor { it.copy(markKind = AnnotationKind.BADGE, arrowStart = null) }
        tapMarks(null, x, y)
    }

    /** Saves a new mark or the changes to one, matched by id. */
    fun saveMark(annotation: Annotation) {
        val plan = content?.map?.plan ?: return
        val list = if (plan.annotations.any { it.id == annotation.id }) {
            plan.annotations.map { if (it.id == annotation.id) annotation else it }
        } else {
            plan.annotations + annotation
        }
        editor { it.copy(markEdit = null) }
        saveAnnotations(list)
    }

    fun deleteMark(id: String) {
        val plan = content?.map?.plan ?: return
        editor { it.copy(markEdit = null) }
        saveAnnotations(plan.annotations.filter { it.id != id })
    }

    /** A mark dragged by [dx], [dy] map pixels. */
    fun moveMark(id: String, dx: Float, dy: Float) {
        val plan = content?.map?.plan ?: return
        saveAnnotations(plan.annotations.map { if (it.id == id) it.movedBy(dx, dy) else it })
    }

    private fun saveAnnotations(list: List<Annotation>) {
        updatePlan { it.copy(annotations = list) }
        write {
            val floor = floor() ?: return@write
            val latest = content?.map?.plan?.annotations ?: list
            repository.saveAnnotations(floor, latest).onFailure { failAndReload(it) }
        }
    }

    /** Zone tool, a tap at [x], [y]: adds a corner to the zone being outlined, picks a zone, or starts a new outline. */
    fun tapZones(x: Float, y: Float) {
        val e = _state.value.editor
        val plan = content?.map?.plan ?: return
        when {
            e.zoneDraft.isNotEmpty() -> {
                if (e.zoneDraft.size / 2 >= MAX_ZONE_CORNERS) editor { it.copy(error = "В зоне уже $MAX_ZONE_CORNERS углов: завершите её.") }
                else editor { it.copy(zoneDraft = it.zoneDraft + listOf(x, y), error = null) }
            }
            else -> {
                val hit = plan.zones.filter { it.contains(x, y) }.minByOrNull { it.area }
                if (hit != null) {
                    editor { it.copy(selectedZone = hit.id, error = null) }
                } else if (plan.zones.size >= MAX_ZONES) {
                    editor { it.copy(error = "На этаже уже $MAX_ZONES зон: удалите лишние.") }
                } else {
                    editor { it.copy(zoneDraft = listOf(x, y), selectedZone = null, error = null) }
                }
            }
        }
    }

    fun undoZoneCorner() = editor { it.copy(zoneDraft = it.zoneDraft.dropLast(2)) }
    fun cancelZone() = editor { it.copy(zoneDraft = emptyList(), selectedZone = null) }

    /** Saves a new zone or the changes to one, matched by id. */
    fun saveZone(zone: Zone) {
        val plan = content?.map?.plan ?: return
        val zones = if (plan.zones.any { it.id == zone.id }) plan.zones.map { if (it.id == zone.id) zone else it } else plan.zones + zone
        editor { it.copy(zoneDraft = emptyList(), selectedZone = zone.id) }
        saveZones(zones)
    }

    fun deleteZone(id: String) {
        val plan = content?.map?.plan ?: return
        editor { it.copy(selectedZone = null) }
        saveZones(plan.zones.filter { it.id != id })
    }

    private fun saveZones(zones: List<Zone>) {
        updatePlan { it.copy(zones = zones) }
        write {
            val floor = floor() ?: return@write
            val latest = content?.map?.plan?.zones ?: zones
            repository.saveZones(floor, latest).onFailure { failAndReload(it) }
        }
    }

    // --- Rack view ---

    fun openRack(id: String) {
        _state.update { it.copy(rack = RackViewState(id)) }
        viewModelScope.launch { refreshRack() }
    }

    fun closeRack() = _state.update { it.copy(rack = null) }

    private suspend fun refreshRack() {
        repository.devices()
            .onSuccess { list -> _state.update { s -> s.copy(rack = s.rack?.copy(devices = list, busy = false)) } }
            .onFailure { e -> _state.update { s -> s.copy(rack = s.rack?.copy(busy = false, error = e.toUserMessage())) } }
    }

    /** Mounts [deviceId] from unit [u], [h] units high; null [rackId] takes it out of its rack. */
    fun mount(deviceId: String, rackId: String?, u: Int?, h: Int?) {
        val device = _state.value.rack?.devices?.firstOrNull { it.id == deviceId } ?: return
        _state.update { s -> s.copy(rack = s.rack?.copy(busy = true, error = null)) }
        viewModelScope.launch {
            repository.updateDevice(deviceId, device.toDraft().copy(rackId = rackId, rackU = u, rackH = h))
                .onFailure { e -> _state.update { s -> s.copy(rack = s.rack?.copy(busy = false, error = e.toUserMessage())) } }
                .onSuccess { refreshRack() }
        }
    }

    /** The rack's own settings: name, height in units and how it is drawn. */
    fun saveRack(rack: Device, name: String, units: Int, style: RackStyle) {
        val draft = rack.toDraft().copy(
            name = name.trim().ifEmpty { rack.name },
            rackUnits = units,
            metadata = style.writeTo(rack.metadata),
        )
        _state.update { s -> s.copy(rack = s.rack?.copy(busy = true, error = null)) }
        viewModelScope.launch {
            repository.updateDevice(rack.id, draft)
                .onFailure { e -> _state.update { s -> s.copy(rack = s.rack?.copy(busy = false, error = e.toUserMessage())) } }
                .onSuccess { refreshRack() }
        }
    }

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
        val names = mutableListOf(content.allDevices[startId]?.name ?: return emptyList())
        var current = startId
        for (id in linkIds) {
            val link = byId[id] ?: break
            current = if (link.deviceAId == current) link.deviceBId else link.deviceAId
            names += content.allDevices[current]?.name ?: break
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
