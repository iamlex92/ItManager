package com.itmanager.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itmanager.app.data.model.Connection
import com.itmanager.app.data.model.DEFAULT_RACK_UNITS
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceDraft
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.isMac
import com.itmanager.app.data.model.FloorInfo
import com.itmanager.app.ui.components.DEVICE_TYPES_IN_ORDER
import com.itmanager.app.ui.components.icon

private val EditTool.icon: ImageVector
    get() = when (this) {
        EditTool.MOVE -> Icons.Outlined.OpenWith
        EditTool.LINK -> Icons.Outlined.Cable
        EditTool.DRAW -> Icons.Outlined.Draw
        EditTool.ZONES -> Icons.Outlined.MeetingRoom
        EditTool.PLAN -> Icons.Outlined.Image
    }

/** The editor's controls at the bottom of the map: a tool switch, the tool's actions and one line of help. */
@Composable
fun MapEditToolbar(
    state: MapEditorState,
    linkFromName: String?,
    /** The cable whose route is being edited, as "SW-01 — PC-07". */
    selectedLinkName: String?,
    hasPlan: Boolean,
    hasDrawings: Boolean,
    /** The zone being edited: its name, and what to do with the outline in progress. */
    selectedZoneName: String?,
    onUndoZone: () -> Unit,
    onFinishZone: () -> Unit,
    onCancelZone: () -> Unit,
    onEditZone: () -> Unit,
    onTool: (EditTool) -> Unit,
    onAddDevice: () -> Unit,
    onAddRack: () -> Unit,
    onCable: (String) -> Unit,
    onCancelLink: () -> Unit,
    onDeletePoint: () -> Unit,
    onStraighten: () -> Unit,
    onDeleteLink: () -> Unit,
    onDoneLink: () -> Unit,
    /** The colour of the selected cable (null: automatic) and a pick from the swatches. */
    selectedLinkColor: Long?,
    onCableColor: (Long?) -> Unit,
    onPenColor: (Long) -> Unit,
    onUndo: () -> Unit,
    onClearDrawings: () -> Unit,
    onPickPlan: () -> Unit,
    onScalePlan: (Float) -> Unit,
    onFitPlan: () -> Unit,
    onRemovePlan: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                EditTool.entries.forEachIndexed { index, tool ->
                    SegmentedButton(
                        selected = tool == state.tool,
                        onClick = { onTool(tool) },
                        shape = SegmentedButtonDefaults.itemShape(index, EditTool.entries.size),
                        icon = {},
                        label = { Icon(tool.icon, contentDescription = tool.label, modifier = Modifier.size(20.dp)) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                when (state.tool) {
                    EditTool.MOVE -> {
                        FilledTonalButton(onClick = onAddDevice, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Объект")
                        }
                        FilledTonalButton(onClick = onAddRack, modifier = Modifier.weight(1f)) {
                            Icon(DeviceType.RACK.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Стойка")
                        }
                    }
                    EditTool.LINK -> if (selectedLinkName != null) {
                        Text(
                            selectedLinkName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDeletePoint, enabled = state.selectedPoint != null) {
                            Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = "Удалить точку поворота")
                        }
                        TextButton(onClick = onStraighten) { Text("Выпрямить") }
                        IconButton(onClick = onDeleteLink) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить кабель") }
                        IconButton(onClick = onDoneLink) { Icon(Icons.Outlined.Close, contentDescription = "Закончить с кабелем") }
                    } else if (linkFromName != null) {
                        Text(
                            "От $linkFromName: коснитесь второго устройства",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onCancelLink) { Icon(Icons.Outlined.Close, contentDescription = "Отменить выбор") }
                    } else {
                        FilterChip(state.cable == CABLE_COPPER, onClick = { onCable(CABLE_COPPER) }, label = { Text("Медь Cat6") })
                        FilterChip(state.cable == CABLE_FIBER, onClick = { onCable(CABLE_FIBER) }, label = { Text("Оптика OM3") })
                    }
                    EditTool.DRAW -> {
                        PEN_COLORS.forEach { color ->
                            val selected = color == state.penColor
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                    .padding(4.dp)
                                    .background(Color(color), CircleShape)
                                    .clickable { onPenColor(color) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onUndo, enabled = hasDrawings) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Отменить последнюю линию")
                        }
                        IconButton(onClick = onClearDrawings, enabled = hasDrawings) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Стереть все линии")
                        }
                    }
                    EditTool.ZONES -> when {
                        state.zoneDraft.isNotEmpty() -> {
                            Text("Углов: ${state.zoneDraft.size / 2}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = onUndoZone) { Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Убрать последний угол") }
                            IconButton(onClick = onCancelZone) { Icon(Icons.Outlined.Close, contentDescription = "Отменить зону") }
                            FilledTonalButton(onClick = onFinishZone, enabled = state.zoneDraft.size >= 6) { Text("Готово") }
                        }
                        selectedZoneName != null -> {
                            Text(
                                selectedZoneName.ifBlank { "Без названия" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onEditZone) { Text("Изменить") }
                            IconButton(onClick = onCancelZone) { Icon(Icons.Outlined.Close, contentDescription = "Снять выбор") }
                        }
                        else -> Text("Касайтесь углов по очереди, затем «Готово»", style = MaterialTheme.typography.bodyMedium)
                    }
                    EditTool.PLAN -> if (!hasPlan) {
                        FilledTonalButton(onClick = onPickPlan, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Загрузить план (PDF, PNG, JPEG, SVG)")
                        }
                    } else {
                        IconButton(onClick = { onScalePlan(1f / 1.1f) }) { Icon(Icons.Outlined.Remove, contentDescription = "Уменьшить план") }
                        IconButton(onClick = { onScalePlan(1.1f) }) { Icon(Icons.Outlined.Add, contentDescription = "Увеличить план") }
                        IconButton(onClick = onFitPlan) { Icon(Icons.Outlined.FitScreen, contentDescription = "Вписать план в этаж") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onPickPlan, enabled = !state.busy) { Text("Заменить") }
                        IconButton(onClick = onRemovePlan) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Убрать план") }
                    }
                }
            }
            if (state.tool == EditTool.LINK && selectedLinkName != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Цвет", style = MaterialTheme.typography.labelLarge)
                    FilterChip(selected = selectedLinkColor == null, onClick = { onCableColor(null) }, label = { Text("Авто") })
                    CABLE_COLORS.forEach { color ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .border(2.dp, if (color == selectedLinkColor) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .padding(4.dp)
                                .background(Color(color), CircleShape)
                                .clickable { onCableColor(color) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            val error = state.error
            Text(
                error ?: if (selectedLinkName != null) ROUTE_HINT else "${state.tool.label}: ${state.tool.hint}",
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (error != null) Modifier.clickable(onClick = onDismissError) else Modifier),
            )
        }
    }
}

private const val ROUTE_HINT =
    "Тяните белые точки, чтобы вести кабель по трассе; «+» добавляет точку поворота. Коснитесь точки, чтобы выбрать её для удаления."

private val FORM_TYPES = DEVICE_TYPES_IN_ORDER

private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")

/** Adds an object to the map or edits one. Only the inventory changes; the device itself is never contacted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceFormDialog(
    form: DeviceForm,
    busy: Boolean,
    floors: List<FloorInfo>,
    onFloor: (String) -> Unit,
    onSave: (DeviceDraft) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Servers a virtual machine can run on. */
    hosts: List<Device> = emptyList(),
    /** Opens the front view of a rack being edited. */
    onOpenRack: ((String) -> Unit)? = null,
) {
    val device = form.device
    val key = device?.id ?: "new"
    var name by rememberSaveable(key) { mutableStateOf(device?.name.orEmpty()) }
    var type by rememberSaveable(key) { mutableStateOf(device?.type ?: form.presetType ?: DeviceType.SWITCH) }
    var ip by rememberSaveable(key) { mutableStateOf(device?.ip.orEmpty()) }
    var mac by rememberSaveable(key) { mutableStateOf(device?.mac.orEmpty()) }
    var vendor by rememberSaveable(key) { mutableStateOf(device?.vendor.orEmpty()) }
    var model by rememberSaveable(key) { mutableStateOf(device?.model.orEmpty()) }
    var hostId by rememberSaveable(key) { mutableStateOf(device?.hostId) }
    var comment by rememberSaveable(key) { mutableStateOf(device?.comment.orEmpty()) }
    var rackUnits by rememberSaveable(key) { mutableStateOf((device?.rackUnits ?: DEFAULT_RACK_UNITS).toString()) }
    var confirmDelete by rememberSaveable(key) { mutableStateOf(false) }
    val ipError = ip.isNotBlank() && !IPV4.matches(ip.trim())
    val macError = mac.isNotBlank() && !isMac(mac)

    if (confirmDelete && device != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить ${device.name}?") },
            text = { Text("Устройство и его кабели пропадут из инвентаря и с карты. Само оборудование это не затрагивает.") },
            confirmButton = {
                TextButton(onClick = { onDelete(device.id) }, enabled = !busy) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (device == null) "Новый объект" else "Объект на карте") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FORM_TYPES.forEach { t ->
                        FilterChip(
                            selected = t == type,
                            onClick = { type = t },
                            label = { Text(t.label) },
                            leadingIcon = { Icon(t.icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text("Имя") },
                    placeholder = { Text("Например, SW-ACC-04") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.take(15) },
                    label = { Text("IP-адрес, если есть") },
                    isError = ipError,
                    supportingText = { if (ipError) Text("Нужен адрес вида 10.0.0.1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it.take(17) },
                    label = { Text("MAC-адрес, если есть") },
                    placeholder = { Text("aa:bb:cc:dd:ee:ff") },
                    isError = macError,
                    supportingText = { if (macError) Text("Нужен адрес вида aa:bb:cc:dd:ee:ff") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vendor,
                        onValueChange = { vendor = it.take(50) },
                        label = { Text("Производитель") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it.take(100) },
                        label = { Text("Модель") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (type == DeviceType.RACK) {
                    OutlinedTextField(
                        value = rackUnits,
                        onValueChange = { rackUnits = it.filter(Char::isDigit).take(2) },
                        label = { Text("Высота стойки, U") },
                        supportingText = { Text("Коммутаторы и серверы ставятся в стойку в её виде.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (device != null && device.type == DeviceType.RACK && onOpenRack != null) {
                        FilledTonalButton(onClick = { onOpenRack(device.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Открыть вид стойки и расставить устройства")
                        }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(4000) },
                    label = { Text("Комментарий") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                val hostChoices = hosts.filter { it.id != device?.id }
                if (type == DeviceType.VIRTUAL && hostChoices.isNotEmpty()) {
                    Text("Хост виртуализации", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = hostId == null, onClick = { hostId = null }, label = { Text("Не указан") })
                        hostChoices.forEach { h ->
                            FilterChip(selected = h.id == hostId, onClick = { hostId = h.id }, label = { Text(h.name) })
                        }
                    }
                }
                if (floors.size > 1) {
                    Text("Этаж", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        floors.forEach { f ->
                            FilterChip(selected = f.id == form.floorId, onClick = { onFloor(f.id) }, label = { Text(f.title) })
                        }
                    }
                }
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (device != null) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Удалить с карты и из инвентаря", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        DeviceDraft(
                            name = name.trim(),
                            type = type,
                            ip = ip.trim().ifEmpty { null },
                            mac = mac.trim().ifEmpty { null },
                            vendor = vendor.trim().ifEmpty { null },
                            model = model.trim().ifEmpty { null },
                            hostId = hostId.takeIf { type == DeviceType.VIRTUAL },
                            comment = comment.trim().ifEmpty { null },
                            rackUnits = if (type == DeviceType.RACK) rackUnits.toIntOrNull()?.coerceIn(1, 60) ?: DEFAULT_RACK_UNITS else null,
                            // Where it stands in a rack is set in the rack view; editing the form keeps it.
                            rackId = device?.rackId.takeIf { type != DeviceType.RACK },
                            rackU = device?.rackU.takeIf { type != DeviceType.RACK },
                            rackH = device?.rackH.takeIf { type != DeviceType.RACK },
                        ),
                    )
                },
                enabled = !busy && name.isNotBlank() && !ipError && !macError,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Asks before a cable leaves the inventory. */
@Composable
fun DeleteLinkDialog(link: Connection, names: Pair<String, String>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить кабель?") },
        text = { Text("${names.first} — ${names.second} · ${link.cableType}${link.label?.let { " · $it" }.orEmpty()}") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** A yes-or-no question before something on the map is erased. */
@Composable
fun ConfirmDialog(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
