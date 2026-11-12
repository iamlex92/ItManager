package com.itmanager.app.ui.map

import androidx.compose.ui.res.stringResource
import com.itmanager.app.R
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
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.runtime.remember
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.itmanager.app.data.model.AnnotationKind
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.isIpAddress
import com.itmanager.app.data.model.isMac
import com.itmanager.app.data.model.splitIps
import com.itmanager.app.data.model.FloorInfo
import com.itmanager.app.ui.components.DEVICE_TYPES_IN_ORDER
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.FormSection
import com.itmanager.app.ui.components.FormSheet

private val EditTool.icon: ImageVector
    get() = when (this) {
        EditTool.MOVE -> Icons.Outlined.OpenWith
        EditTool.LINK -> Icons.Outlined.Cable
        EditTool.DRAW -> Icons.Outlined.Draw
        EditTool.ZONES -> Icons.Outlined.MeetingRoom
        EditTool.MARKS -> Icons.Outlined.PushPin
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
    onAddFromTemplate: () -> Unit,
    onAddMark: () -> Unit,
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
    onMarkKind: (AnnotationKind) -> Unit = {},
    onMarkColor: (Long) -> Unit = {},
    onCancelArrow: () -> Unit = {},
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
            // Icons with captions: what each tool is for is readable without guessing.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EditTool.entries.forEach { tool ->
                    val selected = tool == state.tool
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(onClickLabel = tool.label) { onTool(tool) }
                            .padding(vertical = 6.dp),
                    ) {
                        Icon(
                            tool.icon,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            tool.short,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
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
                        var addMenu by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            FilledTonalButton(onClick = { addMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.map_editor_01))
                            }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.map_editor_02)) },
                                    leadingIcon = { Icon(DeviceType.WORKSTATION.icon, contentDescription = null) },
                                    onClick = { addMenu = false; onAddDevice() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.map_editor_03)) },
                                    leadingIcon = { Icon(Icons.Outlined.Dashboard, contentDescription = null) },
                                    onClick = { addMenu = false; onAddFromTemplate() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.map_editor_04)) },
                                    leadingIcon = { Icon(DeviceType.RACK.icon, contentDescription = null) },
                                    onClick = { addMenu = false; onAddRack() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.map_editor_05)) },
                                    leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                                    onClick = { addMenu = false; onAddMark() },
                                )
                            }
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
                        TextButton(onClick = onStraighten) { Text(stringResource(R.string.map_editor_06)) }
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
                        FilterChip(state.cable == CABLE_COPPER, onClick = { onCable(CABLE_COPPER) }, label = { Text(stringResource(R.string.map_editor_07)) })
                        FilterChip(state.cable == CABLE_FIBER, onClick = { onCable(CABLE_FIBER) }, label = { Text(stringResource(R.string.map_editor_08)) })
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
                            FilledTonalButton(onClick = onFinishZone, enabled = state.zoneDraft.size >= 6) { Text(stringResource(R.string.runbook_sheet_05)) }
                        }
                        selectedZoneName != null -> {
                            Text(
                                selectedZoneName.ifBlank { "Без названия" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onEditZone) { Text(stringResource(R.string.map_editor_09)) }
                            IconButton(onClick = onCancelZone) { Icon(Icons.Outlined.Close, contentDescription = "Снять выбор") }
                        }
                        else -> Text(stringResource(R.string.map_editor_10), style = MaterialTheme.typography.bodyMedium)
                    }
                    EditTool.MARKS -> if (state.arrowStart != null) {
                        Text(stringResource(R.string.map_editor_11), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = onCancelArrow) { Icon(Icons.Outlined.Close, contentDescription = "Отменить стрелку") }
                    } else {
                        AnnotationKind.entries.forEach { kind ->
                            FilterChip(
                                selected = state.markKind == kind,
                                onClick = { onMarkKind(kind) },
                                label = { Text(kind.label, maxLines = 1) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // One dot that steps through the colours: the row stays short on a narrow phone.
                        Box(
                            Modifier
                                .size(30.dp)
                                .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .padding(4.dp)
                                .background(Color(state.markColor), CircleShape)
                                .clickable {
                                    val i = MARK_COLORS.indexOf(state.markColor)
                                    onMarkColor(MARK_COLORS[(i + 1) % MARK_COLORS.size])
                                },
                        )
                    }
                    EditTool.PLAN -> if (!hasPlan) {
                        FilledTonalButton(onClick = onPickPlan, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.map_editor_12))
                        }
                    } else {
                        IconButton(onClick = { onScalePlan(1f / 1.1f) }) { Icon(Icons.Outlined.Remove, contentDescription = "Уменьшить план") }
                        IconButton(onClick = { onScalePlan(1.1f) }) { Icon(Icons.Outlined.Add, contentDescription = "Увеличить план") }
                        IconButton(onClick = onFitPlan) { Icon(Icons.Outlined.FitScreen, contentDescription = "Вписать план в этаж") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onPickPlan, enabled = !state.busy) { Text(stringResource(R.string.map_editor_13)) }
                        IconButton(onClick = onRemovePlan) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Убрать план") }
                    }
                }
            }
            if (state.tool == EditTool.LINK && selectedLinkName != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.map_editor_14), style = MaterialTheme.typography.labelLarge)
                    FilterChip(selected = selectedLinkColor == null, onClick = { onCableColor(null) }, label = { Text(stringResource(R.string.map_editor_15)) })
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

