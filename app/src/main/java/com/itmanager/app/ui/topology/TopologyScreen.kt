package com.itmanager.app.ui.topology

import android.widget.Toast
import androidx.compose.foundation.Canvas
import com.itmanager.app.ui.map.DeviceFormDialog
import com.itmanager.app.ui.map.DeviceForm
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import com.itmanager.app.data.model.SwitchPort
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.Topology
import com.itmanager.app.data.model.TopologyLayout
import com.itmanager.app.data.model.TopologyLink
import com.itmanager.app.data.model.TopologyLinkKind
import com.itmanager.app.data.model.TopologyNode
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.components.TabTopBar
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.tint
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.StatusColors

private val CELL_W = 112.dp
private val CELL_H = 92.dp
private val NODE_R = 19.dp

/** Where the drawing sits on screen: world pixels × [scale] + [offset]. */
private class Viewport {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun toWorld(p: Offset) = (p - offset) / scale
}

/**
 * The network as a tree: routers and firewalls at the top, switches under them, end devices under their
 * switch. Pinch and drag to move around; tap a device for its links.
 */
@Composable
fun TopologyScreen(
    contentPadding: PaddingValues,
    onOpenDevice: (String) -> Unit,
    onShowOnMap: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: TopologyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val topology = (state.topology as? UiState.Content)?.data
    val viewport = remember { Viewport() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchMiss by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // The link dialog: open with the device it starts from, or with none to pick both ends.
    var linking by remember { mutableStateOf<LinkStart?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf<Pair<String, TopologyLink>?>(null) }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val cellW = with(density) { CELL_W.toPx() }
    val cellH = with(density) { CELL_H.toPx() }

    fun centre(of: Offset) {
        viewport.offset = Offset(viewSize.width / 2f, viewSize.height / 3f) - of * viewport.scale
    }

    fun fit(layout: TopologyLayout) {
        if (viewSize.width == 0) return
        val margin = with(density) { 24.dp.toPx() }
        val w = layout.width * cellW
        val scale = ((viewSize.width - 2 * margin) / w.coerceAtLeast(1f)).coerceIn(0.25f, 1.1f)
        viewport.scale = scale
        val x = if (w * scale < viewSize.width) (viewSize.width - w * scale) / 2f else margin
        viewport.offset = Offset(x, margin)
    }

    // A new layout (first load, end devices on or off): show it all. A reload of the same keeps the view.
    val layout = state.layout
    LaunchedEffect(state.showEndDevices, layout != null, viewSize.width > 0) {
        layout?.let(::fit)
    }

    Column(Modifier.fillMaxSize()) {
        TabTopBar(
            title = "Схема сети",
            onBack = onBack,
            subtitle = topology?.let { t ->
                val down = t.nodes.count { !it.isOnline && it.type != DeviceType.VIRTUAL }
                "${t.nodes.size} устр. · ${t.links.count { it.kind == TopologyLinkKind.CABLE }} связей" + if (down > 0) " · не в сети: $down" else ""
            },
            actions = {
                Box {
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Outlined.Add, contentDescription = "Добавить") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Новое устройство") },
                            leadingIcon = { Icon(DeviceType.SWITCH.icon, contentDescription = null) },
                            onClick = {
                                addMenu = false
                                viewModel.openNew()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Новая связь (кабель)") },
                            leadingIcon = { Icon(Icons.Outlined.Cable, contentDescription = null) },
                            onClick = {
                                addMenu = false
                                linking = LinkStart(null)
                            },
                        )
                    }
                }
                IconButton(onClick = { layout?.let(::fit) }) { Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "Показать всё") }
                IconButton(onClick = viewModel::retry) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            TextField(
                value = query,
                onValueChange = {
                    query = it.take(60)
                    searchMiss = false
                },
                singleLine = true,
                isError = searchMiss,
                placeholder = { Text("Найти: имя или IP") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = search@{
                    val l = layout ?: return@search
                    val q = query.trim()
                    val hit = l.nodes.firstOrNull { it.name.equals(q, true) || it.ip == q }
                        ?: l.nodes.firstOrNull { it.name.contains(q, true) || it.ip?.contains(q) == true }
                    searchMiss = hit == null
                    hit?.let { n ->
                        viewModel.select(n.id)
                        l.places[n.id]?.let { p -> centre(Offset(p.x * cellW, p.y * cellH + cellH * 0.3f)) }
                    }
                }),
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = state.showEndDevices,
                onClick = { viewModel.setShowEndDevices(!state.showEndDevices) },
                label = { Text("Все устройства") },
            )
        }
        when (val t = state.topology) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(t.message, viewModel::retry, onOpenSettings, contentPadding = contentPadding)
            is UiState.Content -> Box(Modifier.fillMaxSize()) {
                if (t.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                val l = layout
                if (l == null || l.nodes.isEmpty()) {
                    Text(
                        if (t.data.nodes.isEmpty()) {
                            "В учёте пока нет активного оборудования. Добавьте устройство кнопкой «+» вверху, на карте или сканером сети."
                        } else {
                            "Коммутаторов, маршрутизаторов и межсетевых экранов нет. Включите «Все устройства»."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                } else {
                    TopologyCanvas(
                        layout = l,
                        selectedId = state.selectedId,
                        viewport = viewport,
                        cellW = cellW,
                        cellH = cellH,
                        onTap = viewModel::select,
                        modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it },
                    )
                }
                val selected = state.selectedId?.let { id -> t.data.nodes.firstOrNull { it.id == id } }
                selected?.let { node ->
                    NodeCard(
                        node = node,
                        topology = t.data,
                        onClose = { viewModel.select(null) },
                        onOpenSwitch = { onOpenDevice(node.id) },
                        onShowOnMap = {
                            viewModel.showOnMap(node.id)
                            onShowOnMap()
                        },
                        onEdit = { viewModel.openEdit(node.id) },
                        onConnect = { linking = LinkStart(node) },
                        onDisconnect = { link -> disconnect = node.id to link },
                        onPick = { id ->
                            viewModel.select(id)
                            l?.places?.get(id)?.let { p -> centre(Offset(p.x * cellW, p.y * cellH + cellH * 0.3f)) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(contentPadding)
                            .padding(12.dp),
                    )
                }
            }
        }
    }

    linking?.let { start ->
        LinkDialog(
            start = start.from,
            nodes = topology?.nodes.orEmpty(),
            ports = state.ports,
            onLoadPorts = viewModel::loadPorts,
            onConnect = { a, b, cable, portA, portB ->
                linking = null
                viewModel.connect(a, b, cable, portA, portB)
            },
            onDismiss = { linking = null },
        )
    }
    disconnect?.let { (selfId, link) ->
        val names = topology?.nodes.orEmpty().associate { it.id to it.name }
        AlertDialog(
            onDismissRequest = { disconnect = null },
            title = { Text("Отключить ${names[selfId] ?: ""}?") },
            text = {
                Text(
                    "Из учёта удалится кабель у порта ${link.portOf(selfId) ?: "?"} (${names[selfId]} → ${names[link.other(selfId)]}). " +
                        "Розетки и патч-панели на пути остаются. Само оборудование это не затрагивает.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    disconnect = null
                    viewModel.disconnect(link, selfId)
                }) { Text("Отключить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { disconnect = null }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
    state.form?.let { f ->
        DeviceFormDialog(
            form = DeviceForm(f.device, f.device?.x ?: 0f, f.device?.y ?: 0f, f.device?.floorId, error = f.error),
            busy = state.busy,
            floors = emptyList(),
            onFloor = {},
            onSave = viewModel::saveDevice,
            onDelete = viewModel::deleteDevice,
            onDismiss = viewModel::closeEdit,
            hosts = f.hosts,
        )
    }
}

@Composable
private fun TopologyCanvas(
    layout: TopologyLayout,
    selectedId: String?,
    viewport: Viewport,
    cellW: Float,
    cellH: Float,
    onTap: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val radius = with(density) { NODE_R.toPx() }
    val iconSize = with(density) { 20.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val painters = DeviceType.entries.associateWith { rememberVectorPainter(it.icon) }
    val nameStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
    val ipStyle = MonoStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
    val nodeFill = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.primary
    val dimLink = MaterialTheme.colorScheme.outlineVariant
    val virtualLink = DeviceType.VIRTUAL.tint.copy(alpha = 0.6f)
    val dash = remember(density) { with(density) { PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) } }
    val thin = with(density) { 1.dp.toPx() }
    val thick = with(density) { 2.5.dp.toPx() }
    val infra = setOf(DeviceType.SWITCH, DeviceType.ROUTER, DeviceType.FIREWALL)
    val byId = remember(layout) { layout.nodes.associateBy { it.id } }

    fun centreOf(id: String): Offset? = layout.places[id]?.let { Offset(it.x * cellW, it.y * cellH + radius + cellH * 0.08f) }

    Canvas(
        modifier
            .pointerInput(layout) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val old = viewport.scale
                    val scale = (old * zoom).coerceIn(0.15f, 3f)
                    // Zoom around the fingers, then follow them.
                    viewport.offset = centroid - (centroid - viewport.offset) * (scale / old) + pan
                    viewport.scale = scale
                }
            }
            .pointerInput(layout) {
                detectTapGestures { tap ->
                    val w = viewport.toWorld(tap)
                    val hit = layout.nodes.minByOrNull { n -> centreOf(n.id)?.let { (it - w).getDistanceSquared() } ?: Float.MAX_VALUE }
                    val c = hit?.let { centreOf(it.id) }
                    onTap(if (c != null && (c - w).getDistance() <= radius * 1.8f) hit.id else null)
                }
            },
    ) {
        val s = viewport.scale
        translate(viewport.offset.x, viewport.offset.y) {
            scale(s, s, pivot = Offset.Zero) {
                // Links under the nodes; the selected device's links on top of the rest.
                val ordered = layout.links.sortedBy { selectedId != null && (it.aId == selectedId || it.bId == selectedId) }
                for (link in ordered) {
                    val a = centreOf(link.aId) ?: continue
                    val b = centreOf(link.bId) ?: continue
                    val mine = selectedId != null && (link.aId == selectedId || link.bId == selectedId)
                    val bothInfra = byId[link.aId]?.type in infra && byId[link.bId]?.type in infra
                    val color = when {
                        mine -> highlight
                        link.kind == TopologyLinkKind.VIRTUAL -> virtualLink
                        link.isUp == true -> StatusColors.Online.copy(alpha = if (bothInfra) 0.85f else 0.45f)
                        link.isUp == false -> dimLink
                        else -> dimLink.copy(alpha = 0.7f)
                    }
                    drawLine(
                        color, a, b,
                        strokeWidth = if (mine || bothInfra) thick else thin,
                        pathEffect = if (link.kind == TopologyLinkKind.VIRTUAL || link.isUp == false) dash else null,
                    )
                }
                for (node in layout.nodes) {
                    val c = centreOf(node.id) ?: continue
                    val selected = node.id == selectedId
                    drawCircle(nodeFill, radius, c)
                    drawCircle(
                        if (selected) highlight else node.type.tint.copy(alpha = 0.8f),
                        radius,
                        c,
                        style = Stroke(if (selected) thick * 1.4f else thin * 1.5f),
                    )
                    painters[node.type]?.let { painter ->
                        translate(c.x - iconSize / 2f, c.y - iconSize / 2f) {
                            with(painter) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(node.type.tint)) }
                        }
                    }
                    // Status dot on the rim, except for VMs which have no ping of their own.
                    if (node.type != DeviceType.VIRTUAL) {
                        drawCircle(
                            if (node.isOnline) StatusColors.Online else StatusColors.Offline,
                            radius * 0.24f,
                            Offset(c.x + radius * 0.72f, c.y - radius * 0.72f),
                        )
                    }
                    // Labels only when they can be read.
                    if (s >= 0.45f) {
                        val maxW = (cellW - 6f).toInt().coerceAtLeast(1)
                        val name = measurer.measure(
                            node.name, nameStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
                            constraints = Constraints(maxWidth = maxW),
                        )
                        drawText(name, topLeft = Offset(c.x - name.size.width / 2f, c.y + radius + 3f))
                        node.ip?.let { ip ->
                            val t = measurer.measure(ip, ipStyle, maxLines = 1, constraints = Constraints(maxWidth = maxW))
                            drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y + radius + 3f + name.size.height))
                        }
                    }
                }
            }
        }
    }
}

