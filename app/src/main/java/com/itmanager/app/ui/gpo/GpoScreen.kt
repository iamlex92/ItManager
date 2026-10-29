package com.itmanager.app.ui.gpo

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.core.security.BiometricGate
import com.itmanager.app.core.security.GateResult
import com.itmanager.app.core.security.findFragmentActivity
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.GPO_TYPES
import com.itmanager.app.data.model.Gpo
import com.itmanager.app.data.model.GpoChange
import com.itmanager.app.data.model.GpoDetail
import com.itmanager.app.data.model.GpoOu
import com.itmanager.app.data.model.GpoRegistryValue
import com.itmanager.app.data.model.GpoStatus
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.TabTopBar
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.StatusColors
import kotlinx.coroutines.launch

/** A change waiting for «Да» and then the fingerprint. */
private class Ask(val title: String, val question: String, val run: (AuthMethod) -> Unit)

/**
 * «Групповые политики»: the domain's GPOs — which take effect, where they are linked, what they set and which
 * computers they reach — and the GPMC changes: state, links, registry settings, new and deleted GPOs, gpupdate.
 * Every change is asked once more («Да») and then confirmed with biometrics.
 */
@Composable
fun GpoScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: GpoViewModel = hiltViewModel(),
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    val ous by viewModel.ous.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<Ask?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    BackHandler(enabled = page != null) { viewModel.close() }

    val writes = (overview as? UiState.Content)?.data?.writesEnabled == true
    fun confirm(title: String, question: String, run: (AuthMethod) -> Unit) {
        if (!writes) return viewModel.fail("Изменения выключены на сервере: «Настройки сервера» → «Разрешить изменения».")
        ask = Ask(title, question, run)
    }

    fun confirmed(a: Ask) {
        ask = null
        scope.launch {
            BiometricGate.unavailableReason(context)?.let { return@launch viewModel.fail(it) }
            val activity = context.findFragmentActivity() ?: return@launch viewModel.fail("Не удалось показать окно подтверждения.")
            when (val gate = BiometricGate.confirm(activity, a.title, "Подтвердите изменение групповой политики")) {
                is GateResult.Confirmed -> a.run(gate.method)
                GateResult.Cancelled -> Unit
                is GateResult.Failed -> viewModel.fail("Подтверждение не прошло: ${gate.message}")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val p = page
        if (p == null) {
            TabTopBar(
                title = "Групповые политики",
                subtitle = (overview as? UiState.Content)?.data?.let { if (it.real) it.host else "Эмуляция: данные ненастоящие" },
                onBack = onBack,
            ) {
                if (writes) IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, contentDescription = "Новая политика") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (val s = overview) {
                UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, viewModel::load, onOpenSettings)
                is UiState.Content -> GpoList(s.data.gpos, onOpen = viewModel::open)
            }
        } else {
            val d = p.detail
            TabTopBar(title = d?.gpo?.name ?: "Политика", subtitle = d?.let { if (it.gpo.active) "Действует" else "Не действует" }, onBack = viewModel::close) {
                if (d != null && writes) {
                    IconButton(onClick = {
                        confirm("Удалить политику", "Удалить «${d.gpo.name}» из домена? Отменить можно только из резервной копии GPO.") {
                            viewModel.delete(it)
                        }
                    }) { Icon(Icons.Outlined.Delete, contentDescription = "Удалить политику") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                d != null -> GpoPageView(viewModel, p, d, ous, writes, ::confirm)
                p.error != null -> ErrorView(p.error, { viewModel.open(p.id) }, onOpenSettings)
                else -> LoadingView()
            }
        }
    }

    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(a.title) },
            text = { Text(a.question) },
            confirmButton = { TextButton(onClick = { confirmed(a) }) { Text("Да") } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Нет") } },
        )
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Новая групповая политика") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(200) }, label = { Text("Имя") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    creating = false
                    confirm("Создать политику", "Создать «${name.trim()}»? Она начнёт действовать, когда её свяжут с подразделением.") {
                        viewModel.create(name, it)
                    }
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GpoList(gpos: List<Gpo>, onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) } // 0 all, 1 active, 2 inactive
    val shown = gpos.filter {
        (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) &&
            (filter == 0 || (filter == 1) == it.active)
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "search") {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Найти политику") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf("Все", "Действуют", "Не действуют").forEachIndexed { i, t ->
                        FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(t) })
                    }
                }
            }
        }
        items(shown, key = { it.id }) { g ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
                    .clickable { onOpen(g.id) }
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        if (g.active) "Действует" else "Не действует",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (g.active) StatusColors.Online else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val links = if (g.links.isEmpty()) "не связана" else "связей ${g.links.count { it.enabled }} из ${g.links.size}"
                Text(
                    listOfNotNull(g.status.title.takeIf { g.status != GpoStatus.ENABLED }, links, g.description).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (shown.isEmpty()) item(key = "empty") { Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GpoPageView(
    viewModel: GpoViewModel,
    page: GpoPage,
    d: GpoDetail,
    ous: List<GpoOu>,
    writes: Boolean,
    confirm: (String, String, (AuthMethod) -> Unit) -> Unit,
) {
    val g = d.gpo
    var editing by remember { mutableStateOf<GpoRegistryValue?>(null) }
    var linking by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        if (d.simulated) {
            item(key = "sim") {
                Text(
                    "Эмуляция: настоящий домен не затрагивается. Включается в «Настройки сервера» → «Компьютеры домена».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "status") {
            Card("Состояние") {
                g.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GpoStatus.entries.forEach { st ->
                        FilterChip(
                            selected = g.status == st,
                            enabled = writes,
                            onClick = {
                                if (st != g.status) {
                                    confirm("Состояние политики", "Сменить состояние «${g.name}» на «${st.title}»?") {
                                        viewModel.change("PUT", "status", GpoChange(status = st.wire), it)
                                    }
                                }
                            },
                            label = { Text(st.title) },
                        )
                    }
                }
            }
        }
        item(key = "links") {
            Card("Где действует (связи)") {
                if (g.links.isEmpty()) Text("Ни с чем не связана — не действует.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                g.links.forEachIndexed { i, l ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.targetName, style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = l.enforced, enabled = writes, onClick = {
                                    confirm("Связь", "${if (l.enforced) "Снять принудительность" else "Сделать принудительной"} связь с ${l.targetName}?") {
                                        viewModel.change("PATCH", "links", GpoChange(target = l.target, enforced = !l.enforced), it)
                                    }
                                }, label = { Text("Принудительно") })
                                TextButton(enabled = writes, onClick = {
                                    confirm("Убрать связь", "Убрать связь «${g.name}» с ${l.targetName}? Там политика перестанет действовать.") {
                                        viewModel.change("POST", "links/remove", GpoChange(target = l.target), it)
                                    }
                                }) { Text("Убрать") }
                            }
                        }
                        Switch(checked = l.enabled, enabled = writes, onCheckedChange = { on ->
                            confirm("Связь", "${if (on) "Включить" else "Выключить"} связь с ${l.targetName}?") {
                                viewModel.change("PATCH", "links", GpoChange(target = l.target, enabled = on), it)
                            }
                        })
                    }
                }
                if (writes) FilledTonalButton(onClick = { linking = true }) { Text("Связать с подразделением") }
            }
        }
        item(key = "registry") {
            Card("Параметры (административные шаблоны)") {
                if (d.registry.isEmpty()) {
                    Text("Параметров реестра нет. Другие разделы (сценарии, безопасность) видны в отчёте в веб-консоли.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                d.registry.forEach { r ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = writes) { editing = r }.padding(vertical = 6.dp)) {
                        Text("${r.name} = ${if (r.deleting) "(удаляется)" else r.value.orEmpty()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${r.key} · ${r.type}", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (writes) {
                    FilledTonalButton(onClick = { editing = GpoRegistryValue("HKLM\\Software\\Policies\\", "", "DWord", "", false) }) {
                        Text("Добавить параметр")
                    }
                }
            }
        }
        item(key = "filter") {
            Card("Применяется к") {
                Text(d.securityFilter.joinToString(", ").ifEmpty { "Никому (фильтр безопасности пуст)" })
            }
        }
        item(key = "computers") {
            Card("Компьютеры, на которые действует") {
                val s = page.scope
                when {
                    page.scopeLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    s == null -> FilledTonalButton(onClick = viewModel::loadScope) { Text("Показать компьютеры") }
                    else -> {
                        Text(
                            "${s.computers.size} компьютеров${if (s.filtered) " (с учётом фильтра безопасности)" else ""}. " +
                                "По связям и наследованию; WMI-фильтры не учитываются.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        s.computers.forEach { c ->
                            Text(c.name + if (!c.enabled) " (отключён в AD)" else "", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (writes && s.computers.isNotEmpty()) {
                            Button(onClick = {
                                confirm("Применить политику", "Обновить групповые политики (gpupdate /force) на ${s.computers.size} компьютерах сейчас?") {
                                    viewModel.apply(null, it)
                                }
                            }) { Text("Применить сейчас") }
                        }
                    }
                }
                page.job?.let { job ->
                    Text(
                        if (job.running) "Применяется: ${job.done} из ${job.total}…"
                        else "Готово: успешно ${job.rows.count { it.ok }} из ${job.total}${if (job.simulated) " (эмуляция)" else ""}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (job.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    job.rows.filter { !it.ok }.forEach {
                        Text("${it.host}: ${it.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    editing?.let { r ->
        RegistryDialog(
            value = r,
            onDismiss = { editing = null },
            onSave = { key, name, type, value ->
                editing = null
                confirm("Параметр политики", "Записать $name = $value в «${g.name}»?") {
                    viewModel.change("PUT", "registry", GpoChange(key = key, name = name, type = type, value = value), it)
                }
            },
            onDelete = if (r.name.isNotEmpty()) {
                {
                    editing = null
                    confirm("Удалить параметр", "Удалить ${r.name} из «${g.name}»?") {
                        viewModel.change("POST", "registry/remove", GpoChange(key = r.key, name = r.name), it)
                    }
                }
            } else null,
        )
    }
    if (linking) {
        val free = ous.filter { o -> g.links.none { it.target.equals(o.dn, ignoreCase = true) } }
        var enforced by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { linking = false },
            title = { Text("Связать с подразделением") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = enforced, onCheckedChange = { enforced = it })
                        Text("Принудительно")
                    }
                    if (free.isEmpty()) Text("Свободных подразделений нет.")
                    free.forEach { o ->
                        Text(
                            o.name,
                            modifier = Modifier.fillMaxWidth().clickable {
                                linking = false
                                confirm("Связать", "Связать «${g.name}» с ${o.name}${if (enforced) " (принудительно)" else ""}?") {
                                    viewModel.change("POST", "links", GpoChange(target = o.dn, enforced = enforced), it)
                                }
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { linking = false }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegistryDialog(
    value: GpoRegistryValue,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var key by remember { mutableStateOf(value.key) }
    var name by remember { mutableStateOf(value.name) }
    var type by remember { mutableStateOf(value.type) }
    var text by remember { mutableStateOf(value.value.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (value.name.isEmpty()) "Новый параметр" else value.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Раздел (HKLM\\… или HKCU\\…)") }, textStyle = MonoStyle)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Параметр") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GPO_TYPES.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) }) }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(if (type == "MultiString") "Значения, по одному в строке" else "Значение") },
                    singleLine = type != "MultiString",
                )
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Удалить параметр", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = key.isNotBlank() && name.isNotBlank(), onClick = { onSave(key.trim(), name.trim(), type, text) }) { Text("Записать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel(title)
        Spacer(Modifier.height(2.dp))
        content()
    }
}
