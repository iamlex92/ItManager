package com.itmanager.app.ui.map

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.itmanager.app.data.model.FloorInfo
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FloorMap
import com.itmanager.app.data.model.MapLayer
import com.itmanager.app.data.model.Zone
import com.itmanager.app.data.model.roomAt
import com.itmanager.app.ui.components.AlertsBell
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.tint
import com.itmanager.app.ui.theme.Ink950
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.Motion
import com.itmanager.app.ui.theme.StatusColors
import com.itmanager.app.data.model.takesPeripherals
import com.itmanager.app.ui.peripherals.DevicePeripheralsDialog
import com.itmanager.app.ui.peripherals.PeripheralEditorDialog
import com.itmanager.app.ui.peripherals.PeripheralsViewModel
import com.itmanager.app.ui.peripherals.peripheralSummary
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MapScreen(
    contentPadding: PaddingValues,
    onOpenSwitch: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScanner: () -> Unit,
    viewModel: MapViewModel = hiltViewModel(),
    peripherals: PeripheralsViewModel = hiltViewModel(),
    faceplates: FaceplateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val peripheralState by peripherals.state.collectAsStateWithLifecycle()
    val peripheralDevices by peripherals.devices.collectAsStateWithLifecycle()
    val peripheralEditor by peripherals.editor.collectAsStateWithLifecycle()
    val allPeripherals = (peripheralState as? UiState.Content)?.data.orEmpty()
    var peripheralsFor by remember { mutableStateOf<Device?>(null) }
    val rackPlates by faceplates.rackPlates.collectAsStateWithLifecycle()
    val faceplateEditor by faceplates.editor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(peripherals) {
        peripherals.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val focusRequest by viewModel.focusRequest.collectAsStateWithLifecycle()
    val camera = rememberMapCamera()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var topPanelHeight by remember { mutableIntStateOf(0) }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    var linkToDelete by remember { mutableStateOf<Connection?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRemovePlan by remember { mutableStateOf(false) }
    var showFloors by remember { mutableStateOf(false) }
    var zoneEdit by remember { mutableStateOf<ZoneEdit?>(null) }
    var showRooms by remember { mutableStateOf(false) }
    var commentFor by remember { mutableStateOf<Device?>(null) }
    var commentError by remember { mutableStateOf<String?>(null) }
    var showLegend by remember { mutableStateOf(false) }
    // Kinds switched off in the legend: they and their cables leave the map until switched back on.
    var hiddenTypes by remember { mutableStateOf(emptySet<DeviceType>()) }
    val scope = rememberCoroutineScope()
    val pickPlan = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::uploadPlan)
    }

    BackHandler(enabled = state.editing) { viewModel.setEditing(false) }

    val content = (state.content as? UiState.Content)?.data

    // Another floor opened: show all of it, as on the first load.
    val floorId = content?.map?.plan?.id
    var shownFloor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(floorId) {
        if (floorId == null) return@LaunchedEffect
        if (shownFloor != null && shownFloor != floorId) {
            repeat(2) { withFrameNanos { } }
            camera.fit()
        }
        shownFloor = floorId
    }

    // "Show on map" from the devices tab: centre on the device once the map is loaded.
    LaunchedEffect(focusRequest, content) {
        val id = focusRequest ?: return@LaunchedEffect
        val device = content?.devicesById?.get(id) ?: return@LaunchedEffect
        viewModel.select(id)
        camera.focusOn(device.x, device.y)
        viewModel.focusHandled()
    }

    Box(Modifier.fillMaxSize().background(Ink950)) {
        when (val c = state.content) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(c.message, viewModel::retry, onOpenSettings, contentPadding = contentPadding)
            // A fresh server has no devices yet: say what to do instead of showing an empty grid.
            is UiState.Content -> if (!state.editing && c.data.map.isBlank() && c.data.map.floors.size <= 1) {
                EmptyMapView(onOpenScanner, onOpenSettings, onDraw = { viewModel.setEditing(true) }, contentPadding)
            } else {
                val bottomPx = if (state.editing) {
                    toolbarHeight + with(density) { (contentPadding.calculateBottomPadding() + 24.dp).toPx() }
                } else {
                    with(density) { (contentPadding.calculateBottomPadding() + 16.dp).toPx() }
                }
                val sidePx = with(density) { 16.dp.toPx() }
                val editor = state.editor
                FloorPlanView(
                    content = c.data.without(hiddenTypes),
                    layer = state.layer,
                    vlanFilter = state.vlanFilter,
                    selectedId = state.selectedId,
                    tracedLinks = state.tracedLinks,
                    planPicture = state.planPicture,
                    camera = camera,
                    visiblePadding = Rect(sidePx, topPanelHeight + sidePx, sidePx, bottomPx),
                    onSelect = { id ->
                        if (id != null && id != state.selectedId) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.select(id)
                    },
                    editor = if (!state.editing) null else MapEditing(
                        tool = editor.tool,
                        linkFrom = editor.linkFrom,
                        penColor = Color(editor.penColor),
                        moved = editor.moved,
                        onTapDevice = { id ->
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            when (editor.tool) {
                                EditTool.LINK -> viewModel.tapForLink(id)
                                // An icon in the way of a corner counts as a tap on the map under it.
                                EditTool.ZONES -> c.data.devicesById[id]?.let { viewModel.tapZones(it.x, it.y) }
                                else -> viewModel.openDevice(id)
                            }
                        },
                        onMoveDevice = { id, x, y ->
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            viewModel.moveDevice(id, x, y)
                        },
                        onTapLink = { link ->
                            if (link != null) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            viewModel.selectLink(link?.id)
                        },
                        selectedLink = editor.selectedLink,
                        selectedPoint = editor.selectedPoint,
                        routes = editor.routes,
                        onTapPoint = { index ->
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            viewModel.selectPoint(if (index == editor.selectedPoint) null else index)
                        },
                        onRoute = { id, points ->
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            viewModel.setRoute(id, points)
                        },
                        onStroke = viewModel::addStroke,
                        onPlanMoved = viewModel::placePlan,
                        zoneDraft = editor.zoneDraft,
                        selectedZone = editor.selectedZone,
                        onTapZones = { x, y ->
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            viewModel.tapZones(x, y)
                        },
                    ),
                )
            }
        }

        MapTopPanel(
            title = content?.map?.plan?.title ?: "Карта",
            subtitle = if (state.editing) {
                "Редактор карты · только инвентарь"
            } else {
                content?.let { "${it.map.devices.count { d -> d.type != DeviceType.SOCKET && d.type != DeviceType.PATCH_PANEL }} устройств" }
            },
            editing = state.editing,
            onToggleEdit = if (content != null) ({ viewModel.setEditing(!state.editing) }) else null,
            isRefreshing = (state.content as? UiState.Content)?.isRefreshing == true,
            floors = content?.map?.floors.orEmpty(),
            currentFloorId = floorId,
            onFloor = {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                viewModel.selectFloor(it)
            },
            onManageFloors = { showFloors = true },
            layer = state.layer,
            onLayerChange = {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                viewModel.setLayer(it)
            },
            vlans = content?.map?.vlans.orEmpty().map { it.id to (it.name to Color(it.colorArgb)) },
            vlanFilter = state.vlanFilter,
            onVlanFilter = viewModel::setVlanFilter,
            onOpenSettings = onOpenSettings,
            devices = content?.map?.devices.orEmpty(),
            onSearchResult = { device ->
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                viewModel.select(device.id)
                // Let the search results collapse first, so the visible area is measured without them.
                scope.launch {
                    repeat(2) { withFrameNanos { } }
                    camera.focusOn(device.x, device.y)
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { topPanelHeight = it.height },
        )

        val selected = state.selectedId?.let { content?.devicesById?.get(it) }
        // Fit button steps aside while a device card occupies the bottom.
        AnimatedVisibility(
            visible = content != null && selected == null && !state.editing,
            enter = fadeIn(Motion.smooth()),
            exit = fadeOut(Motion.smooth()),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(contentPadding)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                if (content?.map?.plan?.zones?.any { it.isRoom } == true) {
                    SmallFloatingActionButton(
                        onClick = { showRooms = true },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Icon(Icons.Outlined.MeetingRoom, contentDescription = "Помещения и устройства в них")
                    }
                }
                SmallFloatingActionButton(
                    onClick = camera::fit,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "Показать весь план")
                }
            }
        }

        AnimatedVisibility(
            visible = content != null && selected == null && !state.editing,
            enter = fadeIn(Motion.smooth()),
            exit = fadeOut(Motion.smooth()),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(contentPadding)
                .padding(16.dp),
        ) {
            MapLegend(
                devices = content?.map?.devices.orEmpty(),
                open = showLegend,
                onToggle = { showLegend = !showLegend },
                hidden = hiddenTypes,
                onToggleType = { t ->
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    hiddenTypes = if (t in hiddenTypes) hiddenTypes - t else hiddenTypes + t
                },
                onShowAll = { hiddenTypes = emptySet() },
            )
        }

        // The card rises from the bottom edge and leaves the same way.
        var lastSelected by remember { mutableStateOf<Device?>(null) }
        if (selected != null) lastSelected = selected
        AnimatedVisibility(
            visible = selected != null,
            enter = slideInVertically(Motion.smooth(Motion.RESPONSE_SHEET, IntOffset.VisibilityThreshold)) { it } + fadeIn(),
            exit = slideOutVertically(Motion.smooth(Motion.RESPONSE_SHEET, IntOffset.VisibilityThreshold)) { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(contentPadding)
                .padding(12.dp),
        ) {
            lastSelected?.let { device ->
                DeviceCard(
                    device = device,
                    vlanName = device.vlanId?.let { id -> content?.map?.vlans?.firstOrNull { it.id == id }?.let { "VLAN ${it.id} · ${it.name}" } },
                    tracePath = if (device.id == state.selectedId) state.tracePath else emptyList(),
                    onOpenSwitch = { onOpenSwitch(device.id) },
                    onBindNfc = { viewModel.bindNfc(device) },
                    onDismiss = { viewModel.select(null) },
                    hostName = device.hostId?.let { content?.allDevices?.get(it)?.name },
                    roomName = content?.map?.plan?.zones?.roomAt(device.x, device.y)?.name,
                    rackText = device.rackId?.let { id ->
                        val rack = content?.allDevices?.get(id) ?: state.rack?.devices?.firstOrNull { it.id == id }
                        "${rack?.name ?: "Стойка"} · U${device.rackU}${if ((device.rackH ?: 1) > 1) "–${device.rackTop}" else ""}"
                    },
                    onOpenRack = if (device.type == DeviceType.RACK) ({ viewModel.openRack(device.id) }) else null,
                    onEditComment = {
                        commentError = null
                        commentFor = device
                    },
                    peripheralText = if (device.type.takesPeripherals) {
                        peripheralSummary(allPeripherals.filter { it.deviceId == device.id }) ?: "Добавить периферию"
                    } else {
                        null
                    },
                    onOpenPeripherals = { peripheralsFor = device },
                )
            }
        }

        AnimatedVisibility(
            visible = state.editing && content != null,
            enter = slideInVertically(Motion.smooth(Motion.RESPONSE_SHEET, IntOffset.VisibilityThreshold)) { it } + fadeIn(),
            exit = slideOutVertically(Motion.smooth(Motion.RESPONSE_SHEET, IntOffset.VisibilityThreshold)) { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(contentPadding)
                .padding(12.dp)
                .onSizeChanged { toolbarHeight = it.height },
        ) {
            val plan = content?.map?.plan
            val editor = state.editor
            MapEditToolbar(
                state = editor,
                linkFromName = editor.linkFrom?.let { content?.devicesById?.get(it)?.name },
                selectedLinkName = editor.selectedLink?.let { id ->
                    val link = content?.map?.connections?.firstOrNull { it.id == id } ?: return@let null
                    val all = content?.allDevices.orEmpty()
                    "${all[link.deviceAId]?.name ?: "?"} — ${all[link.deviceBId]?.name ?: "?"}"
                },
                hasPlan = plan?.imageUrl != null,
                hasDrawings = plan?.drawings?.isNotEmpty() == true,
                selectedZoneName = editor.selectedZone?.let { id -> plan?.zones?.firstOrNull { it.id == id }?.name },
                onUndoZone = viewModel::undoZoneCorner,
                onFinishZone = {
                    zoneEdit = ZoneEdit(
                        Zone(
                            id = "z" + java.util.UUID.randomUUID().toString().take(8),
                            name = "",
                            isRoom = true,
                            colorArgb = ZONE_COLORS[(plan?.zones?.size ?: 0) % ZONE_COLORS.size],
                            points = editor.zoneDraft,
                        ),
                        isNew = true,
                    )
                },
                onCancelZone = viewModel::cancelZone,
                onEditZone = {
                    plan?.zones?.firstOrNull { it.id == editor.selectedZone }?.let { zoneEdit = ZoneEdit(it, isNew = false) }
                },
                onTool = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    viewModel.setTool(it)
                },
                onAddDevice = {
                    val at = camera.visibleCentreOnPlan()
                    viewModel.openNewDevice(
                        at.x.coerceIn(0f, plan?.widthPx ?: at.x),
                        at.y.coerceIn(0f, plan?.heightPx ?: at.y),
                    )
                },
                onAddRack = {
                    val at = camera.visibleCentreOnPlan()
                    viewModel.openNewDevice(
                        at.x.coerceIn(0f, plan?.widthPx ?: at.x),
                        at.y.coerceIn(0f, plan?.heightPx ?: at.y),
                        DeviceType.RACK,
                    )
                },
                onCable = viewModel::setCable,
                onCancelLink = viewModel::cancelLink,
                onDeletePoint = viewModel::deletePoint,
                onStraighten = viewModel::straighten,
                onDeleteLink = { linkToDelete = editor.selectedLink?.let { id -> content?.map?.connections?.firstOrNull { it.id == id } } },
                onDoneLink = { viewModel.selectLink(null) },
                selectedLinkColor = editor.selectedLink?.let { id -> content?.map?.connections?.firstOrNull { it.id == id }?.colorArgb },
                onCableColor = viewModel::recolorLink,
                onPenColor = viewModel::setPenColor,
                onUndo = viewModel::undoStroke,
                onClearDrawings = { confirmClear = true },
                onPickPlan = { pickPlan.launch(arrayOf("application/pdf", "image/*")) },
                onScalePlan = viewModel::scalePlan,
                onFitPlan = viewModel::fitPlan,
                onRemovePlan = { confirmRemovePlan = true },
                onDismissError = viewModel::dismissError,
            )
        }

        state.editor.form?.let { form ->
            DeviceFormDialog(
                form = form,
                busy = state.editor.busy,
                floors = content?.map?.floors.orEmpty(),
                onFloor = viewModel::setFormFloor,
                onSave = viewModel::saveDevice,
                onDelete = viewModel::deleteDevice,
                onDismiss = viewModel::closeForm,
                onOpenRack = { id ->
                    viewModel.closeForm()
                    viewModel.openRack(id)
                },
                hosts = content?.allDevices.orEmpty().values.filter { it.type == DeviceType.SERVER }.sortedBy { it.name },
            )
        }
        zoneEdit?.let { edit ->
            ZoneDialog(
                edit = edit,
                onSave = {
                    viewModel.saveZone(it)
                    zoneEdit = null
                },
                onDelete = {
                    viewModel.deleteZone(it)
                    zoneEdit = null
                },
                onDismiss = { zoneEdit = null },
            )
        }
        if (showRooms && content != null) {
            RoomsDialog(
                zones = content.map.plan.zones,
                devices = content.map.devices,
                onPick = { device ->
                    showRooms = false
                    viewModel.select(device.id)
                    scope.launch {
                        repeat(2) { withFrameNanos { } }
                        camera.focusOn(device.x, device.y)
                    }
                },
                onDismiss = { showRooms = false },
            )
        }
        peripheralsFor?.let { device ->
            DevicePeripheralsDialog(device, peripherals, allPeripherals, onDismiss = { peripheralsFor = null })
        }
        peripheralEditor?.let { e ->
            PeripheralEditorDialog(
                editor = e,
                devices = peripheralDevices,
                onChange = peripherals::updateDraft,
                onSave = peripherals::save,
                onDelete = peripherals::delete,
                onDismiss = peripherals::closeEditor,
            )
        }
        commentFor?.let { device ->
            CommentDialog(
                device = device,
                busy = state.editor.busy,
                error = commentError,
                onSave = { text ->
                    commentError = null
                    viewModel.saveComment(device, text) { error ->
                        if (error == null) commentFor = null else commentError = error
                    }
                },
                onDismiss = { commentFor = null },
            )
        }
        // The panel drawings follow the devices mounted in the open rack.
        val rackMembers = state.rack?.let { r -> r.devices.filter { it.rackId == r.rackId } }
        LaunchedEffect(rackMembers) {
            if (rackMembers == null) faceplates.closeRack() else faceplates.showRack(rackMembers)
        }
        state.rack?.let { rackState ->
            val rack = rackState.devices.firstOrNull { it.id == rackState.rackId } ?: content?.allDevices?.get(rackState.rackId)
            if (rack != null) {
                RackDialog(
                    plates = rackPlates,
                    onEditFaceplate = { d -> faceplates.openEditor(d, rackState.devices) },
                    rack = rack,
                    state = rackState,
                    onMount = { id, u, h -> viewModel.mount(id, rack.id, u, h) },
                    onRemove = { id -> viewModel.mount(id, null, null, null) },
                    onOpenSwitch = { id ->
                        viewModel.closeRack()
                        onOpenSwitch(id)
                    },
                    onDismiss = viewModel::closeRack,
                )
            }
        }
        // Over the rack view it was opened from.
        faceplateEditor?.let { e -> FaceplateEditorDialog(e, faceplates) }
        linkToDelete?.let { link ->
            val names = content?.allDevices.orEmpty().let {
                (it[link.deviceAId]?.name ?: "?") to (it[link.deviceBId]?.name ?: "?")
            }
            DeleteLinkDialog(
                link = link,
                names = names,
                onConfirm = {
                    viewModel.deleteLink(link)
                    linkToDelete = null
                },
                onDismiss = { linkToDelete = null },
            )
        }
        if (showFloors && content != null) {
            FloorsDialog(
                floors = content.map.floors,
                currentId = floorId,
                busy = state.editor.busy,
                error = state.editor.error,
                onOpen = {
                    viewModel.selectFloor(it)
                    showFloors = false
                },
                onCreate = viewModel::createFloor,
                onRename = viewModel::renameFloor,
                onMove = viewModel::moveFloor,
                onDelete = viewModel::deleteFloor,
                onDismiss = {
                    viewModel.dismissError()
                    showFloors = false
                },
            )
        }
        if (confirmClear) {
            ConfirmDialog(
                title = "Стереть все линии?",
                text = "Пропадут все линии карандаша на этом этаже. Устройства и кабели останутся.",
                action = "Стереть",
                onConfirm = {
                    viewModel.clearDrawings()
                    confirmClear = false
                },
                onDismiss = { confirmClear = false },
            )
        }
        if (confirmRemovePlan) {
            ConfirmDialog(
                title = "Убрать план здания?",
                text = "Подложка пропадёт с карты. Устройства, кабели и линии останутся на своих местах.",
                action = "Убрать",
                onConfirm = {
                    viewModel.removePlan()
                    confirmRemovePlan = false
                },
                onDismiss = { confirmRemovePlan = false },
            )
        }
    }
}

