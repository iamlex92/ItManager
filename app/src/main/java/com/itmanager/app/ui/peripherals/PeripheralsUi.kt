package com.itmanager.app.ui.peripherals

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Dock
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Scanner
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.Peripheral
import com.itmanager.app.data.model.PeripheralDraft
import com.itmanager.app.data.model.PeripheralKind
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.tint

val PeripheralKind.icon: ImageVector
    get() = when (this) {
        PeripheralKind.MONITOR -> Icons.Outlined.Monitor
        PeripheralKind.KEYBOARD -> Icons.Outlined.Keyboard
        PeripheralKind.MOUSE -> Icons.Outlined.Mouse
        PeripheralKind.DOCK -> Icons.Outlined.Dock
        PeripheralKind.HEADSET -> Icons.Outlined.Headset
        PeripheralKind.WEBCAM -> Icons.Outlined.Videocam
        PeripheralKind.SPEAKERS -> Icons.Outlined.Speaker
        PeripheralKind.SCANNER -> Icons.Outlined.Scanner
        PeripheralKind.PRINTER -> Icons.Outlined.Print
        PeripheralKind.PROJECTOR -> Icons.Outlined.Slideshow
        PeripheralKind.UPS -> Icons.Outlined.BatteryChargingFull
        PeripheralKind.OTHER -> Icons.Outlined.DevicesOther
    }

private val PeripheralTint = Color(0xFF9FC5FF)

/** What to write in «Характеристики» for each kind. */
private fun PeripheralKind.specsHint(): String = when (this) {
    PeripheralKind.MONITOR -> "24\", 1920×1080, DP/HDMI"
    PeripheralKind.DOCK -> "USB-C, 90 Вт, 2×DP"
    PeripheralKind.UPS -> "650 ВА, 360 Вт"
    PeripheralKind.KEYBOARD, PeripheralKind.MOUSE -> "USB, беспроводная"
    PeripheralKind.HEADSET -> "USB, с микрофоном"
    PeripheralKind.PRINTER, PeripheralKind.SCANNER -> "A4, USB"
    PeripheralKind.PROJECTOR -> "1920×1080, 3500 лм"
    else -> "Главное в двух словах"
}

/** One peripheral in a list: what it is, its details and, with [showDevice], where it is. */
@Composable
fun PeripheralRow(item: Peripheral, showDevice: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(item.kind.icon, contentDescription = null, tint = PeripheralTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val second = listOfNotNull(
                item.details.takeIf { it.isNotEmpty() },
                if (showDevice) item.deviceName?.let { "→ $it" } ?: "на складе" else null,
            ).joinToString(" · ")
            if (second.isNotEmpty()) {
                Text(
                    second,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The peripherals of one device, full screen: add a new one, take one from stock, tap one to change,
 * move or detach it.
 */
@Composable
fun DevicePeripheralsDialog(device: Device, viewModel: PeripheralsViewModel, all: List<Peripheral>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp)) {
                    Icon(device.type.icon, contentDescription = null, tint = device.type.tint)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Периферия", style = MaterialTheme.typography.titleLarge)
                        Text(device.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть") }
                }
                DevicePeripheralsContent(device, viewModel, all, Modifier.weight(1f))
            }
        }
    }
}

/**
 * The peripherals of one device: add a new one, take one from stock, tap one to change, move or detach it.
 * Used in the device card's tab and in the map's dialog.
 */
@Composable
fun DevicePeripheralsContent(device: Device, viewModel: PeripheralsViewModel, all: List<Peripheral>, modifier: Modifier = Modifier) {
    val own = all.filter { it.deviceId == device.id }
    val stock = all.filter { it.deviceId == null }
    var pickStock by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            FilledTonalButton(onClick = { viewModel.openNew(PeripheralDraft(PeripheralKind.MONITOR, deviceId = device.id)) }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Добавить")
            }
            OutlinedButton(onClick = { pickStock = true }, enabled = stock.isNotEmpty()) {
                Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (stock.isEmpty()) "Склад пуст" else "Со склада (${stock.size})")
            }
        }
        if (own.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "К ${device.name} ничего не подключено. Добавьте монитор, клавиатуру, мышь, док-станцию или возьмите со склада.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
        ) {
            items(own, key = { it.id }) { item ->
                PeripheralRow(item, showDevice = false, onClick = { viewModel.openEdit(item) })
            }
        }
    }

    if (pickStock) {
        StockPickerDialog(
            stock = stock,
            onPick = { item ->
                pickStock = false
                viewModel.attach(item, device.id)
            },
            onDismiss = { pickStock = false },
        )
    }
}

