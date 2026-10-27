package com.itmanager.app.ui.switchdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itmanager.app.data.model.PortDraft
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.ui.components.StatusDot

/** The port being edited in the inventory form; [port] null adds a new one numbered [nextNumber]. */
data class PortRecordTarget(val port: SwitchPort?, val nextNumber: Int)

private val SPEEDS = listOf(10, 100, 1000, 2500, 10000)

/**
 * Edits what the inventory records about a port: name, number, mode, VLANs, speed, PoE, uplink.
 * Saving only changes the database; the switch is never contacted (that is «Настройка порта»).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortRecordDialog(
    target: PortRecordTarget,
    vlans: List<Vlan>,
    busy: Boolean,
    onSave: (PortDraft) -> Unit,
    onDelete: (SwitchPort) -> Unit,
    onDismiss: () -> Unit,
) {
    val port = target.port
    val key = port?.id ?: "new"
    var number by rememberSaveable(key) { mutableStateOf((port?.number ?: target.nextNumber).toString()) }
    var name by rememberSaveable(key) { mutableStateOf(port?.name ?: "port${target.nextNumber}") }
    var uplink by rememberSaveable(key) { mutableStateOf(port?.isUplink ?: false) }
    var mode by rememberSaveable(key) { mutableStateOf(port?.mode ?: PortMode.ACCESS) }
    var access by rememberSaveable(key) { mutableStateOf(port?.accessVlan) }
    var tagged by rememberSaveable(key) { mutableStateOf(port?.taggedVlans.orEmpty().joinToString(",")) }
    var speed by rememberSaveable(key) { mutableStateOf(port?.speedMbps ?: 1000) }
    var poe by rememberSaveable(key) { mutableStateOf(port?.poeEnabled ?: false) }
    var confirmDelete by rememberSaveable(key) { mutableStateOf(false) }
    val taggedIds = tagged.split(",").mapNotNull { it.trim().toIntOrNull() }.distinct().sorted()

    if (confirmDelete && port != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить запись о порте ${port.name}?") },
            text = { Text("Порт и его кабели пропадут из базы. Сам коммутатор это не затрагивает.") },
            confirmButton = {
                TextButton(onClick = { onDelete(port) }, enabled = !busy) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (port == null) "Новый порт" else "Запись о порте") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Меняется только база данных. На коммутатор ничего не отправляется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it.filter(Char::isDigit).take(4) },
                        label = { Text("Номер") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(50) },
                        label = { Text("Имя") },
                        placeholder = { Text("Gi1/0/5") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PortMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(i, PortMode.entries.size),
                            label = { Text(m.label) },
                        )
                    }
                }
                if (mode == PortMode.ACCESS) {
                    Text("Access VLAN", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = access == null, onClick = { access = null }, label = { Text("Нет") })
                        vlans.forEach { v ->
                            FilterChip(
                                selected = access == v.id,
                                onClick = { access = v.id },
                                label = { Text("${v.id} · ${v.name}") },
                                leadingIcon = { StatusDot(Color(v.colorArgb), size = 10.dp) },
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = tagged,
                        onValueChange = { tagged = it.filter { c -> c.isDigit() || c == ',' || c == ' ' }.take(200) },
                        label = { Text("Tagged VLAN через запятую") },
                        placeholder = { Text("10,20,99") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Скорость порта", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SPEEDS.forEach { s ->
                        FilterChip(selected = speed == s, onClick = { speed = s }, label = { Text(speedLabel(s)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PoE", Modifier.weight(1f))
                    Switch(checked = poe, onCheckedChange = { poe = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Аплинк", Modifier.weight(1f))
                    Switch(checked = uplink, onCheckedChange = { uplink = it })
                }
                if (port != null) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Удалить запись о порте", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        PortDraft(
                            number = number.toIntOrNull() ?: 0,
                            name = name.trim(),
                            isUplink = uplink,
                            mode = mode,
                            accessVlan = access.takeIf { mode == PortMode.ACCESS },
                            taggedVlans = if (mode == PortMode.TRUNK) taggedIds else emptyList(),
                            speedMbps = speed,
                            poeEnabled = poe,
                        ),
                    )
                },
                enabled = !busy && name.isNotBlank() && number.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

private fun speedLabel(mbps: Int) = when {
    mbps < 1000 -> "$mbps Мбит/с"
    mbps % 1000 == 0 -> "${mbps / 1000} Гбит/с"
    else -> "${mbps / 1000f} Гбит/с"
}
