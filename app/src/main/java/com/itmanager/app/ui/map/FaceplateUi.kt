package com.itmanager.app.ui.map

import android.graphics.Picture
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.FaceplatePort
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.tint
import com.itmanager.app.ui.switchdetail.color

/** Where a drawing of [aspect] (width / height) lies when fitted into [size], centred. */
private fun fitRect(size: Size, aspect: Float): Rect {
    if (size.width <= 0f || size.height <= 0f || aspect <= 0f) return Rect.Zero
    return if (size.width / size.height > aspect) {
        val w = size.height * aspect
        Rect(Offset((size.width - w) / 2f, 0f), Size(w, size.height))
    } else {
        val h = size.width / aspect
        Rect(Offset(0f, (size.height - h) / 2f), Size(size.width, h))
    }
}

private fun DrawScope.drawPictureIn(picture: Picture, rect: Rect) {
    if (picture.width <= 0 || picture.height <= 0 || rect.width <= 0f) return
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val saved = native.save()
        native.clipRect(rect.left, rect.top, rect.right, rect.bottom)
        native.translate(rect.left, rect.top)
        native.scale(rect.width / picture.width, rect.height / picture.height)
        native.drawPicture(picture)
        native.restoreToCount(saved)
    }
}

/**
 * A device's front panel: the drawing with each marked port coloured by its link state, like the port panel.
 * [onPort] makes the marks tappable.
 */
@Composable
fun FaceplateImage(
    view: FaceplateView,
    modifier: Modifier = Modifier,
    selectedPortId: String? = null,
    onPort: ((FaceplatePort) -> Unit)? = null,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val marks = view.faceplate.ports
    Canvas(
        modifier.then(
            if (onPort == null) {
                Modifier
            } else {
                Modifier.pointerInput(view) {
                    detectTapGestures { tap ->
                        val r = fitRect(Size(size.width.toFloat(), size.height.toFloat()), view.faceplate.aspect)
                        if (r.width <= 0f) return@detectTapGestures
                        val fx = (tap.x - r.left) / r.width
                        val fy = (tap.y - r.top) / r.height
                        // The nearest mark within a finger's reach: ports on a panel are tiny.
                        val slack = 0.02f
                        marks.filter { fx in (it.x - slack)..(it.x + it.w + slack) && fy in (it.y - slack * 4)..(it.y + it.h + slack * 4) }
                            .minByOrNull { (it.centerX - fx) * (it.centerX - fx) + (it.centerY - fy) * (it.centerY - fy) }
                            ?.let(onPort)
                    }
                }
            },
        ),
    ) {
        val r = fitRect(size, view.faceplate.aspect)
        drawPictureIn(view.picture, r)
        for (m in marks) {
            val port = view.ports[m.portId]
            val topLeft = Offset(r.left + m.x * r.width, r.top + m.y * r.height)
            val markSize = Size(m.w * r.width, m.h * r.height)
            val color = port?.status?.color ?: Color.Gray
            drawRect(color.copy(alpha = 0.75f), topLeft, markSize)
            if (m.portId == selectedPortId) {
                drawRect(highlight, topLeft - Offset(2f, 2f), Size(markSize.width + 4f, markSize.height + 4f), style = Stroke(3f))
            }
        }
    }
}

