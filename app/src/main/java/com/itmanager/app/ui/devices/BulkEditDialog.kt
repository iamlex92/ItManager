package com.itmanager.app.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itmanager.app.data.model.DeviceBulkChange
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.ui.components.DEVICE_TYPES_IN_ORDER
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.tint

/**
 * One change for every chosen device: a type, a floor, a vendor or a VM host. Whatever is left
 * untouched stays as it is on each device. Inventory only: no device is contacted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkEditDialog(count: Int, state: BulkEditState, onApply: (DeviceBulkChange) -> Unit, onDismiss: () -> Unit) {
    var type by rememberSaveable { mutableStateOf<DeviceType?>(null) }
    var floorId by rememberSaveable { mutableStateOf<String?>(null) }
    var vendor by rememberSaveable { mutableStateOf("") }
    var hostId by rememberSaveable { mutableStateOf<String?>(null) }
    val change = DeviceBulkChange(
        type = type,
        floorId = floorId,
        vendor = vendor.trim().ifEmpty { null },
        hostId = hostId.takeIf { type == DeviceType.VIRTUAL },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить устройства: $count") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Отмеченное поменяется у всех выбранных, остальное останется как есть.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Категория", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = type == null, onClick = { type = null }, label = { Text("Не менять") })
                    DEVICE_TYPES_IN_ORDER.forEach { t ->
                        FilterChip(
                            selected = t == type,
                            onClick = { type = t },
                            label = { Text(t.label) },
                            leadingIcon = { Icon(t.icon, contentDescription = null, tint = t.tint, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
                if (type == DeviceType.VIRTUAL && state.hosts.isNotEmpty()) {
                    Text("Хост виртуализации", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = hostId == null, onClick = { hostId = null }, label = { Text("Не менять") })
                        state.hosts.forEach { h ->
                            FilterChip(selected = h.id == hostId, onClick = { hostId = h.id }, label = { Text(h.name) })
                        }
                    }
                }
                if (state.floors.size > 1) {
                    Text("Этаж", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = floorId == null, onClick = { floorId = null }, label = { Text("Не менять") })
                        state.floors.forEach { f ->
                            FilterChip(selected = f.id == floorId, onClick = { floorId = f.id }, label = { Text(f.title) })
                        }
                    }
                }
                OutlinedTextField(
                    value = vendor,
                    onValueChange = { vendor = it.take(50) },
                    label = { Text("Производитель") },
                    placeholder = { Text("Пусто: не менять") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(change) }, enabled = !state.busy && !change.isEmpty) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
