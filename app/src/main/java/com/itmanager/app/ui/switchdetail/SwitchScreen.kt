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
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.StatusColors
import kotlinx.coroutines.launch

private const val NOT_YET = "Управление коммутаторами появится на следующем этапе"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SwitchScreen(
    deviceId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SwitchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedPortId by rememberSaveable(deviceId) { mutableStateOf<String?>(null) }
    val notYet: () -> Unit = { scope.launch { snackbar.showSnackbar(NOT_YET) } }

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
                        QuickAction("Ping", Icons.Outlined.NetworkCheck, notYet)
                        QuickAction("Traceroute", Icons.Outlined.Timeline, notYet)
                        QuickAction("Сравнить конфиг", Icons.Outlined.Difference, notYet)
                        QuickAction("Сохранить конфиг", Icons.Outlined.Save, notYet)
                    }

                    Spacer(Modifier.height(20.dp))
                    SectionLabel("Панель портов")
                    PortPanel(ports, selectedPortId, onPortClick = { selectedPortId = it.id })
                    Spacer(Modifier.height(12.dp))
                    PortLegend()
                    Spacer(Modifier.height(24.dp))
                }

                ports.firstOrNull { it.id == selectedPortId }?.let { port ->
                    PortSheet(port, onDismiss = { selectedPortId = null })
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
private fun PortSheet(port: SwitchPort, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(port.status.color, size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Text(port.name, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace))
            }
            Text(port.status.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            PortField("Подключено", port.connectedDeviceName ?: "—")
            PortField("Режим", port.mode.label)
            if (port.mode == PortMode.ACCESS) {
                PortField("Access VLAN", port.accessVlan?.toString() ?: "—")
            } else {
                PortField("Tagged VLAN", port.taggedVlans.joinToString(", ").ifEmpty { "все" })
            }
            PortField("Скорость", if (port.isOperUp) formatSpeed(port.speedMbps) else "—")
            PortField("PoE", if (port.isPoeActive) "Питает устройство" else if (port.poeEnabled) "Включено" else "Нет")
            PortField("Состояние", if (port.isAdminUp) "no shutdown" else "shutdown")
            Spacer(Modifier.height(16.dp))
            Text(
                "Смена режима и VLAN, shutdown и перезапуск порта появятся на этапе управления коммутаторами.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
        }
    }
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
