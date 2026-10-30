package com.itmanager.app.ui.devices

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.itmanager.app.ui.components.tint
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.itmanager.app.data.export.ExportFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ArrowDropDown
import com.itmanager.app.data.model.isPassive
import com.itmanager.app.data.model.isDown
import com.itmanager.app.data.model.DeviceGrouping
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.components.TabTopBar
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.pressScale
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.StatusColors

@Composable
fun DevicesScreen(
    contentPadding: PaddingValues,
    onOpenDevice: (String) -> Unit,
    onShowOnMap: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenPrinters: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.exports.collect { result ->
            result.fold(
                onSuccess = { file ->
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(file.mime)
                        .putExtra(Intent.EXTRA_STREAM, file.uri)
                        .putExtra(Intent.EXTRA_SUBJECT, file.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Отправить отчёт"))
                },
                onFailure = { e -> Toast.makeText(context, e.message ?: "Отчёт не получился", Toast.LENGTH_LONG).show() },
            )
        }
    }
    val openDeviceLater = rememberUpdatedState(onOpenDevice)
    LaunchedEffect(viewModel) {
        viewModel.scanned.collect { device -> openDeviceLater.value(device.id) }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val showOnMap = { id: String ->
        viewModel.showOnMap(id)
        onShowOnMap()
    }
    val selecting = state.selected.isNotEmpty()
    val bulk by viewModel.bulk.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    bulk?.let { b ->
        BulkEditDialog(
            count = state.selected.size,
            state = b,
            onApply = viewModel::applyBulk,
            onDismiss = viewModel::closeBulkEdit,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить устройств: ${state.selected.size}?") },
            text = { Text("Они пропадут из учёта и с карты вместе со своими портами и кабелями. Само оборудование это не затрагивает.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteSelected()
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = state.selected.size,
                onClose = viewModel::clearSelection,
                onSelectAll = viewModel::selectAllVisible,
                onEdit = viewModel::openBulkEdit,
                onDelete = { confirmDelete = true },
            )
        } else {
            TabTopBar(
                title = "Устройства",
                subtitle = if (state.offlineCount > 0) "Не отвечают: ${state.offlineCount}" else "Долгое нажатие: выбрать несколько",
                actions = {
                    IconButton(
                        onClick = {
                            val options = GmsBarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .enableAutoZoom()
                                .build()
                            GmsBarcodeScanning.getClient(context, options).startScan()
                                .addOnSuccessListener { viewModel.openScanned(it.rawValue.orEmpty()) }
                                .addOnFailureListener {
                                    Toast.makeText(context, "Сканер QR недоступен", Toast.LENGTH_SHORT).show()
                                }
                        },
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Сканировать наклейку")
                    }
                    IconButton(onClick = onOpenScanner) {
                        Icon(Icons.Outlined.TravelExplore, contentDescription = "Сканер сети")
                    }
                    ExportButton(exporting, onExport = viewModel::export)
                },
            )
        }
        SearchField(state.query, viewModel::setQuery, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            // Kinds that are not there are left out; the chosen one stays so it can be switched off.
            val chips = DeviceFilter.entries.filter { f -> f == DeviceFilter.ALL || f == state.filter || (state.counts[f] ?: 0) > 0 }
            items(chips, key = { it.name }) { f ->
                val count = state.counts[f] ?: 0
                FilterChip(
                    selected = state.filter == f,
                    onClick = { viewModel.setFilter(if (state.filter == f) DeviceFilter.ALL else f) },
                    label = { Text("${f.label} $count") },
                    leadingIcon = if (f == DeviceFilter.OFFLINE) ({ StatusDot(StatusColors.Offline) }) else null,
                )
            }
        }
        if (state.filter == DeviceFilter.PRINTERS) {
            TextButton(onClick = onOpenPrinters, modifier = Modifier.padding(start = 8.dp)) { Text("Тонер, барабаны и счётчики страниц") }
        }
        GroupingRow(
            shown = (state.devices as? UiState.Content)?.data?.size,
            grouping = state.grouping,
            onGrouping = viewModel::setGrouping,
            modifier = Modifier.padding(start = 20.dp, end = 8.dp),
        )

        when (val devices = state.devices) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(devices.message, viewModel::retry, onOpenSettings, contentPadding = contentPadding)
            is UiState.Content -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (devices.data.isEmpty()) {
                    item {
                        when {
                            state.query.isNotBlank() || state.filter != DeviceFilter.ALL ->
                                EmptyView("Ничего не найдено", action = "Сбросить поиск и фильтр") {
                                    viewModel.setQuery("")
                                    viewModel.setFilter(DeviceFilter.ALL)
                                }
                            else -> EmptyView("В учёте пока нет устройств. Найдите их сканером сети или добавьте на карте.", action = "Сканер сети", onAction = onOpenScanner)
                        }
                    }
                }
                state.groups.forEach { group ->
                    item(key = "h-${group.key}", contentType = "header") {
                        GroupHeader(group.title, group.devices.size, Modifier.animateItem())
                    }
                    items(group.devices, key = { it.id }) { device ->
                        DeviceRow(
                            device = device,
                            selecting = selecting,
                            checked = device.id in state.selected,
                            onClick = {
                                when {
                                    selecting -> viewModel.toggle(device.id)
                                    else -> onOpenDevice(device.id)
                                }
                            },
                            onLongClick = { viewModel.toggle(device.id) },
                            onShowOnMap = { showOnMap(device.id) }.takeIf { device.floorId != null },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

/** Inventory export: Excel for working with the list, PDF for printing or sending on. */
@Composable
private fun ExportButton(exporting: ExportFormat?, onExport: (ExportFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = exporting == null) {
            if (exporting != null) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.IosShare, contentDescription = "Экспорт учёта")
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExportFormat.entries.forEach { f ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (f) {
                                ExportFormat.XLSX -> "Таблица Excel (.xlsx)"
                                ExportFormat.PDF -> "Документ PDF"
                                ExportFormat.NETBOX -> "Архив для NetBox (.zip)"
                                ExportFormat.LABELS -> "Наклейки с QR (печать)"
                            },
                        )
                    },
                    leadingIcon = {
                        Icon(
                            when (f) {
                                ExportFormat.XLSX -> Icons.Outlined.TableChart
                                ExportFormat.PDF -> Icons.Outlined.PictureAsPdf
                                ExportFormat.NETBOX -> Icons.Outlined.Dns
                                ExportFormat.LABELS -> Icons.Outlined.QrCode2
                            },
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        open = false
                        onExport(f)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text("Имя, IP или MAC") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) { Icon(Icons.Outlined.Clear, contentDescription = "Очистить") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Selection mode header: how many are picked and what can be done with them. */
@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Закончить выбор") }
        Text("Выбрано: $count", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, contentDescription = "Выбрать все показанные") }
        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Изменить выбранные") }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить выбранные", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DeviceRow(
    device: Device,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShowOnMap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val passive = device.isPassive
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.98f)
            .background(
                if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(16.dp),
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onClick = onClick,
            )
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    CircleShape,
                ),
        ) {
            if (checked) {
                Icon(Icons.Outlined.Check, contentDescription = "Выбрано", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            } else {
                Icon(device.type.icon, contentDescription = device.type.label, tint = device.type.tint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!passive) {
                    StatusDot(if (device.isOnline) StatusColors.Online else StatusColors.Offline)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (device.isDown) {
                    Spacer(Modifier.width(8.dp))
                    Text("не в сети", style = MaterialTheme.typography.labelSmall, color = StatusColors.Offline, maxLines = 1)
                }
            }
            Text(
                listOfNotNull(device.vendor, device.model).joinToString(" ").ifEmpty { device.type.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            device.ip?.let {
                Text(it, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!selecting && onShowOnMap != null) {
            IconButton(onClick = onShowOnMap) {
                Icon(Icons.Outlined.Place, contentDescription = "Показать на карте")
            }
        }
        if (!selecting) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How many are shown and how they are split into sections. */
@Composable
private fun GroupingRow(shown: Int?, grouping: DeviceGrouping, onGrouping: (DeviceGrouping) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Text(
            shown?.let { "Показано: $it" }.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { open = true }) {
                Text(grouping.label)
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Группировка")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DeviceGrouping.entries.forEach { g ->
                    DropdownMenuItem(
                        text = { Text(g.label) },
                        onClick = {
                            open = false
                            onGrouping(g)
                        },
                        trailingIcon = if (g == grouping) ({ Icon(Icons.Outlined.Check, contentDescription = null) }) else null,
                    )
                }
            }
        }
    }
}
