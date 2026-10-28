package com.itmanager.app.ui.device

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.data.model.takesPeripherals
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.TabTopBar
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.tint
import com.itmanager.app.ui.map.CommentDialog
import com.itmanager.app.ui.map.DeviceFormDialog
import com.itmanager.app.ui.peripherals.DevicePeripheralsContent
import com.itmanager.app.ui.peripherals.PeripheralEditorDialog
import com.itmanager.app.ui.peripherals.PeripheralsViewModel
import com.itmanager.app.ui.printers.PrinterCard
import com.itmanager.app.ui.switchdetail.color
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.StatusColors

/** The parts of the card; a tab shows only for devices it means something for. */
private enum class DeviceTab(val title: String) {
    OVERVIEW("Обзор"),
    PORTS("Порты"),
    PERIPHERALS("Периферия"),
    CONFIG("Конфигурация"),
}

private fun tabsFor(type: DeviceType): List<DeviceTab> = buildList {
    add(DeviceTab.OVERVIEW)
    if (type.hasPorts) add(DeviceTab.PORTS)
    if (type.takesPeripherals && type != DeviceType.VIRTUAL) add(DeviceTab.PERIPHERALS)
    if (type == DeviceType.SWITCH) add(DeviceTab.CONFIG)
}

/**
 * One card for a device, the same from the map, the list, the topology and the events: what it is, where it
 * stands, what it is cabled to, and everything that can be done with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onOpenPorts: (String) -> Unit,
    onShowOnMap: () -> Unit,
    onOpenHistory: (String, String) -> Unit,
    onOpenDiff: (String, String) -> Unit,
    onOpenImport: (String, String) -> Unit,
    onOpenTool: (String, String) -> Unit,
    onOpenVlans: () -> Unit,
    viewModel: DeviceViewModel = hiltViewModel(),
    peripherals: PeripheralsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val polling by viewModel.pollingPrinter.collectAsStateWithLifecycle()
    val peripheralState by peripherals.state.collectAsStateWithLifecycle()
    val peripheralEditor by peripherals.editor.collectAsStateWithLifecycle()
    val peripheralDevices by peripherals.devices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(peripherals) {
        peripherals.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(viewModel) { viewModel.deleted.collect { onBack() } }

    var tab by rememberSaveable { mutableStateOf(DeviceTab.OVERVIEW) }
    var editComment by remember { mutableStateOf(false) }
    var commentError by remember { mutableStateOf<String?>(null) }
    var pickVlan by remember { mutableStateOf(false) }

    val details = (state as? UiState.Content)?.data
    val device = details?.device

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TabTopBar(
                title = device?.name ?: "Устройство",
                subtitle = device?.let { d -> listOfNotNull(d.type.label, d.vendor, d.model).distinct().joinToString(" · ") },
                onBack = onBack,
            ) {
                if (device != null) {
                    IconButton(onClick = viewModel::openEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Изменить") }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, viewModel::refresh, onOpenSettings)
                is UiState.Content -> {
                    val d = s.data
                    val tabs = tabsFor(d.device.type)
                    val current = tab.takeIf { it in tabs } ?: DeviceTab.OVERVIEW
                    Column(Modifier.fillMaxSize()) {
                        StatusHeader(
                            device = d.device,
                            onShowOnMap = {
                                viewModel.showOnMap()
                                onShowOnMap()
                            }.takeIf { d.device.floorId != null && d.device.type != DeviceType.VIRTUAL },
                            onPing = d.device.ip?.let { ip -> { onOpenTool("ping", ip) } },
                            onNfc = viewModel::bindNfc,
                        )
                        if (tabs.size > 1) {
                            PrimaryScrollableTabRow(
                                selectedTabIndex = tabs.indexOf(current),
                                edgePadding = 16.dp,
                                containerColor = MaterialTheme.colorScheme.background,
                            ) {
                                tabs.forEach { t ->
                                    Tab(selected = t == current, onClick = { tab = t }, text = { Text(t.title) })
                                }
                            }
                        }
                        when (current) {
                            DeviceTab.OVERVIEW -> OverviewTab(
                                details = d,
                                pollingPrinter = polling,
                                onPollPrinter = viewModel::pollPrinter,
                                onOpenDevice = onOpenDevice,
                                onEditComment = {
                                    commentError = null
                                    editComment = true
                                },
                                onPickVlan = { pickVlan = true },
                                onRefresh = viewModel::refresh,
                            )
                            DeviceTab.PORTS -> PortsTab(d.device, d.ports, d.vlans, onOpenPorts = { onOpenPorts(d.device.id) })
                            DeviceTab.PERIPHERALS -> {
                                val all = (peripheralState as? UiState.Content)?.data.orEmpty()
                                DevicePeripheralsContent(d.device, peripherals, all, Modifier.weight(1f))
                            }
                            DeviceTab.CONFIG -> ConfigTab(
                                onHistory = { onOpenHistory(d.device.id, d.device.name) },
                                onDiff = { onOpenDiff(d.device.id, d.device.name) },
                                onImport = { onOpenImport(d.device.id, d.device.name) },
                                onPorts = { onOpenPorts(d.device.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    form?.let { f ->
        DeviceFormDialog(
            form = f,
            busy = busy,
            floors = details?.floors.orEmpty(),
            onFloor = viewModel::setFormFloor,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
            onDismiss = viewModel::closeEdit,
            hosts = details?.servers.orEmpty(),
            onOpenRack = { id -> viewModel.closeEdit(); onOpenDevice(id) },
        )
    }
    if (editComment && device != null) {
        CommentDialog(
            device = device,
            busy = busy,
            error = commentError,
            onSave = { text ->
                viewModel.saveComment(text) { error ->
                    commentError = error
                    if (error == null) editComment = false
                }
            },
            onDismiss = { editComment = false },
        )
    }
    if (pickVlan && details != null) {
        VlanPickerDialog(
            vlans = details.vlans,
            current = details.device.vlanId,
            onPick = { id ->
                pickVlan = false
                viewModel.setPortVlan(id)
            },
            onManage = {
                pickVlan = false
                onOpenVlans()
            },
            onDismiss = { pickVlan = false },
        )
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
}

// --- Header ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusHeader(device: Device, onShowOnMap: (() -> Unit)?, onPing: (() -> Unit)?, onNfc: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(device.type.tint.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(device.type.icon, contentDescription = null, tint = device.type.tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val passive = device.type == DeviceType.SOCKET || device.type == DeviceType.PATCH_PANEL || device.type == DeviceType.RACK
                    if (!passive) {
                        Box(Modifier.size(8.dp).background(if (device.isOnline) StatusColors.Online else StatusColors.Offline, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (device.isOnline) "В сети" else "Не отвечает",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (device.isOnline) StatusColors.Online else StatusColors.Offline,
                        )
                    } else {
                        Text("Пассивное оборудование", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val address = listOfNotNull(device.ip, device.mac).joinToString("  ·  ")
                if (address.isNotEmpty()) Text(address, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onShowOnMap != null) ActionChip(Icons.Outlined.Map, "На карте", onShowOnMap)
            if (onPing != null) ActionChip(Icons.Outlined.NetworkPing, "Ping", onPing)
            ActionChip(Icons.Outlined.Nfc, "NFC-метка", onNfc)
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

// --- Overview ---

@Composable
private fun OverviewTab(
    details: DeviceDetails,
    pollingPrinter: Boolean,
    onPollPrinter: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onEditComment: () -> Unit,
    onPickVlan: () -> Unit,
    onRefresh: () -> Unit,
) {
    val device = details.device
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (details.alerts.isNotEmpty()) {
            Section("Открытые события") {
                details.alerts.forEach { alert ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Box(Modifier.size(8.dp).background(StatusColors.Warning, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(alert.typeLabel, style = MaterialTheme.typography.bodyMedium)
                            Text(alert.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        details.printer?.let { PrinterCard(it, isPolling = pollingPrinter, onPoll = onPollPrinter) }

        Section("Расположение") {
            if (device.type == DeviceType.VIRTUAL) {
                InfoRow("Хост", details.host?.name ?: "не указан", onClick = details.host?.let { h -> { onOpenDevice(h.id) } })
            } else {
                InfoRow("Этаж", details.floor?.title ?: if (device.floorId == null) "не на карте" else "—")
                details.room?.let { InfoRow("Помещение", it) }
                details.rack?.let { rack ->
                    val units = device.rackU?.let { u -> if ((device.rackH ?: 1) > 1) "U$u–U${device.rackTop}" else "U$u" }
                    InfoRow("Стойка", listOfNotNull(rack.name, units).joinToString(" · "), onClick = { onOpenDevice(rack.id) })
                }
            }
        }

        if (device.type != DeviceType.VIRTUAL && device.type != DeviceType.RACK && device.type != DeviceType.SWITCH) {
            Section("Подключение") {
                val uplink = details.uplink
                if (uplink == null) {
                    Text(
                        "Кабель до коммутатора не проложен. Соедините устройство на карте (слой L1) или в разделе «Сеть».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    InfoRow(
                        "Коммутатор",
                        listOfNotNull(uplink.name, device.switchPortName?.let { "порт $it" }).joinToString(" · "),
                        onClick = { onOpenDevice(uplink.id) },
                    )
                    val vlan = details.vlan
                    InfoRow(
                        "VLAN",
                        vlan?.let { "${it.id} · ${it.name}" } ?: device.vlanId?.toString() ?: "не задан",
                        dot = vlan?.let { Color(it.colorArgb) },
                        onClick = onPickVlan,
                        action = "Сменить",
                    )
                    vlan?.subnet?.takeIf { it.isNotBlank() }?.let { InfoRow("Подсеть", it, mono = true) }
                }
            }
        }

        Section("Комментарий", action = "Изменить", onAction = onEditComment) {
            Text(
                device.comment?.takeIf { it.isNotBlank() } ?: "Пусто. Запишите, за кем закреплено, пароль от сейфа, особенности.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (device.comment.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }

        if (details.rackMembers.isNotEmpty()) {
            Section("В стойке") {
                details.rackMembers.forEach { member ->
                    DeviceLink(member, prefix = member.rackU?.let { "U$it" }) { onOpenDevice(member.id) }
                }
            }
        }
        if (details.guests.isNotEmpty()) {
            Section("Виртуальные машины") {
                details.guests.forEach { guest -> DeviceLink(guest) { onOpenDevice(guest.id) } }
            }
        }

        TextButton(onClick = onRefresh, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Обновить")
        }
    }
}

@Composable
private fun Section(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(36.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
        Column(Modifier.padding(end = 8.dp)) { content() }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    mono: Boolean = false,
    dot: Color? = null,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        if (dot != null) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            value,
            style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else if (onClick != null) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceLink(device: Device, prefix: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        if (prefix != null) {
            Text(prefix, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
        }
        Icon(device.type.icon, contentDescription = null, tint = device.type.tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(device.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        device.ip?.let { Text(it, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Ports ---

@Composable
private fun PortsTab(device: Device, ports: List<SwitchPort>, vlans: List<Vlan>, onOpenPorts: () -> Unit) {
    val vlanById = vlans.associateBy { it.id }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        item {
            FilledTonalButton(onClick = onOpenPorts, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Lan, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (device.type == DeviceType.SWITCH) "Панель портов, трафик и диагностика" else "Открыть панель портов")
            }
        }
        if (ports.isEmpty()) {
            item {
                Text(
                    "Портов пока нет. Добавьте их на панели портов или импортом конфигурации.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(ports, key = { it.id }) { port -> PortRow(port, vlanById, onOpenPorts) }
    }
}

@Composable
private fun PortRow(port: SwitchPort, vlans: Map<Int, Vlan>, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(12.dp).background(port.status.color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                port.name + if (port.isUplink) " · uplink" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                port.connectedDeviceName ?: port.status.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val vlanText = when (port.mode) {
            PortMode.ACCESS -> port.accessVlan?.let { id -> vlans[id]?.let { "${it.id} ${it.name}" } ?: "VLAN $id" } ?: "—"
            PortMode.TRUNK -> "trunk " + port.taggedVlans.sorted().joinToString(",").ifEmpty { "все" }
        }
        val color = port.accessVlan?.takeIf { port.mode == PortMode.ACCESS }?.let { vlans[it] }?.let { Color(it.colorArgb) }
        if (color != null) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(vlanText, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Configuration ---

@Composable
private fun ConfigTab(onHistory: () -> Unit, onDiff: () -> Unit, onImport: () -> Unit, onPorts: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConfigAction(Icons.Outlined.History, "История", "Сохранённые копии конфигурации и изменения портов", onHistory)
        ConfigAction(Icons.Outlined.Difference, "Сравнить конфигурацию", "Сохранённая копия против running-config", onDiff)
        ConfigAction(Icons.Outlined.FileDownload, "Импорт конфигурации", "Порты и VLAN из текста конфига коммутатора", onImport)
        ConfigAction(Icons.Outlined.Lan, "Панель портов", "Режим, VLAN, включение портов на коммутаторе", onPorts)
    }
}

@Composable
private fun ConfigAction(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- VLAN picker ---

/** Puts the port the device is cabled to into another VLAN; the list is managed in «VLAN и подсети». */
@Composable
private fun VlanPickerDialog(
    vlans: List<Vlan>,
    current: Int?,
    onPick: (Int) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("VLAN порта") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (vlans.isEmpty()) {
                    Text("В справочнике нет VLAN.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vlans.forEach { vlan ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(vlan.id) }
                            .padding(vertical = 10.dp),
                    ) {
                        Box(Modifier.size(12.dp).background(Color(vlan.colorArgb), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${vlan.id} · ${vlan.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (vlan.id == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (vlan.subnet.isNotBlank()) Text(vlan.subnet, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onManage) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить или изменить VLAN…")
                }
                Text(
                    "Меняется запись в учёте; на сам коммутатор команда не отправляется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