/** Devices the server reads over SNMP: switch links and traffic, printer supplies and counters. */
private val SNMP_TYPES = setOf(
    DeviceType.SWITCH, DeviceType.PRINTER, DeviceType.ROUTER, DeviceType.FIREWALL, DeviceType.ACCESS_POINT, DeviceType.UPS,
)

/** The switch's SSH login for the server's real driver; «По умолчанию» is SWITCH_CREDENTIAL on the server. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SshLoginPicker(selected: String?, onPick: (String?) -> Unit) {
    val viewModel: SshLoginsViewModel = hiltViewModel()
    val logins by viewModel.logins.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    Text(stringResource(R.string.map_editor_16), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == null, onClick = { onPick(null) }, label = { Text(stringResource(R.string.map_editor_17)) })
        logins.forEach { c ->
            FilterChip(selected = c.id == selected, onClick = { onPick(c.id) }, label = { Text(c.name) })
        }
    }
    Text(
        "Нужен, когда сервер работает с настоящими коммутаторами (HAL_MODE=real). Логины — в «Ещё → Учётные данные».",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Objects with no address of their own: no «Сеть» section in the form. */
private val PASSIVE_FORM_TYPES = setOf(DeviceType.RACK, DeviceType.SOCKET, DeviceType.PATCH_PANEL)


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
    var extraIps by rememberSaveable(key) { mutableStateOf(device?.extraIps.orEmpty().joinToString(", ")) }
    var mac by rememberSaveable(key) { mutableStateOf(device?.mac.orEmpty()) }
    var vendor by rememberSaveable(key) { mutableStateOf(device?.vendor.orEmpty()) }
    var model by rememberSaveable(key) { mutableStateOf(device?.model.orEmpty()) }
    var hostId by rememberSaveable(key) { mutableStateOf(device?.hostId) }
    var comment by rememberSaveable(key) { mutableStateOf(device?.comment.orEmpty()) }
    var rackUnits by rememberSaveable(key) { mutableStateOf((device?.rackUnits ?: DEFAULT_RACK_UNITS).toString()) }
    var sshLogin by rememberSaveable(key) { mutableStateOf(device?.sshCredentialId) }
    var snmpCommunity by rememberSaveable(key) { mutableStateOf("") }
    val ipError = ip.isNotBlank() && !isIpAddress(ip)
    val badExtraIp = splitIps(extraIps).firstOrNull { !isIpAddress(it) }
    val macError = mac.isNotBlank() && !isMac(mac)
    var triedSave by rememberSaveable(key) { mutableStateOf(false) }
    // The server said the address is taken (ip_in_use): pressing Save again saves it anyway.
    var allowDuplicateIp by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(form.error) { if (form.error?.startsWith("Адрес ") == true && form.error.contains("уже занят")) allowDuplicateIp = true }
    val nameError = triedSave && name.isBlank()

    FormSheet(
        title = if (device == null) "Новое устройство" else device.name,
        onDismiss = onDismiss,
        onSave = {
            triedSave = true
            if (name.isBlank() || ipError || macError || badExtraIp != null) return@FormSheet
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
                    sshCredentialId = sshLogin.takeIf { type == DeviceType.SWITCH },
                    snmpCommunity = snmpCommunity.trim().takeIf { it.isNotEmpty() && type in SNMP_TYPES },
                    extraIps = if (type in PASSIVE_FORM_TYPES) emptyList() else splitIps(extraIps),
                    allowDuplicateIp = allowDuplicateIp,
                ),
            )
        },
        busy = busy,
        error = form.error,
        deleteLabel = device?.let { "Удалить из учёта" },
        deleteQuestion = device?.let {
            "Удалить ${it.name}?" to "Устройство, его порты и кабели пропадут из учёта и с карты. Само оборудование это не затрагивает."
        },
        onDelete = device?.let { d -> { onDelete(d.id) } },
    ) {
        FormSection("Что это") {
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
                label = { Text(stringResource(R.string.gpo_screen_04)) },
                placeholder = { Text(stringResource(R.string.map_editor_18)) },
                isError = nameError,
                supportingText = if (nameError) ({ Text(stringResource(R.string.map_editor_19)) }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vendor,
                    onValueChange = { vendor = it.take(50) },
                    label = { Text(stringResource(R.string.bulk_edit_dialog_05)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it.take(100) },
                    label = { Text(stringResource(R.string.map_editor_20)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (type !in PASSIVE_FORM_TYPES) {
            FormSection("Сеть") {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.take(45) },
                    label = { Text(stringResource(R.string.map_editor_21)) },
                    isError = ipError,
                    supportingText = if (ipError) ({ Text(stringResource(R.string.map_editor_22)) }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = extraIps,
                    onValueChange = { extraIps = it.take(600) },
                    label = { Text(stringResource(R.string.map_editor_23)) },
                    placeholder = { Text("10.0.1.1, 192.168.82.1") },
                    isError = badExtraIp != null,
                    supportingText = {
                        Text(badExtraIp?.let { "«$it» — не IP-адрес" } ?: "Другие интерфейсы того же устройства: их тоже пингуем и находим поиском.")
                    },
                    singleLine = true,
                    // Commas between addresses: the plain keyboard, not the number pad.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it.take(17) },
                    label = { Text(stringResource(R.string.map_editor_24)) },
                    placeholder = { Text("aa:bb:cc:dd:ee:ff") },
                    isError = macError,
                    supportingText = if (macError) ({ Text(stringResource(R.string.map_editor_25)) }) else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (type in SNMP_TYPES) {
                    OutlinedTextField(
                        value = snmpCommunity,
                        onValueChange = { snmpCommunity = it.take(50) },
                        label = { Text(stringResource(R.string.map_editor_26)) },
                        placeholder = {
                            Text(
                                when {
                                    device == null -> "public"
                                    device.hasSnmpCommunity -> "задано — оставьте пустым, чтобы не менять"
                                    else -> "не задано"
                                },
                            )
                        },
                        supportingText = { Text(stringResource(R.string.map_editor_27)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (type == DeviceType.SWITCH) SshLoginPicker(sshLogin) { sshLogin = it }
            }
        }
        val hostChoices = hosts.filter { it.id != device?.id }
        FormSection("Где стоит") {
            if (type == DeviceType.RACK) {
                OutlinedTextField(
                    value = rackUnits,
                    onValueChange = { rackUnits = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.map_editor_28)) },
                    supportingText = { Text(stringResource(R.string.map_editor_29)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (device != null && device.type == DeviceType.RACK && onOpenRack != null) {
                    FilledTonalButton(onClick = { onOpenRack(device.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.map_editor_30))
                    }
                }
            }
            if (type == DeviceType.VIRTUAL) {
                Text(stringResource(R.string.bulk_edit_dialog_03), style = MaterialTheme.typography.labelLarge)
                if (hostChoices.isEmpty()) {
                    Text(stringResource(R.string.map_editor_31), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = hostId == null, onClick = { hostId = null }, label = { Text(stringResource(R.string.map_editor_32)) })
                    hostChoices.forEach { h ->
                        FilterChip(selected = h.id == hostId, onClick = { hostId = h.id }, label = { Text(h.name) })
                    }
                }
            }
            if (floors.size > 1) {
                Text(stringResource(R.string.bulk_edit_dialog_04), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    floors.forEach { f ->
                        FilterChip(selected = f.id == form.floorId, onClick = { onFloor(f.id) }, label = { Text(f.title) })
                    }
                }
            } else if (type != DeviceType.RACK && type != DeviceType.VIRTUAL) {
                Text(
                    "Место на плане задаётся перетаскиванием на карте в режиме «Правка».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FormSection("Заметки") {
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it.take(4000) },
                label = { Text(stringResource(R.string.map_editor_33)) },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Asks before a cable leaves the inventory. */
@Composable
fun DeleteLinkDialog(link: Connection, names: Pair<String, String>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_editor_34)) },
        text = { Text("${names.first} — ${names.second} · ${link.cableType}${link.label?.let { " · $it" }.orEmpty()}") },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.form_sheet_01), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.form_sheet_02)) } },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.form_sheet_02)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
