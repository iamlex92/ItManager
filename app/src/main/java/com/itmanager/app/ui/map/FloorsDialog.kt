package com.itmanager.app.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itmanager.app.data.model.FloorDraft
import com.itmanager.app.data.model.FloorInfo

/**
 * The building's floors, top to bottom as on a lift panel: open one, rename it, change its place,
 * add a new one or remove an empty one. Each floor keeps its own plan, objects and drawings.
 */
@Composable
fun FloorsDialog(
    floors: List<FloorInfo>,
    currentId: String?,
    busy: Boolean,
    error: String?,
    onOpen: (String) -> Unit,
    onCreate: (FloorDraft) -> Unit,
    onRename: (String, FloorDraft) -> Unit,
    onMove: (String, up: Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // null: the list; a FloorInfo: editing it; NEW: adding one.
    var editing by remember { mutableStateOf<FloorInfo?>(null) }
    var confirmDelete by remember { mutableStateOf<FloorInfo?>(null) }

    editing?.let { floor ->
        FloorEditDialog(
            floor = floor.takeIf { it !== NEW },
            suggested = FloorDraft(
                building = floors.lastOrNull()?.building ?: "Здание",
                number = (floors.maxOfOrNull { it.number } ?: 0) + 1,
                name = null,
            ),
            onSave = { draft ->
                if (floor === NEW) onCreate(draft) else onRename(floor.id, draft)
                editing = null
            },
            onDismiss = { editing = null },
        )
        return
    }
    confirmDelete?.let { floor ->
        ConfirmDialog(
            title = "Удалить «${floor.title}»?",
            text = "Удаляется только пустой этаж: план, линии карандаша и сам этаж. Устройства сначала перенесите на другой этаж.",
            action = "Удалить",
            onConfirm = {
                onDelete(floor.id)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Этажи") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val top = floors.reversed()
                top.forEachIndexed { i, floor ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(floor.id) }
                            .padding(vertical = 2.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                floor.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (floor.id == currentId) FontWeight.Bold else null,
                                color = if (floor.id == currentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${floor.building} · № ${floor.number}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onMove(floor.id, true) }, enabled = !busy && i > 0) {
                            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Выше")
                        }
                        IconButton(onClick = { onMove(floor.id, false) }, enabled = !busy && i < top.size - 1) {
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Ниже")
                        }
                        IconButton(onClick = { editing = floor }, enabled = !busy) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Переименовать")
                        }
                        IconButton(onClick = { confirmDelete = floor }, enabled = !busy && floors.size > 1) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить этаж")
                        }
                    }
                }
                if (floors.isEmpty()) {
                    Text(
                        "Этажей пока нет: первый появится вместе с первым объектом на карте, или добавьте его здесь.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                error?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = { editing = NEW }, enabled = !busy) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить этаж")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Marks "add a new floor" in the dialog state. */
private val NEW = FloorInfo("", "", 0, null)

@Composable
private fun FloorEditDialog(floor: FloorInfo?, suggested: FloorDraft, onSave: (FloorDraft) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(floor?.name.orEmpty()) }
    var building by remember { mutableStateOf(floor?.building ?: suggested.building) }
    var number by remember { mutableStateOf((floor?.number ?: suggested.number).toString()) }
    val parsed = number.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (floor == null) "Новый этаж" else "Этаж") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text("Название") },
                    placeholder = { Text("Например, 2 этаж или Подвал") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = building,
                    onValueChange = { building = it.take(100) },
                    label = { Text("Здание") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it.take(4) },
                    label = { Text("Номер этажа") },
                    supportingText = { Text("Задаёт порядок: подвал 0 или -1, первый этаж 1.") },
                    isError = parsed == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(FloorDraft(building.trim(), parsed ?: 0, name.trim().ifEmpty { null })) },
                enabled = parsed != null && building.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
