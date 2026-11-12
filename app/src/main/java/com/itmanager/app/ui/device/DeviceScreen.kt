package com.itmanager.app.ui.device

import androidx.compose.ui.res.stringResource
import com.itmanager.app.R
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.itmanager.app.data.model.PathHop
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
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.map.RackHost
import com.itmanager.app.ui.map.RackViewModel
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
    onOpenPc: (String) -> Unit,
    onOpenVlans: () -> Unit,
    /** The computer's page with remote help started at once. */
    onAssistPc: (String) -> Unit = {},
    /** The computer's page with RDP shadowing started: the sessions looked up, the mstsc command ready. */
    onShadowPc: (String) -> Unit = {},
    viewModel: DeviceViewModel = hiltViewModel(),
    peripherals: PeripheralsViewModel = hiltViewModel(),
    rackViewModel: RackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val polling by viewModel.pollingPrinter.collectAsStateWithLifecycle()
    val portPath by viewModel.path.collectAsStateWithLifecycle()
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
    var merging by remember { mutableStateOf(false) }
    val mergeAsk by viewModel.mergeAsk.collectAsStateWithLifecycle()

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
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            // The same device entered twice (each address found as its own host): fold the other one in.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.device_screen_01)) },
                                enabled = !busy,
                                onClick = {
                                    menu = false
                                    merging = true
                                },
                            )
                        }
                    }
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
                            onManagePc = { onOpenPc(d.device.name) }.takeIf { d.device.type in PC_TYPES },
                            onAssistPc = { onAssistPc(d.device.name) }.takeIf { d.device.type in PC_TYPES },
                            onShadowPc = { onShadowPc(d.device.name) }.takeIf { d.device.type in PC_TYPES },
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
                                onOpenRack = { rackViewModel.open(d.device.id) }.takeIf { d.device.type == DeviceType.RACK },
                            )
                            DeviceTab.PORTS -> PortsTab(
                                d.device,
                                d.ports,
                                d.vlans,
                                onOpenPorts = { onOpenPorts(d.device.id) },
                                onPort = viewModel::openPath,
                            )
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
            onOpenRack = { id ->
                viewModel.closeEdit()
                rackViewModel.open(id)
            },
        )
    }
    RackHost(onOpenDevice = onOpenDevice, rackViewModel = rackViewModel, fallback = { id -> device?.takeIf { it.id == id } })
    portPath?.let { p ->
        PortPathSheet(
            state = p,
            device = device,
            onOpenDevice = { id ->
                viewModel.closePath()
                if (id != device?.id) onOpenDevice(id)
            },
            onOpenPorts = device?.takeIf { it.type == DeviceType.SWITCH }?.let { d -> { viewModel.closePath(); onOpenPorts(d.id) } },
            onShowOnMap = { id ->
                viewModel.closePath()
                viewModel.showOnMap(id)
                onShowOnMap()
            },
            onDismiss = viewModel::closePath,
        )
    }
    if (merging && details != null) {
        MergeDialog(
            keep = details.device,
            candidates = details.others.filter { (it.type == DeviceType.RACK) == (details.device.type == DeviceType.RACK) },
            onPick = {
                merging = false
                viewModel.askMerge(it)
            },
            onDismiss = { merging = false },
        )
    }
    mergeAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = viewModel::cancelMerge,
            title = { Text("Объединить с ${device?.name}?") },
            text = {
                when {
                    ask.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    ask.error != null -> Text(ask.error, color = MaterialTheme.colorScheme.error)
                    ask.preview != null -> Text(ask.preview.summary() + " Оборудование это не затрагивает.")
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmMerge, enabled = ask.preview != null) {
                    Text(stringResource(R.string.device_screen_02), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelMerge) { Text(stringResource(R.string.form_sheet_02)) } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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

/** Windows computers the server can reach over WinRM (Connect-DomainPC.ps1's tools). */
private val PC_TYPES = setOf(DeviceType.WORKSTATION, DeviceType.SERVER, DeviceType.VIRTUAL)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusHeader(
    device: Device,
    onShowOnMap: (() -> Unit)?,
    onPing: (() -> Unit)?,
    onManagePc: (() -> Unit)?,
    onAssistPc: (() -> Unit)?,
    onShadowPc: (() -> Unit)? = null,
    onNfc: () -> Unit,
) {
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
                        Text(stringResource(R.string.device_screen_03), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val address = (device.allIps + listOfNotNull(device.mac)).joinToString("  ·  ")
                if (address.isNotEmpty()) Text(address, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The main action depends on what the device is: a computer is helped or managed (those chips come first and
            // are filled), anything else is looked at on the map or pinged. The rest follows in plain chips.
            val computer = onManagePc != null
            if (onAssistPc != null) ActionChip(Icons.Outlined.ScreenShare, "Удалённая помощь", onAssistPc, main = true)
            if (onManagePc != null) ActionChip(Icons.Outlined.Computer, "Управление ПК", onManagePc, main = true)
            if (onShowOnMap != null) ActionChip(Icons.Outlined.Map, "На карте", onShowOnMap, main = !computer)
            if (onPing != null) ActionChip(Icons.Outlined.NetworkPing, "Ping", onPing, main = !computer)
            if (onShadowPc != null) ActionChip(Icons.Outlined.DesktopWindows, "Подключиться напрямую", onShadowPc)
            ActionChip(Icons.Outlined.Nfc, "NFC-метка", onNfc)
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit, main: Boolean = false) {
    AssistChip(
        onClick = onClick,
        colors = if (main) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
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
    /** A rack: its front view with the mounted devices. */
    onOpenRack: (() -> Unit)? = null,
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
            device.systemName?.let { InfoRow("Система", it) }
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

        if (onOpenRack != null) {
            FilledTonalButton(onClick = onOpenRack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.device_screen_04))
            }
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
            Text(stringResource(R.string.device_screen_05))
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
private fun PortsTab(
    device: Device,
    ports: List<SwitchPort>,
    vlans: List<Vlan>,
    onOpenPorts: () -> Unit,
    /** A tap on a port: where its cable goes, both ways. */
    onPort: (SwitchPort) -> Unit,
) {
    val passive = device.type == DeviceType.PATCH_PANEL || device.type == DeviceType.SOCKET
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
                Text(
                    when (device.type) {
                        DeviceType.SWITCH -> "Панель портов, трафик и диагностика"
                        DeviceType.PATCH_PANEL, DeviceType.SOCKET -> "Добавить или изменить порты"
                        else -> "Открыть панель портов"
                    },
                )
            }
        }
        if (ports.isNotEmpty()) {
            item {
                Text(
                    "Коснитесь порта, чтобы увидеть, куда идёт его кабель: что подключено с каждой стороны.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        items(ports, key = { it.id }) { port -> PortRow(port, vlanById, passive) { onPort(port) } }
    }
}

@Composable
private fun PortRow(port: SwitchPort, vlans: Map<Int, Vlan>, passive: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // A patch panel has no link of its own: the mark says whether a cable is in it.
        val mark = if (passive) {
            if (port.connectedDeviceName != null) StatusColors.Online else MaterialTheme.colorScheme.outlineVariant
        } else {
            port.status.color
        }
        Box(Modifier.size(12.dp).background(mark, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                port.name + if (port.isUplink) " · uplink" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                port.connectedDeviceName ?: if (passive) "свободен" else port.status.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (passive) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
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
        title = { Text(stringResource(R.string.device_screen_06)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (vlans.isEmpty()) {
                    Text(stringResource(R.string.device_screen_07), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(stringResource(R.string.device_screen_08))
                }
                Text(
                    "Меняется запись в учёте; на сам коммутатор команда не отправляется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.device_screen_09)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Picks the other inventory entry of this same device; searched by name, address or MAC. */
@Composable
private fun MergeDialog(keep: Device, candidates: List<Device>, onPick: (Device) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = candidates.filter { it.matches(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_screen_10)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Остаётся «${keep.name}». Выберите запись того же устройства, которую нужно влить в неё и удалить.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.device_screen_11)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.id }) { d ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(d) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(d.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                (listOf(d.type.label) + d.allIps).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.device_screen_12), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.form_sheet_02)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Where one port's cable goes: the whole line through it, end to end, each device a tap away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortPathSheet(
    state: PortPathState,
    device: Device?,
    onOpenDevice: (String) -> Unit,
    onOpenPorts: (() -> Unit)?,
    onShowOnMap: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${device?.name.orEmpty()} · порт ${state.port.name}", style = MaterialTheme.typography.titleMedium)
            val path = state.path
            path?.warnings?.forEach { w ->
                Text(
                    w,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp)).padding(10.dp),
                )
            }
            when {
                state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                path == null || path.hops.size < 2 -> Text(
                    "Кабель к этому порту в учёте не подключён. Соедините его на карте (слой L1) или в разделе «Сеть».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val others = path.ends.filter { it.deviceId != device?.id }
                    Text(
                        if (others.isEmpty()) "Линия обрывается на пассивном оборудовании: дальше кабель в учёте не проложен."
                        else "Подключено: " + others.joinToString(" и ") { it.deviceName },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    others.forEach { end -> PathEndCard(end) { onOpenDevice(end.deviceId) } }
                    Text(stringResource(R.string.device_screen_13), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    path.hops.forEachIndexed { i, hop ->
                        PathHopRow(hop) { onOpenDevice(hop.deviceId) }
                        if (i < path.hops.lastIndex) {
                            Text(
                                "   │ " + listOfNotNull(hop.cableType, hop.cableLabel).joinToString(" · ").ifEmpty { "кабель" },
                                style = MonoStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (path != null && path.hops.isNotEmpty()) {
                val context = androidx.compose.ui.platform.LocalContext.current
                TextButton(onClick = {
                    val text = path.hops.joinToString(" → ") { it.deviceName }
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Путь", text))
                    Toast.makeText(context, "Путь скопирован", Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.device_screen_14)) }
            }
            path?.mapTarget(device?.id.orEmpty())?.let { target ->
                TextButton(onClick = { onShowOnMap(target.deviceId) }) { Text(stringResource(R.string.device_screen_15)) }
            }
            if (onOpenPorts != null) {
                TextButton(onClick = onOpenPorts) { Text(stringResource(R.string.device_screen_16)) }
            }
        }
    }
}

@Composable
private fun PathEndCard(hop: PathHop, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Icon(hop.deviceType.icon, contentDescription = null, tint = hop.deviceType.tint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hop.deviceName, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(hop.deviceType.label, "порт ${hop.portName}", hop.ip).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (hop.isOnline) StatusColors.Online else StatusColors.Offline)
                Spacer(Modifier.width(6.dp))
                Text(if (hop.isOnline) "в сети" else "не отвечает", style = MaterialTheme.typography.bodySmall)
            }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Открыть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PathHopRow(hop: PathHop, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (hop.isHere) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(hop.deviceType.icon, contentDescription = null, tint = hop.deviceType.tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(hop.deviceName, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull("порт ${hop.portName}", hop.ip.takeIf { !hop.isPassive }).joinToString(" · ") + if (hop.isHere) " · этот порт" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!hop.isPassive) StatusDot(if (hop.isOnline) StatusColors.Online else StatusColors.Offline)
    }
}