/** Nothing to show yet: no devices, no plan and no drawings. */
private fun FloorMap.isBlank() =
    devices.isEmpty() && plan.imageUrl == null && plan.drawings.isEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapTopPanel(
    title: String,
    subtitle: String?,
    editing: Boolean,
    onToggleEdit: (() -> Unit)?,
    isRefreshing: Boolean,
    floors: List<FloorInfo>,
    currentFloorId: String?,
    onFloor: (String) -> Unit,
    onManageFloors: () -> Unit,
    layer: MapLayer,
    onLayerChange: (MapLayer) -> Unit,
    vlans: List<Pair<Int, Pair<String, Color>>>,
    vlanFilter: Int?,
    onVlanFilter: (Int?) -> Unit,
    onOpenSettings: () -> Unit,
    devices: List<Device>,
    onSearchResult: (Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    // A scroll-edge fade instead of a hard bar: the plan stays visible right up to the status bar.
    Column(
        modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Ink950.copy(alpha = 0.94f),
                    0.75f to Ink950.copy(alpha = 0.80f),
                    1f to Color.Transparent,
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
    ) {
        AnimatedContent(
            targetState = searching,
            transitionSpec = { fadeIn(Motion.smooth()) togetherWith fadeOut(Motion.smooth()) },
            label = "mapHeader",
        ) { isSearching ->
            if (isSearching) {
                MapSearch(
                    devices = devices,
                    onPick = {
                        searching = false
                        onSearchResult(it)
                    },
                    onClose = { searching = false },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var floorMenu by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        Column(Modifier.clickable(onClickLabel = "Выбрать этаж") { floorMenu = true }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                            }
                            if (subtitle != null) {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        DropdownMenu(expanded = floorMenu, onDismissRequest = { floorMenu = false }) {
                            floors.reversed().forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(f.title) },
                                    onClick = {
                                        floorMenu = false
                                        onFloor(f.id)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Layers,
                                            contentDescription = null,
                                            tint = if (f.id == currentFloorId) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                        )
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Настроить этажи…") },
                                onClick = {
                                    floorMenu = false
                                    onManageFloors()
                                },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            )
                        }
                    }
                    if (editing) {
                        TextButton(onClick = { onToggleEdit?.invoke() }) { Text("Готово") }
                    } else {
                        if (onToggleEdit != null) {
                            IconButton(onClick = onToggleEdit) {
                                Icon(Icons.Outlined.EditLocationAlt, contentDescription = "Редактировать карту")
                            }
                        }
                        IconButton(onClick = { searching = true }, enabled = devices.isNotEmpty()) {
                            Icon(Icons.Outlined.Search, contentDescription = "Поиск на карте")
                        }
                    }
                    AlertsBell()
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(end = 12.dp).height(2.dp)) {
            if (isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        // More than one floor: they sit right above the layers, one tap away, bottom floor on the left.
        if (floors.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 12.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                items(floors, key = { it.id }) { f ->
                    FilterChip(
                        selected = f.id == currentFloorId,
                        onClick = { onFloor(f.id) },
                        label = { Text(f.title, maxLines = 1) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(end = 12.dp)) {
            MapLayer.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = item == layer,
                    onClick = { onLayerChange(item) },
                    shape = SegmentedButtonDefaults.itemShape(index, MapLayer.entries.size),
                    icon = {},
                    label = { Text("${item.short} ${item.title}", maxLines = 1) },
                )
            }
        }
        AnimatedVisibility(
            visible = layer != MapLayer.L1 && vlans.isNotEmpty(),
            enter = expandVertically(Motion.smooth(Motion.RESPONSE_SHEET)) + fadeIn(),
            exit = shrinkVertically(Motion.smooth(Motion.RESPONSE_SHEET)) + fadeOut(),
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 12.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                item {
                    FilterChip(selected = vlanFilter == null, onClick = { onVlanFilter(null) }, label = { Text("Все VLAN") })
                }
                items(vlans, key = { it.first }) { (id, info) ->
                    val (name, color) = info
                    FilterChip(
                        selected = vlanFilter == id,
                        onClick = { onVlanFilter(if (vlanFilter == id) null else id) },
                        label = { Text("$id · $name") },
                        leadingIcon = { StatusDot(color, size = 10.dp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = color.copy(alpha = 0.22f),
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }
    }
}

/** Find a device by name, IP, MAC or socket number and fly the map to it. */
@Composable
private fun MapSearch(
    devices: List<Device>,
    onPick: (Device) -> Unit,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val results = remember(query, devices) {
        if (query.isBlank()) emptyList() else devices.filter { it.matches(query) }.take(6)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.padding(end = 12.dp)) {
        TextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Имя, IP, MAC или розетка") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть поиск") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                results.firstOrNull()?.let {
                    keyboard?.hide()
                    onPick(it)
                }
            }),
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        if (query.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Column {
                    if (results.isEmpty()) {
                        Text(
                            "Ничего не найдено",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    results.forEach { device ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    keyboard?.hide()
                                    onPick(device)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Icon(device.type.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(device.type.label, device.ip).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Selected device. Swipe down to dismiss: the card follows the finger 1:1,
 * and on release it either leaves (fast or far enough) or springs back with the finger's velocity.
 */
@Composable
private fun DeviceCard(
    device: Device,
    vlanName: String?,
    tracePath: List<String>,
    onOpenSwitch: () -> Unit,
    onBindNfc: () -> Unit,
    onDismiss: () -> Unit,
    hostName: String? = null,
    roomName: String? = null,
    rackText: String? = null,
    onOpenRack: (() -> Unit)? = null,
    onEditComment: () -> Unit = {},
    /** The device's peripherals in a line, or «add» when it has none; null for a device that takes none. */
    peripheralText: String? = null,
    onOpenPeripherals: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    var cardHeight by remember { mutableIntStateOf(1) }
    LaunchedEffect(device.id) { drag.snapTo(0f) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { cardHeight = it.height.coerceAtLeast(1) }
            .graphicsLayer { translationY = drag.value }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch {
                        val next = drag.value + delta
                        // Upward pulls resist; downward pulls track the finger.
                        drag.snapTo(if (next < 0f) Motion.rubberband(next, cardHeight.toFloat()) else next)
                    }
                },
                onDragStarted = { drag.stop() },
                onDragStopped = { velocity ->
                    val projected = drag.value + Motion.project(velocity)
                    if (projected > cardHeight * 0.5f) {
                        onDismiss()
                    } else {
                        drag.animateTo(0f, Motion.momentum(Motion.RESPONSE_SHEET), initialVelocity = velocity)
                    }
                },
            ),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 18.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(end = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Icon(device.type.icon, contentDescription = null, tint = device.type.tint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(device.type.label, device.vendor, device.model).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть") }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (device.type != DeviceType.SOCKET && device.type != DeviceType.PATCH_PANEL) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (device.isOnline) StatusColors.Online else StatusColors.Offline)
                        Spacer(Modifier.width(6.dp))
                        Text(if (device.isOnline) "В сети" else "Не отвечает", style = MaterialTheme.typography.labelLarge)
                    }
                }
                device.ip?.let { Text(it, style = MonoStyle) }
                vlanName?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            hostName?.let {
                Spacer(Modifier.height(10.dp))
                Text("Работает на хосте $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            roomName?.let {
                Spacer(Modifier.height(10.dp))
                Text("Помещение: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            rackText?.let {
                Spacer(Modifier.height(6.dp))
                Text("В стойке: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().clickable(onClick = onEditComment).padding(end = 12.dp)) {
                Icon(
                    Icons.Outlined.Edit, contentDescription = "Изменить комментарий",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    device.comment ?: "Добавить комментарий",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.comment == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            peripheralText?.let { text ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPeripherals).padding(end = 12.dp)) {
                    Icon(
                        Icons.Outlined.Keyboard, contentDescription = "Периферия",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (tracePath.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    tracePath.joinToString("  →  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            if (device.type == DeviceType.SWITCH) {
                Spacer(Modifier.height(14.dp))
                FilledTonalButton(onClick = onOpenSwitch, modifier = Modifier.padding(end = 12.dp).fillMaxWidth()) {
                    Text("Открыть панель портов")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            if (onOpenRack != null) {
                Spacer(Modifier.height(14.dp))
                FilledTonalButton(onClick = onOpenRack, modifier = Modifier.padding(end = 12.dp).fillMaxWidth()) {
                    Text("Открыть стойку")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            TextButton(onClick = onBindNfc, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Outlined.Nfc, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Привязать NFC-метку")
            }
        }
    }
}

@Composable
private fun EmptyMapView(
    onOpenScanner: () -> Unit,
    onOpenSettings: () -> Unit,
    onDraw: () -> Unit,
    contentPadding: PaddingValues,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.TravelExplore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("На сервере пока нет устройств", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Найдите коммутаторы и принтеры сканером сети: он только читает (ping и SNMP GET) и ничего не меняет. " +
                    "Или нарисуйте карту сами: загрузите план здания и расставьте объекты вручную.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenScanner) { Text("Сканер сети") }
            FilledTonalButton(onClick = onDraw, modifier = Modifier.padding(top = 8.dp)) { Text("Нарисовать вручную") }
            TextButton(onClick = onOpenSettings) { Text("Настройки") }
        }
    }
}
