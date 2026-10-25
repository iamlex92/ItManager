package com.itmanager.app.ui.devices

import android.content.Intent
import android.widget.Toast
import androidx.compose.material.icons.outlined.IosShare
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
import androidx.compose.foundation.clickable
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
    onOpenSwitch: (String) -> Unit,
    onShowOnMap: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScanner: () -> Unit,
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
    val showOnMap = { id: String ->
        viewModel.showOnMap(id)
        onShowOnMap()
    }

    Column(Modifier.fillMaxSize()) {
        TabTopBar(
            title = "Устройства",
            subtitle = if (state.offlineCount > 0) "Не отвечают: ${state.offlineCount}" else null,
            onOpenSettings = onOpenSettings,
            actions = {
                IconButton(onClick = onOpenScanner) {
                    Icon(Icons.Outlined.TravelExplore, contentDescription = "Сканер сети")
                }
                ExportButton(exporting, onExport = viewModel::export)
            },
        )
        SearchField(state.query, viewModel::setQuery, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(DeviceFilter.entries) { f ->
                FilterChip(selected = state.filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(f.label) })
            }
        }
        Spacer(Modifier.height(8.dp))

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
                    item { EmptyView(if (state.query.isBlank()) "Устройств нет" else "Ничего не найдено") }
                }
                items(devices.data, key = { it.id }) { device ->
                    DeviceRow(
                        device = device,
                        onClick = {
                            if (device.type == DeviceType.SWITCH) onOpenSwitch(device.id) else showOnMap(device.id)
                        },
                        onShowOnMap = { showOnMap(device.id) },
                        modifier = Modifier.animateItem(),
                    )
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
                    text = { Text(if (f == ExportFormat.XLSX) "Таблица Excel (.xlsx)" else "Документ PDF") },
                    leadingIcon = {
                        Icon(if (f == ExportFormat.XLSX) Icons.Outlined.TableChart else Icons.Outlined.PictureAsPdf, contentDescription = null)
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

@Composable
private fun DeviceRow(
    device: Device,
    onClick: () -> Unit,
    onShowOnMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val passive = device.type == DeviceType.SOCKET || device.type == DeviceType.PATCH_PANEL
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.98f)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        ) {
            Icon(device.type.icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!passive) {
                    StatusDot(if (device.isOnline) StatusColors.Online else StatusColors.Offline)
                    Spacer(Modifier.width(6.dp))
                }
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        IconButton(onClick = onShowOnMap) {
            Icon(Icons.Outlined.Place, contentDescription = "Показать на карте")
        }
        if (device.type == DeviceType.SWITCH) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
