package com.itmanager.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.Zone
import com.itmanager.app.data.model.roomAt
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.tint

/** A zone being named: [isNew] for an outline just finished, false for changes to a stored one. */
data class ZoneEdit(val zone: Zone, val isNew: Boolean)

val ZONE_COLORS = listOf(0xFF0A84FFL, 0xFF30D158L, 0xFFFF9F0AL, 0xFFBF5AF2L, 0xFFFF453AL, 0xFF64D2FFL)

/** Names a zone, marks it as a room (or not) and picks its colour. */
@Composable
fun ZoneDialog(edit: ZoneEdit, onSave: (Zone) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    val key = edit.zone.id
    var name by rememberSaveable(key) { mutableStateOf(edit.zone.name) }
    var isRoom by rememberSaveable(key) { mutableStateOf(edit.zone.isRoom) }
    var color by rememberSaveable(key) { mutableStateOf(edit.zone.colorArgb) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit.isNew) "Новая зона" else "Зона на карте") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text("Название") },
                    placeholder = { Text("Например, Серверная") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Это помещение", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Устройства внутри зоны попадут в список помещения.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = isRoom, onCheckedChange = { isRoom = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZONE_COLORS.forEach { c ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .border(2.dp, if (c == color) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .padding(4.dp)
                                .background(Color(c), CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                if (!edit.isNew) {
                    TextButton(onClick = { onDelete(edit.zone.id) }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Удалить зону", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(edit.zone.copy(name = name.trim(), isRoom = isRoom, colorArgb = color)) },
                enabled = name.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Rooms of the floor with the devices whose icon lies inside each; the rest are listed apart. */
@Composable
fun RoomsDialog(zones: List<Zone>, devices: List<Device>, onPick: (Device) -> Unit, onDismiss: () -> Unit) {
    val rooms = zones.filter { it.isRoom }
    val byRoom = devices.groupBy { zones.roomAt(it.x, it.y)?.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Помещения этажа") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rooms.forEach { room ->
                    val inside = byRoom[room.id].orEmpty().sortedBy { it.name }
                    item(key = "room-${room.id}") { RoomHeader(room.name, inside.size, Color(room.colorArgb)) }
                    items(inside, key = { "${room.id}-${it.id}" }) { RoomDevice(it, onPick) }
                    if (inside.isEmpty()) {
                        item(key = "empty-${room.id}") {
                            Text(
                                "Пока пусто",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
                val outside = byRoom[null].orEmpty().sortedBy { it.name }
                if (outside.isNotEmpty()) {
                    item(key = "outside") { RoomHeader("Вне помещений", outside.size, MaterialTheme.colorScheme.outline) }
                    items(outside, key = { "outside-${it.id}" }) { RoomDevice(it, onPick) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun RoomHeader(name: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RoomDevice(device: Device, onPick: (Device) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onPick(device) }.padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Icon(device.type.icon, contentDescription = null, tint = device.type.tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(device.type.label, device.ip).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The note on a device card. [error] is the server's refusal of the last save. */
@Composable
fun CommentDialog(device: Device, busy: Boolean, error: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(device.id) { mutableStateOf(device.comment.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Комментарий · ${device.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(4000) },
                    placeholder = { Text("Заметка об устройстве: куда включено, что менялось, кому звонить") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = !busy) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