/** The selected device: what it is and every link it has, each opening the device at the other end. */
@Composable
private fun NodeCard(
    node: TopologyNode,
    topology: Topology,
    onClose: () -> Unit,
    onOpenSwitch: () -> Unit,
    onShowOnMap: () -> Unit,
    onPick: (String) -> Unit,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: (TopologyLink) -> Unit,
    modifier: Modifier = Modifier,
) {
    val names = remember(topology) { topology.nodes.associate { it.id to it.name } }
    val links = topology.links.filter { it.aId == node.id || it.bId == node.id }
        .sortedWith(compareBy({ it.kind }, { it.portOf(node.id) }))
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(node.type.icon, contentDescription = null, tint = node.type.tint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(node.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(node.type.label, node.vendor, node.model).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Изменить устройство") }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (node.type != DeviceType.VIRTUAL) {
                    StatusDot(if (node.isOnline) StatusColors.Online else StatusColors.Offline)
                    Text(if (node.isOnline) "В сети" else "Не отвечает", style = MaterialTheme.typography.labelLarge)
                }
                node.ip?.let { Text(it, style = MonoStyle) }
            }
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp, end = 12.dp)) {
                if (links.isEmpty()) {
                    Text("Кабелем ни с чем не соединено", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                links.forEach { link -> LinkLine(node.id, link, names, onPick, onDisconnect) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp, end = 12.dp)) {
                FilledTonalButton(onClick = onOpenSwitch) {
                    Text("Карточка")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                if (node.type != DeviceType.VIRTUAL) {
                    TextButton(onClick = onConnect) { Text("Соединить…") }
                    TextButton(onClick = onShowOnMap) { Text("На карте") }
                }
            }
        }
    }
}