/**
 * Sets up a device's panel drawing: upload or copy an SVG, then mark where each port is.
 * Pick a port below, tap its place on the drawing; the next port is picked by itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FaceplateEditorDialog(
    state: FaceplateEditorState,
    viewModel: FaceplateViewModel,
) {
    val pickSvg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::upload) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pickSource by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (state.dirty) {
            confirmClose = true
        } else {
            viewModel.closeEditor()
        }
    }
    val device = state.device

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp)) {
                    Icon(device.type.icon, contentDescription = null, tint = device.type.tint)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Схема панели", style = MaterialTheme.typography.titleLarge)
                        Text(
                            listOfNotNull(device.name, device.model).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть") }
                }
                Box(Modifier.fillMaxWidth().height(2.dp)) { if (state.busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                val view = state.view
                if (state.loading) return@Column
                if (view == null) {
                    NoDrawing(
                        hasSources = state.copySources.isNotEmpty(),
                        busy = state.busy,
                        onUpload = { pickSvg.launch(arrayOf("image/svg+xml", "text/xml", "application/xml", "*/*")) },
                        onCopy = { pickSource = true },
                    )
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text("Масштаб", style = MaterialTheme.typography.labelLarge)
                            listOf(1f, 2f, 4f, 8f).forEach { z ->
                                FilterChip(selected = zoom == z, onClick = { zoom = z }, label = { Text(if (z == 1f) "Вписать" else "×${z.toInt()}") })
                            }
                        }
                        MarkCanvas(state, view, zoom, onTap = viewModel::tap)
                        Text(
                            state.selectedPort?.let { p ->
                                if (p.id in state.marks) "Порт ${p.name} отмечен. Коснитесь другого места, чтобы переставить его, или выберите другой порт."
                                else "Коснитесь схемы там, где порт ${p.name}."
                            } ?: "Выберите порт ниже.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text("Порядок", style = MaterialTheme.typography.labelLarge)
                            FilterChip(selected = state.step == 1, onClick = { viewModel.setStep(1) }, label = { Text("Подряд") })
                            FilterChip(selected = state.step == 2, onClick = { viewModel.setStep(2) }, label = { Text("Через один") })
                        }
                        SizeSlider("Ширина метки", state.markW, 0.004f..0.12f) { viewModel.setMarkSize(it, state.markH) }
                        SizeSlider("Высота метки", state.markH, 0.04f..1f) { viewModel.setMarkSize(state.markW, it) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                            OutlinedButton(onClick = viewModel::continueRow, enabled = state.selectedPort != null) { Text("Продолжить ряд") }
                            OutlinedButton(onClick = viewModel::removeMark, enabled = state.selectedPortId in state.marks) { Text("Убрать метку") }
                            TextButton(onClick = viewModel::clearMarks, enabled = state.marks.isNotEmpty()) { Text("Снять все") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Порты · отмечено ${state.marks.size} из ${state.ports.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        if (state.ports.isEmpty()) {
                            Text(
                                "У устройства нет портов в учёте. Добавьте их импортом конфигурации или сканером сети.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(16.dp),
                        ) {
                            state.ports.forEach { p -> PortChip(p, marked = p.id in state.marks, selected = p.id == state.selectedPortId) { viewModel.selectPort(p.id) } }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        TextButton(onClick = { pickSvg.launch(arrayOf("image/svg+xml", "text/xml", "application/xml", "*/*")) }, enabled = !state.busy) { Text("Заменить SVG") }
                        TextButton(onClick = { confirmDelete = true }, enabled = !state.busy) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = viewModel::saveMarks, enabled = state.dirty && !state.busy) { Text("Сохранить") }
                    }
                }
            }
        }
    }

    if (pickSource) {
        SourcePicker(state.copySources, onPick = { pickSource = false; viewModel.copyFrom(it) }, onDismiss = { pickSource = false })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить схему панели?") },
            text = { Text("Схема и все метки портов ${device.name} будут удалены. Сами порты и устройство не меняются.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteDrawing() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Метки не сохранены") },
            text = { Text("Закрыть без сохранения? Расставленные метки пропадут.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; viewModel.closeEditor() }) { Text("Закрыть", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Остаться") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun NoDrawing(hasSources: Boolean, busy: Boolean, onUpload: () -> Unit, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Загрузите схему лицевой панели в SVG (её можно взять с сайта производителя или нарисовать в Inkscape), " +
                "затем отметьте на ней порты. В стойке панель будет показана с портами, окрашенными по состоянию линка.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onUpload, enabled = !busy) {
            Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Загрузить SVG")
        }
        if (hasSources) {
            OutlinedButton(onClick = onCopy, enabled = !busy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Скопировать с такого же устройства")
            }
        }
    }
}

/** The drawing at [zoom] times the screen width, scrolled sideways, with the marks being placed. */
@Composable
private fun MarkCanvas(state: FaceplateEditorState, view: FaceplateView, zoom: Float, onTap: (Float, Float) -> Unit) {
    val measurer = rememberTextMeasurer()
    val highlight = MaterialTheme.colorScheme.primary
    val markColor = Color(0xFF64D2FF)
    val labelStyle = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val width = maxWidth * zoom
        val height = (width / view.faceplate.aspect).coerceIn(48.dp, 420.dp)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Canvas(
                Modifier
                    .size(width, height)
                    .background(Color(0xFF15191F), RoundedCornerShape(6.dp))
                    .pointerInput(view, zoom) {
                        detectTapGestures { tap ->
                            val r = fitRect(Size(size.width.toFloat(), size.height.toFloat()), view.faceplate.aspect)
                            if (r.width <= 0f) return@detectTapGestures
                            val fx = (tap.x - r.left) / r.width
                            val fy = (tap.y - r.top) / r.height
                            if (fx in 0f..1f && fy in 0f..1f) onTap(fx, fy)
                        }
                    },
            ) {
                val r = fitRect(size, view.faceplate.aspect)
                drawPictureIn(view.picture, r)
                val showLabels = with(density) { (r.width * 0.02f).toDp() } >= 14.dp
                for (m in state.marks.values) {
                    val topLeft = Offset(r.left + m.x * r.width, r.top + m.y * r.height)
                    val s = Size(m.w * r.width, m.h * r.height)
                    val selected = m.portId == state.selectedPortId
                    drawRect((if (selected) highlight else markColor).copy(alpha = 0.45f), topLeft, s)
                    drawRect(if (selected) highlight else markColor, topLeft, s, style = Stroke(if (selected) 3f else 1.5f))
                    if (showLabels) {
                        val label = measurer.measure(m.portNumber.toString(), labelStyle)
                        drawText(label, topLeft = Offset(topLeft.x + (s.width - label.size.width) / 2f, topLeft.y + (s.height - label.size.height) / 2f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SizeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(110.dp))
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PortChip(port: SwitchPort, marked: Boolean, selected: Boolean, onClick: () -> Unit) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary
        marked -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        marked -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text((if (marked) "✓ " else "") + port.name, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
private fun SourcePicker(sources: List<Device>, onPick: (Device) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Скопировать схему с…") },
        text = {
            Column {
                Text(
                    "Схема и метки переносятся, порты сопоставляются по имени, затем по номеру. Подойдёт устройство той же модели.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(sources, key = { it.id }) { d ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 8.dp),
                        ) {
                            Icon(d.type.icon, contentDescription = null, tint = d.type.tint, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.name, style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(d.vendor, d.model).joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
