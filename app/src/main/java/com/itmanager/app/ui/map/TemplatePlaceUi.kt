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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itmanager.app.data.model.DeviceTemplate
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.tint

/**
 * «+ Из шаблона» on the map: pick a template, name the device, and it lands on the floor with the template's
 * type, model and ports. [onCreate] reports back through its callback: null on success or the error.
 */
@Composable
fun TemplatePlaceDialog(
    templates: List<DeviceTemplate>,
    busy: Boolean,
    onCreate: (template: DeviceTemplate, name: String, ip: String?, done: (String?) -> Unit) -> Unit,
    onManageTemplates: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf<DeviceTemplate?>(null) }
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val template = picked

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(template?.name ?: "Устройство из шаблона") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (template == null) {
                    if (templates.isEmpty()) {
                        Text(
                            "Шаблонов пока нет. Шаблон хранит тип, модель и порты типового устройства.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onManageTemplates) { Text("Создать шаблон") }
                    }
                    templates.forEach { t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = t
                                    error = null
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Icon(t.type.icon, contentDescription = null, tint = t.type.tint, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(t.vendor, t.model, t.portCount.takeIf { it > 0 }?.let { "$it портов" }).joinToString(" · ")
                                        .ifEmpty { t.type.label },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(100); error = null },
                        label = { Text("Имя устройства") },
                        placeholder = { Text("SW-2-01") },
                        singleLine = true,
                        isError = error != null && name.isBlank(),
                        supportingText = if (name.isBlank() && error != null) ({ Text("Укажите имя") }) else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it.trim().take(45) },
                        label = { Text("IP-адрес, если есть") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Встанет в центр экрана, порты создадутся по шаблону. Потом его можно перетащить.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.takeIf { name.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (template != null) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        if (name.isBlank()) {
                            error = "Укажите имя"
                            return@TextButton
                        }
                        onCreate(template, name.trim(), ip.ifBlank { null }) { e ->
                            error = e
                            if (e == null) onDismiss()
                        }
                    },
                ) { Text("Добавить") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (template != null) picked = null else onDismiss() }) {
                Text(if (template != null) "Назад" else "Отмена")
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