@Composable
private fun LinkLine(
    selfId: String,
    link: TopologyLink,
    names: Map<String, String>,
    onPick: (String) -> Unit,
    onDisconnect: (TopologyLink) -> Unit,
) {
    val other = link.other(selfId)
    val text = buildString {
        if (link.kind == TopologyLinkKind.VIRTUAL) {
            append(if (selfId == link.aId) "ВМ " else "Хост ")
            append(names[other] ?: "?")
            return@buildString
        }
        append(link.portOf(selfId) ?: "?")
        append(" → ")
        append(names[other] ?: "?")
        link.portOf(other)?.let { append(" $it") }
        if (link.via.isNotEmpty()) append(" через ${link.via.joinToString(", ")}")
        link.speedMbps?.takeIf { link.isUp == true }?.let { append(" · ${if (it >= 1000) "${it / 1000} Гбит/с" else "$it Мбит/с"}") }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(vertical = 3.dp)
            .pointerInput(other) { detectTapGestures { onPick(other) } },
    ) {
        StatusDot(
            when (link.isUp) {
                true -> StatusColors.Online
                false -> StatusColors.LinkDown
                null -> MaterialTheme.colorScheme.outline
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (link.kind == TopologyLinkKind.CABLE && link.cableAt(selfId) != null) {
            IconButton(onClick = { onDisconnect(link) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.LinkOff, contentDescription = "Отключить", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Where the link dialog starts: from a picked device, or with nothing picked yet. */
private class LinkStart(val from: TopologyNode?)

private val NETWORK_GEAR = setOf(DeviceType.SWITCH, DeviceType.ROUTER, DeviceType.FIREWALL)

/**
 * A new cable in three steps: where from, where to, and into which ports (a free one is taken when none is picked).
 * The inventory changes; nothing is sent to the equipment.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkDialog(
    start: TopologyNode?,
    nodes: List<TopologyNode>,
    ports: Map<String, List<SwitchPort>>,
    onLoadPorts: (String) -> Unit,
    onConnect: (a: String, b: String, cable: String, portA: String?, portB: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var a by remember { mutableStateOf(start) }
    var b by remember { mutableStateOf<TopologyNode?>(null) }
    var portA by remember { mutableStateOf<String?>(null) }
    var portB by remember { mutableStateOf<String?>(null) }
    var cable by remember { mutableStateOf("Cat6") }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(a?.id) { a?.let { onLoadPorts(it.id) } }
    LaunchedEffect(b?.id) { b?.let { onLoadPorts(it.id) } }
    val picking = if (a == null) "Откуда идёт кабель" else if (b == null) "Куда: соединить ${a?.name} с…" else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(picking ?: "${a?.name} — ${b?.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (picking != null) {
                    val shown = nodes.filter { it.id != a?.id && it.type != DeviceType.VIRTUAL }
                        .filter { query.isBlank() || it.name.contains(query, true) || it.ip?.contains(query.trim()) == true }
                        .sortedWith(compareBy({ it.type !in NETWORK_GEAR }, { it.name }))
                        .take(200)
                    TextField(
                        value = query,
                        onValueChange = { query = it.take(60) },
                        singleLine = true,
                        placeholder = { Text("Имя или IP") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(shown, key = { it.id }) { n ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        query = ""
                                        if (a == null) a = n else b = n
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Icon(n.type.icon, contentDescription = null, tint = n.type.tint, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(n.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(listOfNotNull(n.type.label, n.ip).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = cable == "Cat6", onClick = { cable = "Cat6" }, label = { Text("Медь Cat6") })
                            FilterChip(selected = cable == "OM3", onClick = { cable = "OM3" }, label = { Text("Оптика OM3") })
                        }
                        PortPicker(a!!.name, ports[a!!.id], portA) { portA = it }
                        PortPicker(b!!.name, ports[b!!.id], portB) { portB = it }
                        Text(
                            "Кабель запишется в учёт и появится на карте и в сети. На оборудование ничего не отправляется.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            val from = a
            val to = b
            if (from != null && to != null) {
                TextButton(onClick = { onConnect(from.id, to.id, cable, portA, portB) }) { Text("Соединить") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                when {
                    b != null -> { b = null; portB = null }
                    a != null && start == null -> { a = null; portA = null }
                    else -> onDismiss()
                }
            }) { Text(if (b != null || (a != null && start == null)) "Назад" else "Отмена") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** One end's port: «свободный» lets the server choose; taken ports show what is in them and cannot be picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortPicker(deviceName: String, ports: List<SwitchPort>?, picked: String?, onPick: (String?) -> Unit) {
    Text("Порт $deviceName", style = MaterialTheme.typography.labelLarge)
    when {
        ports == null -> Text("Загружаю порты…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ports.isEmpty() -> Text("Портов в учёте нет: сервер добавит порт сам.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FilterChip(selected = picked == null, onClick = { onPick(null) }, label = { Text("Любой свободный") })
            ports.forEach { p ->
                val taken = p.connectedDeviceName != null
                FilterChip(
                    selected = picked == p.id,
                    enabled = !taken,
                    onClick = { onPick(p.id) },
                    label = { Text(if (taken) "${p.name} · ${p.connectedDeviceName}" else p.name, maxLines = 1) },
                )
            }
        }
    }
}
