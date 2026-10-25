package com.itmanager.app.ui.scanner

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.FoundDevice
import com.itmanager.app.data.model.FoundLink
import com.itmanager.app.data.model.ImportReport
import com.itmanager.app.data.model.NetworkScan
import com.itmanager.app.data.model.ScanStatus
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.StatusColors

/**
 * Finds devices in the ranges the user typed and the links between them, then adds the chosen ones to the
 * inventory. Read-only towards the network: ping and SNMP reads, started only by the button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onOpenMap: () -> Unit,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isDemo by viewModel.isDemo.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Сканер сети", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val scan = state.scan
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            item(key = "note") { SafetyNote(isDemo, scan) }
            item(key = "form") {
                ScanForm(
                    state = state,
                    onSubnets = viewModel::setSubnets,
                    onCommunity = viewModel::setCommunity,
                    onSuggestion = viewModel::toggleSuggestion,
                    onStart = viewModel::start,
                )
            }
            state.error?.let { message -> item(key = "error") { ErrorCard(message) } }
            if (scan != null) {
                item(key = "progress") { ScanProgress(scan) }
            }
            state.report?.let { report ->
                item(key = "report") { ReportCard(report, onOpenMap, viewModel::dismissReport) }
            }
            if (scan != null && scan.status == ScanStatus.DONE) {
                val fresh = scan.newDevices
                item(key = "new-head") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        SectionLabel("Новые устройства: ${fresh.size}", Modifier.weight(1f))
                        if (fresh.isNotEmpty()) {
                            val all = state.selected.size == fresh.size
                            TextButton(onClick = { viewModel.selectAllNew(!all) }) { Text(if (all) "Снять все" else "Выбрать все") }
                        }
                    }
                }
                if (fresh.isEmpty()) {
                    item(key = "new-empty") { Hint("Всё, что ответило, уже есть в учёте.") }
                }
                items(fresh, key = { "new-${it.ip}" }) { device ->
                    FoundRow(device, checked = device.ip in state.selected, onToggle = { viewModel.toggle(device.ip) })
                }
                if (fresh.isNotEmpty()) {
                    item(key = "import") {
                        ImportBar(
                            count = state.selected.size,
                            withLinks = state.withLinks,
                            importing = state.importing,
                            onWithLinks = viewModel::setWithLinks,
                            onImport = viewModel::import,
                        )
                    }
                }
                item(key = "links-head") { SectionLabel("Связи: ${scan.links.size}, новых ${scan.newLinks.size}", Modifier.padding(top = 12.dp)) }
                if (scan.links.isEmpty()) {
                    item(key = "links-empty") {
                        Hint("Связи берутся из LLDP, CDP и MAC-таблиц коммутаторов. Добавьте в диапазон подсеть управления коммутаторами и проверьте community.")
                    }
                }
                items(scan.links.sortedBy { it.known }, key = { "link-${it.a.label}-${it.b.label}" }) { LinkRow(it) }
                val known = scan.knownDevices
                if (known.isNotEmpty()) {
                    item(key = "known-head") { SectionLabel("Уже в учёте: ${known.size}", Modifier.padding(top = 12.dp)) }
                    items(known, key = { "known-${it.ip}" }) { FoundRow(it, checked = null, onToggle = {}) }
                }
            }
        }
    }
}

@Composable
private fun SafetyNote(isDemo: Boolean, scan: NetworkScan?) {
    val emulated = isDemo || scan?.emulated == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Icon(
            if (emulated) Icons.Outlined.Science else Icons.Outlined.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            when {
                isDemo -> "Демо-режим: сеть эмулируется внутри приложения, в настоящую сеть ничего не уходит."
                scan?.emulated == true -> "Сервер в режиме эмуляции: ответы берутся из учёта и нескольких придуманных устройств."
                else -> "Только чтение: ping и SNMP-запросы GET к указанным адресам. На оборудовании ничего не меняется."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanForm(
    state: ScannerUiState,
    onSubnets: (String) -> Unit,
    onCommunity: (String) -> Unit,
    onSuggestion: (String) -> Unit,
    onStart: () -> Unit,
) {
    var showCommunity by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = state.subnets,
            onValueChange = onSubnets,
            label = { Text("Подсети") },
            placeholder = { Text("10.99.0.0/24, 10.10.30.0/24") },
            supportingText = { Text("Только внутренние адреса, до /22 каждая, не больше 8 подсетей") },
            enabled = !state.running,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            minLines = 1,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.suggestions.forEach { vlan ->
                    val picked = state.subnets.split(',', ' ', '\n', ';').any { it.trim() == vlan.subnet }
                    FilterChip(
                        selected = picked,
                        onClick = { onSuggestion(vlan.subnet) },
                        enabled = !state.running,
                        label = { Text("${vlan.name} · ${vlan.subnet}", maxLines = 1) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.community,
            onValueChange = onCommunity,
            label = { Text("SNMP community (только чтение)") },
            singleLine = true,
            enabled = !state.running,
            visualTransformation = if (showCommunity) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showCommunity = !showCommunity }) {
                    Icon(
                        if (showCommunity) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (showCommunity) "Скрыть" else "Показать",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            supportingText = { Text("Используется только для этого сканирования и нигде не сохраняется") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStart, enabled = !state.running && !state.importing, modifier = Modifier.fillMaxWidth()) {
            if (state.running) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Идёт сканирование")
            } else {
                Text("Сканировать")
            }
        }
    }
}

@Composable
private fun ScanProgress(scan: NetworkScan) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .animateContentSize()
            .padding(16.dp),
    ) {
        Text(scan.subnets.joinToString(", "), style = MonoStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        when (scan.status) {
            ScanStatus.RUNNING -> {
                LinearProgressIndicator(progress = { scan.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    "Проверено ${scan.probed} из ${scan.total} адресов",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ScanStatus.DONE -> Text(
                "Ответили ${scan.devices.size} из ${scan.total}, по SNMP ${scan.devices.count { it.snmp }}. " +
                    "Новых устройств ${scan.newDevices.size}, связей ${scan.links.size}.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ScanStatus.FAILED -> Text(
                "Сканирование не удалось: ${scan.error ?: "причина неизвестна"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun FoundRow(device: FoundDevice, checked: Boolean?, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .then(if (checked != null) Modifier.clickable(role = Role.Checkbox, onClick = onToggle) else Modifier)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        ) {
            Icon(device.type.icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(device.vendor, device.model).joinToString(" ").ifEmpty {
                    if (device.snmp) device.type.label else "Ответил только на ping"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(device.ip, device.mac).joinToString("  "),
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (checked != null) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        } else {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "Уже в учёте",
                tint = StatusColors.Online,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
            )
        }
    }
}

@Composable
private fun ImportBar(count: Int, withLinks: Boolean, importing: Boolean, onWithLinks: (Boolean) -> Unit, onImport: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Со связями", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Найденные кабели появятся на карте",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = withLinks, onCheckedChange = onWithLinks)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onImport, enabled = count > 0 && !importing, modifier = Modifier.fillMaxWidth()) {
            if (importing) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(if (count > 0) "Добавить в учёт ($count)" else "Отметьте устройства")
        }
    }
}

@Composable
private fun LinkRow(link: FoundLink) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("${link.a.label}  ↔  ${link.b.label}", style = MaterialTheme.typography.bodyMedium)
            Text(
                link.source.label + if (link.known) " · уже в учёте" else " · новая",
                style = MaterialTheme.typography.bodySmall,
                color = if (link.known) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ReportCard(report: ImportReport, onOpenMap: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            if (report.created.isEmpty() && report.links.isEmpty()) {
                "Ничего нового: всё уже было в учёте."
            } else {
                "Добавлено устройств: ${report.created.size}, связей: ${report.links.size}."
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (report.created.isNotEmpty()) {
            Text(
                report.created.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        report.skipped.forEach {
            Text("Пропущено: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = onDismiss) { Text("Скрыть") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onOpenMap) {
                Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Открыть карту")
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
