package com.itmanager.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.itmanager.app.data.model.Annotation
import com.itmanager.app.data.model.AnnotationKind

/** A room number, a caption or the caption of an arrow: its text, colour and size. */
@Composable
fun MarkDialog(edit: MarkEdit, onSave: (Annotation) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    val a = edit.annotation
    var text by rememberSaveable(a.id) { mutableStateOf(a.text) }
    // A number and a caption can turn into each other; an arrow stays an arrow.
    var kind by rememberSaveable(a.id) { mutableStateOf(a.kind) }
    var color by rememberSaveable(a.id) { mutableLongStateOf(a.colorArgb) }
    var size by rememberSaveable(a.id) { mutableFloatStateOf(a.size) }
    val needsText = kind != AnnotationKind.ARROW
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    !edit.isNew -> "Метка"
                    kind == AnnotationKind.BADGE -> "Номер кабинета"
                    kind == AnnotationKind.TEXT -> "Подпись"
                    else -> "Стрелка"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (kind != AnnotationKind.ARROW) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AnnotationKind.BADGE, AnnotationKind.TEXT).forEach { k ->
                            FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(if (kind == AnnotationKind.BADGE) 8 else 100) },
                    label = {
                        Text(
                            when (kind) {
                                AnnotationKind.BADGE -> "Номер, например 214"
                                AnnotationKind.TEXT -> "Текст, например «Склад» или «Вход»"
                                AnnotationKind.ARROW -> "Подпись у стрелки, если нужна"
                            },
                        )
                    },
                    singleLine = kind != AnnotationKind.TEXT,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Цвет", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MARK_COLORS.forEach { c ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .border(2.dp, if (c == color) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .padding(4.dp)
                                .background(Color(c), CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                Text("Размер", style = MaterialTheme.typography.labelLarge)
                Slider(value = size, onValueChange = { size = it }, valueRange = 0.5f..3f)
                if (!edit.isNew) {
                    TextButton(onClick = { onDelete(a.id) }) { Text("Удалить метку", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(a.copy(kind = kind, text = text.trim(), colorArgb = color, size = (size * 10).toInt() / 10f)) },
                enabled = !needsText || text.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
