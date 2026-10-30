package com.itmanager.app.ui.serverconfig

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.core.security.BiometricGate
import com.itmanager.app.core.security.GateResult
import com.itmanager.app.core.security.findFragmentActivity
import com.itmanager.app.data.model.Credential
import com.itmanager.app.data.model.CredentialKind
import com.itmanager.app.data.model.ServerConfigField
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.TabTopBar
import com.itmanager.app.ui.components.UiState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * «Настройки сервера»: switch control, monitoring and SNMP, domain computers, backups — everything that
 * used to be edited in netadmin.env. Saving is confirmed with biometrics; values act after the server restarts.
 */
@Composable
fun ServerConfigScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ServerConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val edits by viewModel.edits.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val restarting by viewModel.restarting.collectAsStateWithLifecycle()
    val certificate by viewModel.certificate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askRestart by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    fun confirmAndSave() {
        scope.launch {
            BiometricGate.unavailableReason(context)?.let { return@launch viewModel.fail(it) }
            val activity = context.findFragmentActivity() ?: return@launch viewModel.fail("Не удалось показать окно подтверждения.")
            when (val gate = BiometricGate.confirm(activity, "Настройки сервера", "Подтвердите изменение настроек")) {
                is GateResult.Confirmed -> viewModel.save()
                GateResult.Cancelled -> Unit
                is GateResult.Failed -> viewModel.fail("Подтверждение не прошло: ${gate.message}")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabTopBar(title = "Настройки сервера", subtitle = "Вместо правки netadmin.env", onBack = onBack) {
            TextButton(onClick = ::confirmAndSave, enabled = !edits.isEmpty && !busy) { Text("Сохранить") }
        }
        if (busy || restarting) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (val s = state) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(s.message, viewModel::load, onOpenSettings)
            is UiState.Content -> {
                val config = s.data
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "head") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Значения хранятся на сервере и заменяют netadmin.env. Начинают действовать после перезапуска сервера. " +
                                    "Адрес базы, API-токен и ключ шифрования остаются в файле.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (config.restartPending || restarting) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        if (restarting) "Сервер перезапускается… Это займёт 10–30 секунд."
                                        else "Есть сохранённые изменения, которые ещё не действуют.",
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    if (!restarting) {
                                        Spacer(Modifier.height(8.dp))
                                        if (config.canRestart) {
                                            FilledTonalButton(onClick = { askRestart = true }) { Text("Перезапустить сервер") }
                                        } else {
                                            Text(
                                                "Сервер запущен не как служба: перезапустите его вручную.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(config.groups, key = { it.title }) { g ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
                                .padding(16.dp),
                        ) {
                            SectionLabel(g.title)
                            if (g.note.isNotBlank()) {
                                Text(g.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            g.fields.forEachIndexed { i, f ->
                                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                FieldRow(
                                    field = f,
                                    edited = edits.values[f.key],
                                    resetting = f.key in edits.reset,
                                    credentials = credentials,
                                    onChange = { viewModel.set(f.key, it) },
                                    onReset = { viewModel.reset(f.key) },
                                    onFetchCertificate = {
                                        viewModel.fetchCertificate((edits.values["ad_ldap_url"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                                    },
                                )
                            }
                        }
                    }
                    item(key = "save") {
                        Button(onClick = ::confirmAndSave, enabled = !edits.isEmpty && !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Сохранить")
                        }
                    }
                }
            }
        }
    }

    certificate?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissCertificate,
            title = { Text("Сертификат контроллера") },
            text = {
                Text(
                    "Кому: ${c.subject}\nКем выдан: ${c.issuer}${if (c.selfSigned) " (самоподписанный)" else ""}\n" +
                        "Действует до: ${c.notAfter.take(10)}\n\nОтпечаток SHA-256:\n${c.sha256}\n\n" +
                        "Сверьте на контроллере: openssl x509 -in /var/lib/samba/private/tls/cert.pem -noout -fingerprint -sha256",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.acceptCertificate("ad_ca_pem") }) { Text("Доверять") } },
            dismissButton = { TextButton(onClick = viewModel::dismissCertificate) { Text("Отмена") } },
        )
    }

    if (askRestart) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askRestart = false },
            title = { Text("Перезапустить сервер?") },
            text = { Text("Он будет недоступен 10–30 секунд, затем начнут действовать сохранённые настройки.") },
            confirmButton = { TextButton(onClick = { askRestart = false; viewModel.restart() }) { Text("Перезапустить") } },
            dismissButton = { TextButton(onClick = { askRestart = false }) { Text("Отмена") } },
        )
    }
}

private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonElement?.flag(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldRow(
    field: ServerConfigField,
    edited: JsonElement?,
    resetting: Boolean,
    credentials: List<Credential>,
    onChange: (JsonElement?) -> Unit,
    onReset: () -> Unit,
    onFetchCertificate: () -> Unit,
) {
    val value = edited ?: field.value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(field.label, style = MaterialTheme.typography.bodyLarge)
                val tags = listOfNotNull(
                    if (resetting) "вернётся из netadmin.env" else field.sourceText,
                    "изменено".takeIf { edited != null },
                    "после перезапуска".takeIf { field.restartRequired && edited == null },
                )
                Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (field.kind == "bool") {
                Spacer(Modifier.width(12.dp))
                Switch(checked = value.flag(), onCheckedChange = { onChange(JsonPrimitive(it)) })
            }
        }
        when (field.kind) {
            "bool" -> Unit
            "choice" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                field.choices.forEach { c ->
                    FilterChip(selected = value.text() == c.value, onClick = { onChange(JsonPrimitive(c.value)) }, label = { Text(c.label) })
                }
            }
            "credential" -> {
                val kind = CredentialKind.entries.firstOrNull { it.wire == field.credentialKind }
                val fitting = credentials.filter { it.kind == kind }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = value.text().isEmpty(), onClick = { onChange(JsonPrimitive("")) }, label = { Text("Не выбрана") })
                    fitting.forEach { c ->
                        FilterChip(selected = value.text() == c.name, onClick = { onChange(JsonPrimitive(c.name)) }, label = { Text(c.name) })
                    }
                    if (value.text().isNotEmpty() && fitting.none { it.name == value.text() }) {
                        FilterChip(selected = true, onClick = {}, label = { Text("${value.text()} — нет в «Учётных данных»") })
                    }
                }
                if (fitting.isEmpty()) {
                    Text(
                        "Добавьте учётные данные вида ${kind?.label ?: field.credentialKind} в «Ещё → Учётные данные».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            "secret" -> OutlinedTextField(
                value = edited.text(),
                onValueChange = { onChange(if (it.isEmpty()) null else JsonPrimitive(it)) },
                placeholder = { Text(if (field.isSet) "задано — пусто, чтобы не менять" else "не задано") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> {
                val number = field.kind == "int" || field.kind == "float"
                val pem = field.kind == "pem"
                OutlinedTextField(
                    value = value.text(),
                    onValueChange = { onChange(JsonPrimitive(if (pem) it else it.trim())) },
                    singleLine = !pem,
                    minLines = if (pem) 4 else 1,
                    placeholder = if (pem) ({ Text("-----BEGIN CERTIFICATE-----") }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (field.kind == "pem") TextButton(onClick = onFetchCertificate) { Text("Получить с контроллера") }
        if (field.help.isNotBlank()) {
            Text(field.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (field.source == "ui" && !resetting) {
            TextButton(onClick = onReset, contentPadding = PaddingValues(0.dp)) { Text("Вернуть из netadmin.env") }
        }
    }
}
