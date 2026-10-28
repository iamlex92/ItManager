package com.itmanager.app.ui.pcs

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.core.security.BiometricGate
import com.itmanager.app.core.security.GateResult
import com.itmanager.app.core.security.findFragmentActivity
import com.itmanager.app.data.model.PcAction
import com.itmanager.app.data.model.PcInfo
import com.itmanager.app.data.model.PcKeyJob
import com.itmanager.app.data.model.PcOutcome
import com.itmanager.app.data.model.PcStatus
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.TabTopBar
import com.itmanager.app.ui.theme.MonoStyle
import kotlinx.coroutines.launch

/** A change waiting for «Да» and then the fingerprint. */
private data class PendingAction(val action: PcAction, val arg: String?, val question: String)

/**
 * Domain computers: what the admin script Connect-DomainPC.ps1 did, run by the server over WinRM.
 * One computer at a time (system and licences, disks, folders, processes, policies, cleanup, reboot, console),
 * a ping sweep of a /24 and the licence key inventory with its CSV.
 */
@Composable
fun PcsScreen(
    onBack: () -> Unit,
    initialHost: String = "",
    viewModel: PcsViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val statusError by viewModel.statusError.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    LaunchedEffect(initialHost) { viewModel.openOnce(initialHost) }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(viewModel) {
        viewModel.exports.collect { result ->
            result.fold(
                onSuccess = { file ->
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(file.mime)
                        .putExtra(Intent.EXTRA_STREAM, file.uri)
                        .putExtra(Intent.EXTRA_SUBJECT, file.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Отправить инвентаризацию ключей"))
                },
                onFailure = { e -> Toast.makeText(context, e.message ?: "Файл не получился", Toast.LENGTH_LONG).show() },
            )
        }
    }
    // From the list the back button closes the computer; opened from a card it goes back to the card.
    BackHandler(enabled = session != null && initialHost.isBlank()) { viewModel.close() }

    fun ask(action: PcAction, arg: String? = null) {
        val host = session?.host ?: return
        if (!action.confirm) {
            viewModel.run(action, arg)
            return
        }
        val question = when (action) {
            PcAction.CLEANUP -> "Удалить временные файлы (C:\\Windows\\Temp, Temp пользователей) и кэш загрузок обновлений Windows на $host?"
            PcAction.REBOOT -> "Перезагрузить $host сейчас? Несохранённая работа пользователя пропадёт."
            PcAction.KILL -> "Завершить все процессы $arg на $host? Несохранённые данные в них пропадут."
            else -> "Выполнить на $host от имени администратора домена:\n\n$arg"
        }
        pending = PendingAction(action, arg, question)
    }

    fun confirmed(p: PendingAction) {
        pending = null
        scope.launch {
            BiometricGate.unavailableReason(context)?.let { return@launch viewModel.fail(it) }
            val activity = context.findFragmentActivity() ?: return@launch viewModel.fail("Не удалось показать окно подтверждения.")
            when (val gate = BiometricGate.confirm(activity, p.action.title, "Подтвердите действие на ${session?.host.orEmpty()}")) {
                is GateResult.Confirmed -> viewModel.run(p.action, p.arg, gate.method)
                GateResult.Cancelled -> Unit
                is GateResult.Failed -> viewModel.fail("Подтверждение не прошло: ${gate.message}")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val s = session
        if (s == null) {
            TabTopBar(title = "Компьютеры домена", subtitle = "Сведения, ключи, обслуживание по WinRM", onBack = onBack)
            PcsHome(viewModel, status, statusError)
        } else {
            TabTopBar(
                title = s.info?.computer ?: s.host,
                subtitle = s.info?.let { it.user ?: "Нет активного сеанса" } ?: "Подключение…",
                onBack = if (initialHost.isBlank()) viewModel::close else onBack,
            )
            PcPage(s, status, onRun = ::ask, onClearConsole = viewModel::clearConsole)
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(p.action.title) },
            text = { Text(p.question) },
            confirmButton = { TextButton(onClick = { confirmed(p) }) { Text("Да") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Нет") } },
        )
    }
}

// --- the list: open, sweep, key inventory ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PcsHome(viewModel: PcsViewModel, status: PcStatus?, statusError: String?) {
    val sweep by viewModel.sweep.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val keysError by viewModel.keysError.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf("") }
    var prefix by rememberSaveable { mutableStateOf("") }
    var keySource by rememberSaveable { mutableStateOf("ad") }
    var keyPrefix by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "mode") { ModeNote(status, statusError) }
        item(key = "open") {
            Card("Подключиться к ПК") {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Имя или IP-адрес") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { viewModel.open(host) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.open(host) }, enabled = host.isNotBlank()) { Text("Открыть") }
            }
        }
        item(key = "sweep") {
            Card("Поиск в подсети") {
                Text(
                    "Ping по всем адресам /24 и имя из DNS, как в скрипте. Например 192.168.82.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("Подсеть") },
                    placeholder = { Text("192.168.82") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.sweep(prefix) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { viewModel.sweep(prefix) }, enabled = prefix.isNotBlank() && sweep?.running != true) {
                    Text("Сканировать")
                }
                sweep?.let { sw ->
                    Spacer(Modifier.height(8.dp))
                    when {
                        sw.running -> {
                            Text("Сканирование ${sw.prefix}… это займёт до минуты.", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        sw.error != null -> Text(sw.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        sw.hits.isNullOrEmpty() -> Text("Активных узлов не найдено.", style = MaterialTheme.typography.bodySmall)
                        else -> {
                            val hits = sw.hits.orEmpty()
                            Text("Откликнулись: ${hits.size}", style = MaterialTheme.typography.labelLarge)
                            hits.forEach { h ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { viewModel.open(h.target) }.padding(vertical = 8.dp),
                                ) {
                                    Icon(Icons.Outlined.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(h.name ?: "DNS не ответил", style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            listOfNotNull(h.ip, h.deviceName?.let { "в учёте: $it" }).joinToString("  ·  "),
                                            style = MonoStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "keys") {
            Card("Инвентаризация ключей") {
                Text(
                    "Ключи Windows (BIOS и реестр), последние 5 символов ключей Office, лицензии 1С и пользователь — со всех включённых ПК. Результат — CSV для Excel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = keySource == "ad", onClick = { keySource = "ad" }, label = { Text("Все ПК из AD") })
                    FilterChip(selected = keySource == "subnet", onClick = { keySource = "subnet" }, label = { Text("Подсеть") })
                }
                if (keySource == "subnet") {
                    OutlinedTextField(
                        value = keyPrefix,
                        onValueChange = { keyPrefix = it },
                        label = { Text("Подсеть") },
                        placeholder = { Text("192.168.82") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { viewModel.startKeys(keySource, keyPrefix.takeIf { keySource == "subnet" }) },
                    enabled = keys?.running != true && (keySource == "ad" || keyPrefix.isNotBlank()),
                ) { Text("Собрать") }
                keysError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                keys?.let { KeyJobView(it, onShare = viewModel::shareKeys, onOpen = viewModel::open) }
            }
        }
    }
}

@Composable
private fun KeyJobView(job: PcKeyJob, onShare: () -> Unit, onOpen: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    when (job.status) {
        "running" -> {
            Text("${job.stage}: ${job.done} из ${job.online.takeIf { it > 0 } ?: job.total}…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        "failed" -> Text("Ошибка: ${job.error}", color = MaterialTheme.colorScheme.error)
        else -> {
            val ok = job.rows.count { it.ok }
            Text(
                "Готово: компьютеров ${job.total}, включено ${job.online}, собрано $ok.${if (job.simulated) " (эмуляция)" else ""}",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onShare, enabled = job.rows.isNotEmpty()) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Отправить CSV")
            }
            job.rows.forEach { r ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(r.host) }.padding(vertical = 8.dp)) {
                    Text(r.computer ?: r.host, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    if (!r.ok) {
                        Text(r.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        KeyLine("Пользователь", r.user ?: "нет сеанса")
                        KeyLine("ОС", r.os ?: "—")
                        KeyLine("Windows (BIOS)", r.windowsBiosKey ?: "Нет")
                        KeyLine("Windows (реестр)", r.windowsRegistryKey ?: "Нет")
                        KeyLine("MS Office", r.office)
                        KeyLine("1С", r.oneC)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyLine(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ModeNote(status: PcStatus?, error: String?) {
    val text = when {
        error != null -> error
        status == null -> return
        status.emulated -> "Эмуляция (RUNBOOK_MODE=emulator): ответы ненастоящие, к компьютерам сервер не подключается."
        !status.loginConfigured -> "Учётная запись для WinRM не задана: укажите RUNBOOK_CREDENTIAL на сервере."
        !status.writesEnabled -> "Только просмотр: изменения (очистка, перезагрузка, процессы, консоль) выключены на сервере (ALLOW_RUNBOOK_WRITES)."
        else -> "Подключение по WinRM от учётной записи домена на сервере. Изменения подтверждаются биометрией и пишутся в журнал."
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Icon(Icons.Outlined.Science, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

// --- one computer ---

private val ACTION_ICONS = mapOf(
    PcAction.INFO to Icons.Outlined.Info,
    PcAction.DISKS to Icons.Outlined.Storage,
    PcAction.FOLDERS to Icons.Outlined.FolderOpen,
    PcAction.PROCESSES to Icons.Outlined.Memory,
    PcAction.GPUPDATE to Icons.Outlined.Policy,
    PcAction.CLEANUP to Icons.Outlined.CleaningServices,
    PcAction.REBOOT to Icons.Outlined.RestartAlt,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PcPage(
    session: PcSession,
    status: PcStatus?,
    onRun: (PcAction, String?) -> Unit,
    onClearConsole: () -> Unit,
) {
    var command by rememberSaveable(session.host) { mutableStateOf("") }
    val readOnly = status?.writesEnabled == false
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "actions") { Column {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ACTION_ICONS.forEach { (action, icon) ->
                    ActionChip(icon, action.title, enabled = session.running == null && !(action.changes && readOnly)) { onRun(action, null) }
                }
            }
            if (session.running != null) {
                Text("${session.running.title}: выполняется на ${session.host}…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            session.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
            }
        } }
        val r = session.result
        if (r != null) {
            if (r.simulated) {
                item(key = "sim") {
                    Text("Эмуляция: данные ненастоящие.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (val o = r.outcome) {
                is PcOutcome.Info -> item(key = "info") { InfoView(o.info) }
                is PcOutcome.Disks -> items(o.disks, key = { "disk-${it.drive}" }) { d ->
                    Card("${d.drive} ${d.label.orEmpty()}".trim()) {
                        Text("Свободно ${d.freeGb} ГБ из ${d.totalGb} ГБ · занято ${d.usedPercent} %")
                        LinearProgressIndicator(progress = { d.usedPercent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
                is PcOutcome.Folders -> item(key = "folders") {
                    Card("Крупные папки на C:\\") {
                        if (o.folders.isEmpty()) Text("Папок больше 0,1 ГБ нет")
                        o.folders.forEach { f -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${f.sizeGb} ГБ", style = MonoStyle)
                        } }
                    }
                }
                is PcOutcome.Processes -> item(key = "processes") {
                    Card("Топ-20 процессов по памяти") {
                        o.processes.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("PID ${p.id} · ${p.ramMb} МБ", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { onRun(PcAction.KILL, p.name) }, enabled = session.running == null && !readOnly) {
                                    Text("Завершить", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                is PcOutcome.Cleaned -> item(key = "cleaned") {
                    Card("Очистка завершена") { Text("Освобождено ${o.freedMb} МБ, свободно на C: ${o.freeGb} ГБ.") }
                }
                is PcOutcome.Text -> item(key = "text") {
                    Card(r.action.title) { Text(o.text.ifBlank { "Готово" }, style = MonoStyle) }
                }
                is PcOutcome.Stopped, null -> Unit
            }
        }
        item(key = "console") {
            Card("Консоль PowerShell") {
                Text(
                    "Команда выполняется на ${session.host} от имени администратора домена. Каждая подтверждается и пишется в журнал.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                session.console.forEach { (cmd, out) ->
                    Text(
                        "PS ${session.host}> $cmd\n$out",
                        style = MonoStyle,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Команда") },
                    placeholder = { Text("Get-Service spooler") },
                    textStyle = MonoStyle,
                    enabled = !readOnly,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(
                        onClick = { onRun(PcAction.COMMAND, command.trim()) },
                        enabled = command.isNotBlank() && session.running == null && !readOnly,
                    ) { Text("Выполнить") }
                    if (session.console.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onClearConsole) { Text("Очистить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoView(info: PcInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card("Система") {
            Line("Компьютер", info.computer)
            Line("ОС", info.os?.let { os -> info.architecture?.let { "$os ($it)" } ?: os })
            Line("Сборка", info.build)
            Line("Аптайм", info.uptimeText)
            Line("Пользователь", info.user ?: "нет активного сеанса")
            Line("Процессор", info.cpu)
            Line("ОЗУ", info.ramGb?.let { "$it ГБ" })
        }
        Card("Лицензия Windows") {
            Line("Ключ BIOS", info.windowsBiosKey ?: "отсутствует (не вшит в BIOS)", mono = info.windowsBiosKey != null)
            Line("Ключ ОС", info.windowsRegistryKey ?: "не найден в реестре", mono = info.windowsRegistryKey != null)
        }
        Card("MS Office") { Text(info.officeText) }
        Card("Лицензии 1С:Предприятие") {
            if (info.oneC.isEmpty()) {
                Text(
                    "Программные лицензии (.lic) не найдены: USB-ключ HASP или сервер лицензий",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            info.oneC.forEach { Line(it.path, it.date) }
        }
    }
}

@Composable
private fun Line(label: String, value: String?, mono: Boolean = false) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        SectionLabel(title)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}