@Composable
fun StockPickerDialog(stock: List<Peripheral>, onPick: (Peripheral) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = stock.filter { it.matches(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Со склада") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(50) },
                    label = { Text("Поиск: модель, S/N, инв. номер") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.id }) { item -> PeripheralRow(item, showDevice = false, onClick = { onPick(item) }) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Which device a peripheral goes to; «На складе» detaches it. */
@Composable
fun DevicePickerDialog(devices: List<Device>, current: String?, onPick: (Device?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = devices.filter { it.matches(query) }.take(300)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Куда подключено") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(50) },
                    label = { Text("Имя, IP или модель") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item(key = "stock") {
                        PickRow(Icons.Outlined.Inventory2, MaterialTheme.colorScheme.onSurfaceVariant, "На складе", "Ни к чему не подключено", current == null) {
                            onPick(null)
                        }
                    }
                    items(shown, key = { it.id }) { d ->
                        PickRow(d.type.icon, d.type.tint, d.name, listOfNotNull(d.type.label, d.ip).joinToString(" · "), d.id == current) {
                            onPick(d)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun PickRow(icon: ImageVector, tint: Color, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The form of a new or existing peripheral. Delete asks once more before it goes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeripheralEditorDialog(
    editor: PeripheralEditor,
    devices: List<Device>,
    onChange: (PeripheralDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: (Peripheral) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = editor.draft
    var pickDevice by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val deviceName = draft.deviceId?.let { id -> devices.firstOrNull { it.id == id }?.name ?: editor.editing?.deviceName ?: "устройство" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.editing == null) "Новая периферия" else editor.editing.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PeripheralKind.entries.forEach { kind ->
                        FilterChip(
                            selected = draft.kind == kind,
                            onClick = { onChange(draft.copy(kind = kind)) },
                            label = { Text(kind.label) },
                            leadingIcon = { Icon(kind.icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
                Field("Производитель", draft.vendor, 50, "Dell") { onChange(draft.copy(vendor = it)) }
                Field("Модель", draft.model, 100, "P2422H") { onChange(draft.copy(model = it)) }
                Field("Характеристики", draft.specs, 200, draft.kind.specsHint()) { onChange(draft.copy(specs = it)) }
                Field("Серийный номер", draft.serialNumber, 100, null) { onChange(draft.copy(serialNumber = it)) }
                Field("Инвентарный номер", draft.inventoryNumber, 100, null) { onChange(draft.copy(inventoryNumber = it)) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                        .clickable { pickDevice = true }
                        .padding(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Подключено к", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(deviceName ?: "На складе", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("Изменить", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(
                    value = draft.comment.orEmpty(),
                    onValueChange = { onChange(draft.copy(comment = it.take(4000))) },
                    label = { Text("Комментарий") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (editor.editing != null) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !editor.busy) {
                        Text("Удалить из учёта", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !editor.busy) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )

    if (pickDevice) {
        DevicePickerDialog(
            devices = devices,
            current = draft.deviceId,
            onPick = { d ->
                pickDevice = false
                onChange(draft.copy(deviceId = d?.id))
            },
            onDismiss = { pickDevice = false },
        )
    }
    if (confirmDelete && editor.editing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить ${editor.editing.title}?") },
            text = { Text("Запись пропадёт из учёта. Если вещь просто убрали, лучше верните её на склад.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(editor.editing) }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun Field(label: String, value: String?, max: Int, hint: String?, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = { onValue(it.take(max)) },
        label = { Text(label) },
        placeholder = if (hint != null) {
            { Text(hint) }
        } else {
            null
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** «Монитор Dell P2422H, Клавиатура Logitech K120 и ещё 2» for the device card. */
fun peripheralSummary(items: List<Peripheral>): String? {
    if (items.isEmpty()) return null
    val shown = items.take(2).joinToString(", ") { it.title }
    return if (items.size > 2) "$shown и ещё ${items.size - 2}" else shown
}
