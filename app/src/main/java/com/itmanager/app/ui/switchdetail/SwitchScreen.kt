package com.itmanager.app.ui.switchdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.DiagnosticKind
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.StatusColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SwitchScreen(
    deviceId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiff: (switchName: String?) -> Unit,
    onOpenHistory: (switchName: String) -> Unit,
    onOpenImport: (switchName: String?) -> Unit,
    viewModel: SwitchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val vlans by viewModel.vlans.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val diagnostic by viewModel.diagnostic.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val load by viewModel.load.collectAsStateWithLifecycle()
    val portTraffic by viewModel.portTraffic.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selectedPortId by rememberSaveable(deviceId) { mutableStateOf<String?>(null) }
    var recordTarget by remember { mutableStateOf<PortRecordTarget?>(null) }
    var portView by rememberSaveable { mutableStateOf(PortView.STATUS) }
    var highlightVlan by rememberSaveable(deviceId) { mutableStateOf<Int?>(null) }
    val palette = remember(vlans) { VlanPalette(vlans) }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    // Loaded on first use of the load view, also when it comes back selected after the app was killed.
    LaunchedEffect(portView) { if (portView == PortView.LOAD) viewModel.loadTraffic() }

    val details = (state as? UiState.Content)?.data
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(details?.device?.name ?: "Коммутатор", style = MaterialTheme.typography.titleLarge)
                        details?.device?.let { d ->
                            Text(
                                listOfNotNull(d.vendor, d.model).joinToString(" "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when (val s = state) {
            UiState.Loading -> LoadingView(Modifier.padding(padding))
            is UiState.Error -> ErrorView(s.message, viewModel::refresh, onOpenSettings, Modifier.padding(padding))
            is UiState.Content -> {
                val (device, ports) = s.data
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp),
                ) {
                    if (s.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        StatusDot(if (device.isOnline) StatusColors.Online else StatusColors.Offline)
                        Spacer(Modifier.width(8.dp))
                        Text(if (device.isOnline) "В сети" else "Не отвечает", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(16.dp))
                        device.ip?.let { Text(it, style = MonoStyle) }
                        Spacer(Modifier.weight(1f))
                        LiveBadge(live)
                    }
                    PortSummary(ports)

                    Spacer(Modifier.height(20.dp))
                    SectionLabel("Быстрые действия")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickAction("Ping", Icons.Outlined.NetworkCheck) { viewModel.openDiagnostic(DiagnosticKind.PING) }
                        QuickAction("Traceroute", Icons.Outlined.Timeline) { viewModel.openDiagnostic(DiagnosticKind.TRACEROUTE) }
                        QuickAction("Сравнить конфиг", Icons.Outlined.Difference) { onOpenDiff(device.name) }
                        QuickAction("История", Icons.Outlined.History) { onOpenHistory(device.name) }
                        QuickAction("Импорт конфига", Icons.Outlined.FileDownload) { onOpenImport(device.name) }
                        QuickAction("Сохранить конфиг", Icons.Outlined.Save, viewModel::requestSaveConfig)
                        QuickAction("NFC-метка", Icons.Outlined.Nfc, viewModel::bindNfc)
                        QuickAction("Добавить порт", Icons.Outlined.Add) {
                            recordTarget = PortRecordTarget(null, (ports.maxOfOrNull { it.number } ?: 0) + 1)
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    SectionLabel("Панель портов")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        PortView.entries.forEachIndexed { i, v ->
                            SegmentedButton(
                                selected = portView == v,
                                onClick = { portView = v },
                                shape = SegmentedButtonDefaults.itemShape(i, PortView.entries.size),
                                label = { Text(v.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PortPanel(
                        ports,
                        selectedPortId,
                        onPortClick = { selectedPortId = it.id },
                        view = portView,
                        palette = palette,
                        highlightVlan = highlightVlan,
                        load = (load as? UiState.Content)?.data.orEmpty(),
                    )
                    Spacer(Modifier.height(12.dp))
                    when (portView) {
                        PortView.STATUS -> PortLegend()
                        PortView.VLAN -> VlanLegend(ports, palette, highlightVlan, onHighlight = { highlightVlan = it })
                        PortView.LOAD -> when (val l = load) {
                            is UiState.Content -> LoadLegend(ports, l.data, onPortClick = { selectedPortId = it.id })
                            is UiState.Error -> Text(l.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }

                ports.firstOrNull { it.id == selectedPortId }?.let { port ->
                    LaunchedEffect(port.id) { viewModel.openPortTraffic(port.id) }
                    PortSheet(
                        port = port,
                        traffic = portTraffic?.takeIf { it.portId == port.id },
                        vlans = vlans,
                        busy = busy,
                        onApply = { change -> viewModel.requestPortChange(port, change) },
                        onBounce = { viewModel.requestBounce(port) },
                        onEditRecord = { recordTarget = PortRecordTarget(port, port.number) },
                        onDismiss = { selectedPortId = null },
                    )
                }
                recordTarget?.let { target ->
                    PortRecordDialog(
                        target = target,
                        vlans = vlans,
                        busy = busy,
                        onSave = { draft -> viewModel.savePortRecord(target.port, draft) { recordTarget = null } },
                        onDelete = { p ->
                            viewModel.deletePortRecord(p) {
                                recordTarget = null
                                selectedPortId = null
                            }
                        },
                        onDismiss = { recordTarget = null },
                    )
                }
                diagnostic?.let { run ->
                    DiagnosticSheet(
                        run = run,
                        switchName = device.name,
                        targets = targets,
                        onRun = viewModel::runDiagnostic,
                        onDismiss = viewModel::closeDiagnostic,
                    )
                }
                pending?.let { write ->
                    ConfirmWriteDialog(
                        write = write,
                        switchName = device.name,
                        onConfirm = viewModel::confirmPending,
                        onDismiss = viewModel::dismissPending,
                    )
                }
            }
        }
    }
}

/** Tells whether the colours below are live or the last known state. */
@Composable
private fun LiveBadge(live: LiveState) {
    val (color, label) = when (live) {
        LiveState.Live -> StatusColors.Online to "Онлайн-статус"
        LiveState.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant to "Подключение…"
        is LiveState.Offline -> StatusColors.Warning to "Нет связи"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        StatusDot(color, size = 7.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun PortSummary(ports: List<SwitchPort>) {
    val up = ports.count { it.isOperUp }
    val poe = ports.count { it.isPoeActive }
    val shut = ports.count { !it.isAdminUp }
    Text(
        "Портов: ${ports.size} · с линком: $up · PoE: $poe · выключено: $shut",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        shape = RoundedCornerShape(12.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortSheet(
    port: SwitchPort,
    traffic: PortTraffic?,
    vlans: List<Vlan>,
    busy: Boolean,
    onApply: (PortChange) -> Unit,
    onBounce: () -> Unit,
    onEditRecord: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(port.status.color, size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Text(port.name, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace))
                if (port.isUplink) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "аплинк",
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusColors.Warning,
                        modifier = Modifier
                            .background(StatusColors.Warning.copy(alpha = 0.14f), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(port.status.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            PortField("Подключено", port.connectedDeviceName ?: "—")
            PortField("Скорость", if (port.isOperUp) formatSpeed(port.speedMbps) else "—")
            PortField("PoE", if (port.isPoeActive) "Питает устройство" else if (port.poeEnabled) "Включено" else "Нет")

            Spacer(Modifier.height(20.dp))
            PortTrafficSection(traffic)

            Spacer(Modifier.height(20.dp))
            PortEditor(port, vlans, busy, onApply, onBounce)

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onEditRecord, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Изменить запись о порте (только база)")
            }
            Text(
                "Имя, номер, режим, VLAN, скорость, PoE и аплинк как они записаны в учёте. Коммутатор не затрагивается.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Edits mode, VLANs and admin state locally; nothing reaches the switch until the user
 * reviews the generated commands and confirms them in a separate dialog.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PortEditor(
    port: SwitchPort,
    vlans: List<Vlan>,
    busy: Boolean,
    onApply: (PortChange) -> Unit,
    onBounce: () -> Unit,
) {
    // Reset the draft whenever the port itself changes underneath (live update or applied change).
    var mode by remember(port) { mutableStateOf(port.mode) }
    var accessVlan by remember(port) { mutableStateOf(port.accessVlan) }
    var tagged by remember(port) { mutableStateOf(port.taggedVlans.toSet()) }
    var adminUp by remember(port) { mutableStateOf(port.isAdminUp) }
    val change = PortChange.between(port, mode, accessVlan, tagged.toList(), adminUp)

    SectionLabel("Настройка порта")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PortMode.entries.forEachIndexed { i, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { mode = m },
                shape = SegmentedButtonDefaults.itemShape(i, PortMode.entries.size),
                label = { Text(m.label) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        if (mode == PortMode.ACCESS) "Access VLAN" else "Разрешённые VLAN (tagged)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    if (vlans.isEmpty()) {
        Text("Список VLAN не загрузился", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vlans.forEach { vlan ->
            val selected = if (mode == PortMode.ACCESS) accessVlan == vlan.id else vlan.id in tagged
            FilterChip(
                selected = selected,
                onClick = {
                    if (mode == PortMode.ACCESS) accessVlan = vlan.id
                    else tagged = if (selected) tagged - vlan.id else tagged + vlan.id
                },
                label = { Text("${vlan.id} · ${vlan.name}") },
                leadingIcon = { StatusDot(Color(vlan.colorArgb), size = 10.dp) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Порт включён", style = MaterialTheme.typography.titleSmall)
            Text(
                if (adminUp) "no shutdown" else "shutdown: устройство за портом потеряет связь",
                style = MaterialTheme.typography.bodySmall,
                color = if (adminUp) MaterialTheme.colorScheme.onSurfaceVariant else StatusColors.Warning,
            )
        }
        Switch(checked = adminUp, onCheckedChange = { adminUp = it })
    }

    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { onApply(change) },
        enabled = !change.isEmpty && !busy && (mode == PortMode.TRUNK || accessVlan != null),
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text("Показать команды и применить")
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onBounce,
        enabled = port.isAdminUp && !busy,
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Перезапустить порт")
    }
    Text(
        "Перезапуск: shutdown, пауза 2 секунды, no shutdown. Помогает зависшим камерам и телефонам на PoE.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** The only place a write is sent from: exact commands, the target, and an extra tick for uplinks. */
@Composable
private fun ConfirmWriteDialog(
    write: PendingWrite,
    switchName: String,
    onConfirm: (confirmUplink: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val port = when (write) {
        is PendingWrite.PortChangeWrite -> write.port
        is PendingWrite.BounceWrite -> write.port
        PendingWrite.SaveConfigWrite -> null
    }
    val needsUplinkTick = port?.isUplink == true
    var uplinkTicked by rememberSaveable { mutableStateOf(false) }
    val destructive = when (write) {
        is PendingWrite.PortChangeWrite -> write.change.isAdminUp == false || write.change.mode != null
        is PendingWrite.BounceWrite -> true
        PendingWrite.SaveConfigWrite -> false
    }
    val title = when (write) {
        is PendingWrite.PortChangeWrite -> "Изменить ${write.port.name}?"
        is PendingWrite.BounceWrite -> "Перезапустить ${write.port.name}?"
        PendingWrite.SaveConfigWrite -> "Сохранить конфигурацию?"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Коммутатор $switchName", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                when (write) {
                    is PendingWrite.PortChangeWrite -> CommandBlock(write.commands)
                    is PendingWrite.BounceWrite -> Text(
                        "Порт отключится примерно на 2 секунды" +
                            (write.port.connectedDeviceName?.let { ", $it потеряет связь" + if (write.port.isPoeActive) " и питание PoE." else "." } ?: "."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PendingWrite.SaveConfigWrite -> Text(
                        "Текущая конфигурация станет загрузочной: после перезагрузки коммутатор поднимется с ней.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (needsUplinkTick) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = uplinkTicked, onCheckedChange = { uplinkTicked = it })
                        Text(
                            "Это аплинк. Понимаю, что коммутатор может пропасть из сети.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.Warning,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(uplinkTicked) },
                enabled = !needsUplinkTick || uplinkTicked,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    when (write) {
                        is PendingWrite.BounceWrite -> "Перезапустить"
                        PendingWrite.SaveConfigWrite -> "Сохранить"
                        else -> "Применить"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun CommandBlock(commands: List<String>) {
    Text(
        commands.joinToString("\n"),
        style = MonoStyle,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(10.dp))
            .padding(12.dp),
    )
}

@Composable
private fun PortField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun formatSpeed(mbps: Int) = if (mbps >= 1000) "${mbps / 1000} Гбит/с" else "$mbps Мбит/с"
