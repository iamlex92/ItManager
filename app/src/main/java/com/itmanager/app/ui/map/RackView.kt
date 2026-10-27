package com.itmanager.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itmanager.app.data.model.DEFAULT_RACK_UNITS
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FaceplatePort
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.RackLabels
import com.itmanager.app.data.model.RackNumbering
import com.itmanager.app.data.model.RackStyle
import com.itmanager.app.data.model.RackWidth
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.tint
import com.itmanager.app.ui.theme.StatusColors

private val UNIT_HEIGHT = 30.dp
private val RAIL_WIDTH = 30.dp

/**
 * The front of a 19" rack, each device drawn over the units it occupies. Units are numbered from the bottom
 * unless the rack's style says otherwise; the gear opens its name, height and look.
 * A tap on a free unit offers the devices that stand in no rack yet; a tap on a device offers to take it out.
 */
@Composable
fun RackDialog(
    rack: Device,
    state: RackViewState,
    onMount: (deviceId: String, u: Int, h: Int) -> Unit,
    onRemove: (deviceId: String) -> Unit,
    onOpenSwitch: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Front panel drawings of the mounted devices, by device id. */
    plates: Map<String, FaceplateView> = emptyMap(),
    onEditFaceplate: (Device) -> Unit = {},
    onSaveRack: (name: String, units: Int, style: RackStyle) -> Unit = { _, _, _ -> },
) {
    val units = rack.rackUnits ?: DEFAULT_RACK_UNITS
    val style = remember(rack.metadata) { RackStyle.from(rack.metadata) }
    val frame = style.color?.let { hexColor(it) } ?: MaterialTheme.colorScheme.outline
    var settings by remember { mutableStateOf(false) }
    val members = state.devices.filter { it.rackId == rack.id && it.rackU != null }.sortedBy { it.rackU }
    val occupied = HashSet<Int>()
    members.forEach { m -> for (u in m.rackU!!..m.rackTop!!) occupied += u }
    var slot by remember { mutableStateOf<Int?>(null) }
    var picked by remember { mutableStateOf<Device?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(rack.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Занято ${occupied.size} из $units U · ${members.size} устр.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, contentDescription = "Настройки стойки") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть") }
                }
                Box(Modifier.fillMaxWidth().height(2.dp)) { if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                Text(
                    "Коснитесь свободного юнита, чтобы поставить устройство, или устройства, чтобы убрать его из стойки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Box(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .widthIn(max = style.width.maxDp.dp)
                            .fillMaxWidth()
                            .border(2.dp, frame, RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp)),
                    ) {
                        Column {
                            val order = if (style.numbering == RackNumbering.TOP) (1..units) else (units downTo 1)
                            for (u in order) UnitRow(u, free = u !in occupied, rail = frame) { slot = u }
                        }
                        members.forEach { device ->
                            val top = device.rackTop ?: return@forEach
                            // Rows run from the top of the box: from the highest unit, or from unit 1 when numbered downwards.
                            val row = if (style.numbering == RackNumbering.TOP) device.rackU!! - 1 else units - top
                            DeviceBlock(device, UNIT_HEIGHT * row, style.labels, plates[device.id]) { picked = device }
                        }
                    }
                }
            }
        }
    }

    if (settings) {
        RackSettingsDialog(
            rack = rack,
            style = style,
            lowestFree = members.maxOfOrNull { it.rackTop ?: 0 } ?: 1,
            onSave = { name, u, st ->
                settings = false
                onSaveRack(name, u, st)
            },
            onDismiss = { settings = false },
        )
    }
    slot?.let { u ->
        SlotDialog(
            unit = u,
            units = units,
            occupied = occupied,
            candidates = state.devices.filter { it.rackId == null && it.type != DeviceType.RACK },
            onPick = { device, h ->
                slot = null
                onMount(device.id, u, h)
            },
            onDismiss = { slot = null },
        )
    }
    picked?.let { device ->
        val plate = plates[device.id]
        var port by remember(device.id) { mutableStateOf<FaceplatePort?>(null) }
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(device.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (plate != null) {
                        FaceplateImage(
                            plate,
                            Modifier.fillMaxWidth().aspectRatio(plate.faceplate.aspect.coerceIn(1.5f, 30f)),
                            selectedPortId = port?.portId,
                            onPort = { port = it },
                        )
                        val p = port?.let { plate.ports[it.portId] }
                        Text(
                            when {
                                port == null -> "Коснитесь порта на схеме"
                                p == null -> port!!.portName
                                else -> listOfNotNull(
                                    p.name,
                                    p.status.label,
                                    if (p.mode == PortMode.TRUNK) "trunk ${p.taggedVlans.joinToString(",")}" else p.accessVlan?.let { "VLAN $it" },
                                    p.connectedDeviceName?.let { "→ $it" },
                                ).joinToString(" · ")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        listOfNotNull(device.type.label, device.vendor, device.model).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Юниты ${device.rackU}–${device.rackTop}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    device.comment?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (device.type == DeviceType.SWITCH) {
                        TextButton(onClick = { picked = null; onOpenSwitch(device.id) }) { Text("Открыть панель портов") }
                    }
                    TextButton(onClick = { picked = null; onEditFaceplate(device) }) {
                        Text(if (plate == null) "Добавить схему панели (SVG)" else "Схема панели и порты…")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picked = null; onRemove(device.id) }) {
                    Text("Убрать из стойки", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Закрыть") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun UnitRow(unit: Int, free: Boolean, rail: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(UNIT_HEIGHT)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .then(if (free) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(Modifier.width(RAIL_WIDTH).fillMaxHeight().background(rail.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text("$unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One device over its units, [offset] down from the top of the rack box. With [plate], its panel drawing. */
@Composable
private fun DeviceBlock(device: Device, offset: Dp, labels: RackLabels, plate: FaceplateView?, onClick: () -> Unit) {
    val height = device.rackH ?: 1
    val tint = device.type.tint
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.7f)),
        modifier = Modifier
            .padding(start = RAIL_WIDTH, end = 3.dp, top = 2.dp, bottom = 2.dp)
            .offset(y = offset)
            .fillMaxWidth()
            .height(UNIT_HEIGHT * height - 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(device.type.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (labels != RackLabels.NONE) {
                    Text(device.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (height > 1 && labels == RackLabels.NAME_MODEL) {
                    Text(
                        listOfNotNull(device.vendor, device.model).joinToString(" "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (plate != null) {
                FaceplateImage(plate, Modifier.weight(2.2f).fillMaxHeight().padding(vertical = 2.dp))
                Spacer(Modifier.width(6.dp))
            } else if (device.type == DeviceType.SWITCH || device.type == DeviceType.PATCH_PANEL) {
                PortStrip(if (device.isOnline) StatusColors.Online.copy(alpha = 0.85f) else Color.Gray.copy(alpha = 0.6f))
                Spacer(Modifier.width(8.dp))
            }
            StatusDot(if (device.isOnline) StatusColors.Online else StatusColors.Offline)
        }
    }
}

/** A front panel's two rows of twelve ports. */
@Composable
private fun PortStrip(color: Color) {
    Canvas(Modifier.size(width = 96.dp, height = 14.dp)) {
        val side = 6.dp.toPx()
        val gap = 2.dp.toPx()
        for (row in 0 until 2) {
            for (col in 0 until 12) drawRect(color, Offset(col * (side + gap), row * (side + gap)), Size(side, side))
        }
    }
}

/** Which device goes to [unit], and how many units it takes (up to the next occupied unit). */
@Composable
private fun SlotDialog(
    unit: Int,
    units: Int,
    occupied: Set<Int>,
    candidates: List<Device>,
    onPick: (Device, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val maxHeight = (1..MAX_DEVICE_UNITS).takeWhile { h -> unit + h - 1 <= units && (unit until unit + h).none { it in occupied } }.size
        .coerceAtLeast(1)
    var height by remember(unit) { mutableStateOf(1) }
    var query by remember { mutableStateOf("") }
    val shown = candidates.filter { it.matches(query) }.sortedWith(compareBy({ it.type.ordinal }, { it.name })).take(MAX_CANDIDATES)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Юнит $unit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Высота устройства", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..maxHeight).forEach { h -> FilterChip(selected = h == height, onClick = { height = h }, label = { Text("${h}U") }) }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(50) },
                    label = { Text("Поиск устройства") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    Text(
                        "Нет устройств без стойки. Добавьте устройство на карте или найдите его сканером.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(shown, key = { it.id }) { device ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(device, height) }.padding(vertical = 8.dp),
                        ) {
                            Icon(device.type.icon, contentDescription = null, tint = device.type.tint, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(device.type.label, device.model).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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

private const val MAX_DEVICE_UNITS = 6
private const val MAX_CANDIDATES = 200

private val RACK_COLORS = listOf("#8E8E93", "#0A84FF", "#30D158", "#FF9F0A", "#BF5AF2", "#FF453A", "#64D2FF")

private fun hexColor(hex: String) = Color(android.graphics.Color.parseColor(hex))

/** Name, height in units, colour, numbering direction, block labels and width of one rack. */
@Composable
private fun RackSettingsDialog(
    rack: Device,
    style: RackStyle,
    /** The highest unit a device already occupies; the rack cannot be made lower than that. */
    lowestFree: Int,
    onSave: (name: String, units: Int, style: RackStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(rack.id) { mutableStateOf(rack.name) }
    var units by rememberSaveable(rack.id) { mutableStateOf((rack.rackUnits ?: DEFAULT_RACK_UNITS).toString()) }
    var color by rememberSaveable(rack.id) { mutableStateOf(style.color) }
    var numbering by rememberSaveable(rack.id) { mutableStateOf(style.numbering) }
    var labels by rememberSaveable(rack.id) { mutableStateOf(style.labels) }
    var width by rememberSaveable(rack.id) { mutableStateOf(style.width) }
    val height = units.toIntOrNull()
    val tooLow = height != null && height < lowestFree
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки стойки") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = units,
                    onValueChange = { units = it.filter(Char::isDigit).take(2) },
                    label = { Text("Высота, U") },
                    supportingText = { if (tooLow) Text("Заняты юниты до $lowestFree: сначала уберите устройства") },
                    isError = tooLow,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Цвет рамки", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf<String?>(null) + RACK_COLORS).forEach { c ->
                        val fill = c?.let { hexColor(it) } ?: MaterialTheme.colorScheme.outline
                        Box(
                            Modifier
                                .size(30.dp)
                                .border(2.dp, if (c == color) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .padding(4.dp)
                                .background(fill, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                Text("Нумерация юнитов", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RackNumbering.entries.forEach { n -> FilterChip(selected = n == numbering, onClick = { numbering = n }, label = { Text(n.label) }) }
                }
                Text("Подписи на устройствах", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RackLabels.entries.forEach { l -> FilterChip(selected = l == labels, onClick = { labels = l }, label = { Text(l.label) }) }
                }
                Text("Ширина на экране", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RackWidth.entries.forEach { w -> FilterChip(selected = w == width, onClick = { width = w }, label = { Text(w.label) }) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = height != null && height in 1..60 && !tooLow && name.isNotBlank(),
                onClick = { onSave(name, height!!, RackStyle(color, numbering, labels, width)) },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
