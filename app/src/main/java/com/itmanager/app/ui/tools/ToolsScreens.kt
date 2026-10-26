package com.itmanager.app.ui.tools

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Http
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itmanager.app.core.nettools.Cidr
import com.itmanager.app.core.nettools.Iperf3Client
import com.itmanager.app.core.nettools.Iperf3Exception
import com.itmanager.app.core.nettools.Iperf3Result
import com.itmanager.app.core.nettools.NetTools
import com.itmanager.app.core.nettools.OuiDatabase
import com.itmanager.app.core.nettools.PORT_PRESETS
import com.itmanager.app.core.nettools.PingLine
import com.itmanager.app.core.nettools.PortEvent
import com.itmanager.app.core.nettools.SERVICE_NAMES
import com.itmanager.app.core.nettools.Severity
import com.itmanager.app.core.nettools.SnmpClient
import com.itmanager.app.core.nettools.SnmpRow
import com.itmanager.app.core.nettools.SnmpTools
import com.itmanager.app.core.nettools.formatMs
import com.itmanager.app.core.nettools.ipv4ToString
import com.itmanager.app.core.nettools.isLocallyAdministered
import com.itmanager.app.core.nettools.isMulticastMac
import com.itmanager.app.core.nettools.macHex
import com.itmanager.app.core.nettools.macToString
import com.itmanager.app.core.nettools.parseMac
import com.itmanager.app.core.nettools.parsePorts
import com.itmanager.app.core.nettools.pingStats
import com.itmanager.app.ui.theme.MonoStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ToolDef(val route: String, val icon: ImageVector, val title: String, val subtitle: String)

private val TOOLS = listOf(
    ToolDef("ping", Icons.Outlined.NetworkPing, "Ping", "Доступность, задержка, потери пакетов"),
    ToolDef("trace", Icons.Outlined.Route, "Трассировка", "Путь до узла по прыжкам"),
    ToolDef("dns", Icons.Outlined.Dns, "Имя хоста", "DNS, обратный запрос, имя NetBIOS и MAC"),
    ToolDef("ports", Icons.Outlined.Radar, "Проверка портов", "Какие TCP-порты открыты"),
    ToolDef("http", Icons.Outlined.Http, "HTTP и сертификат", "Статус, редиректы, срок действия сертификата"),
    ToolDef("snmp", Icons.Outlined.Sensors, "SNMP", "Имя, аптайм, интерфейсы, любой OID (только чтение)"),
    ToolDef("iperf", Icons.Outlined.Speed, "Тест скорости (iperf3)", "Пропускная способность до сервера iperf3"),
    ToolDef("oui", Icons.Outlined.Badge, "MAC → производитель", "База IEEE без интернета"),
    ToolDef("wol", Icons.Outlined.PowerSettingsNew, "Wake-on-LAN", "Включить компьютер по MAC"),
    ToolDef("subnet", Icons.Outlined.Calculate, "Калькулятор подсетей", "Маска, диапазон, деление на подсети"),
)

/** Names of the tools, for the navigation graph to validate a route argument. */
val ToolKinds: Set<String> = TOOLS.map { it.route }.toSet()

private object Recents {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nettools", Context.MODE_PRIVATE)

    fun load(ctx: Context, key: String): List<String> =
        prefs(ctx).getString(key, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun push(ctx: Context, key: String, value: String) {
        val list = (listOf(value.trim()) + load(ctx, key)).distinct().take(8)
        prefs(ctx).edit().putString(key, list.joinToString("\n")).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ToolsHubScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    ToolScaffold("Сетевые утилиты", onBack) {
        Text(
            "Работают с самого телефона, без сервера: годятся там, куда сервер не дотягивается. Ничего не меняют в сети, кроме Wake-on-LAN.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TOOLS.forEach { t ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { onOpen(t.route) }
                    .padding(14.dp),
            ) {
                Icon(t.icon, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                    Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One tool by its route name; [host] pre-fills the target when opened from a device. */
@Composable
fun ToolRoute(kind: String, host: String, onBack: () -> Unit) {
    when (kind) {
        "ping" -> PingScreen(host, onBack)
        "trace" -> TraceScreen(host, onBack)
        "dns" -> DnsScreen(host, onBack)
        "ports" -> PortsScreen(host, onBack)
        "http" -> HttpScreen(host, onBack)
        "snmp" -> SnmpScreen(host, onBack)
        "iperf" -> IperfScreen(host, onBack)
        "oui" -> OuiScreen(host, onBack)
        "wol" -> WolScreen(onBack)
        else -> SubnetScreen(onBack)
    }
}

// ---- shared pieces ----

@Composable
private fun ToolInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    recentKey: String,
    enabled: Boolean,
    onGo: () -> Unit,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Uri,
) {
    val ctx = LocalContext.current
    val recents = remember(enabled) { Recents.load(ctx, recentKey) }
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.titleMedium.merge(MonoStyle),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
    )
    if (recents.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            recents.forEach { r -> AssistChip(onClick = { if (enabled) onChange(r) }, label = { Text(r, maxLines = 1) }) }
        }
    }
}

@Composable
private fun RunButton(running: Boolean, label: String, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    if (running) {
        FilledTonalButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
    } else {
        Button(onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(vertical = 6.dp),
        content = content,
    )
}

@Composable
private fun ValueRow(label: String, value: String, mono: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(112.dp))
        Text(value, style = if (mono) MaterialTheme.typography.bodyLarge.merge(MonoStyle) else MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

private fun Throwable.readable(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/** Runs [block] as the tool's single job; a second tap on the same button stops it. */
@Composable
private fun rememberJob(): JobHolder {
    val scope = rememberCoroutineScope()
    return remember { JobHolder(scope) }
}

private class JobHolder(private val scope: kotlinx.coroutines.CoroutineScope) {
    var job: Job? = null
    var running by mutableStateOf(false)

    fun start(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        job?.cancel()
        running = true
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } finally {
                running = false
            }
        }
    }

    fun stop() { job?.cancel(); running = false }
}

// ---- ping ----

@Composable
private fun PingScreen(initialHost: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var host by rememberSaveable { mutableStateOf(initialHost) }
    val rtts = remember { mutableStateListOf<Double?>() }
    var target by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val job = rememberJob()

    fun start() {
        val h = host.trim()
        if (h.isEmpty()) return
        Recents.push(ctx, "hosts", h)
        rtts.clear(); error = null; target = null
        job.start {
            val addr = NetTools.resolve(h)
            if (addr == null) { error = "Имя «$h» не разрешается"; return@start }
            target = addr.hostAddress
            NetTools.ping(addr, 0).collect { (_, a) -> rtts += (a.line as? PingLine.Reply)?.rttMs }
        }
    }

    ToolScaffold("Ping", onBack) {
        ToolInput(host, { host = it }, "Адрес или имя", "hosts", !job.running, ::start, "10.0.0.1")
        RunButton(job.running, "Запустить", host.isNotBlank(), ::start, job::stop)
        error?.let { Note(it, error = true) }
        if (rtts.isNotEmpty()) {
            val s = pingStats(rtts.toList())
            Card {
                target?.takeIf { it != host.trim() }?.let { ValueRow("Адрес", it) }
                ValueRow("Получено", "${s.received} из ${s.sent}, потери ${s.lossPercent}%")
                ValueRow("мин / сред / макс", listOf(s.minMs, s.avgMs, s.maxMs).joinToString(" / ") { it?.let(::formatMs) ?: "-" } + " мс")
                ValueRow("Джиттер", (s.jitterMs?.let(::formatMs) ?: "-") + " мс")
            }
            Card {
                rtts.takeLast(12).reversed().forEachIndexed { i, v ->
                    ValueRow("#${rtts.size - i}", v?.let { "${formatMs(it)} мс" } ?: "нет ответа")
                }
            }
        }
    }
}

// ---- traceroute ----

@Composable
private fun TraceScreen(initialHost: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var host by rememberSaveable { mutableStateOf(initialHost) }
    val hops = remember { mutableStateListOf<com.itmanager.app.core.nettools.Hop>() }
    val names = remember { mutableStateOf(mapOf<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val job = rememberJob()

    fun start() {
        val h = host.trim()
        if (h.isEmpty()) return
        Recents.push(ctx, "hosts", h)
        hops.clear(); error = null; names.value = emptyMap()
        job.start {
            val addr = NetTools.resolve(h)
            if (addr == null) { error = "Имя «$h» не разрешается"; return@start }
            NetTools.traceroute(addr).collect { hop ->
                hops += hop
                hop.addr?.let { a -> launch { NetTools.reverse(a)?.let { n -> names.value = names.value + (a to n) } } }
            }
        }
    }

    ToolScaffold("Трассировка", onBack) {
        ToolInput(host, { host = it }, "Адрес или имя", "hosts", !job.running, ::start, "10.0.0.1")
        RunButton(job.running, "Запустить", host.isNotBlank(), ::start, job::stop)
        if (job.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Note(it, error = true) }
        if (hops.isNotEmpty()) Card {
            hops.forEach { h ->
                val rtt = h.rttsMs.joinToString(" ") { it?.let(::formatMs) ?: "*" } + " мс"
                val who = (h.addr ?: "*") + (h.addr?.let { names.value[it] }?.let { " ($it)" }.orEmpty())
                ValueRow("%2d".format(h.ttl), who + "\n" + rtt + (h.note?.let { "\n$it" }.orEmpty()))
            }
        }
        if (!job.running && hops.lastOrNull()?.reached == true) Note("Узел достигнут за ${hops.size} прыж.")
    }
}

// ---- DNS and NetBIOS ----

@Composable
private fun DnsScreen(initialHost: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var answer by remember { mutableStateOf<com.itmanager.app.core.nettools.DnsAnswer?>(null) }
    var nb by remember { mutableStateOf<com.itmanager.app.core.nettools.NbInfo?>(null) }
    var done by remember { mutableStateOf(false) }
    val job = rememberJob()

    fun start() {
        val h = host.trim()
        if (h.isEmpty()) return
        Recents.push(ctx, "hosts", h)
        answer = null; nb = null; done = false
        job.start {
            val a = NetTools.dns(h)
            answer = a
            a.addresses.firstOrNull { '.' in it }?.let { nb = NetTools.netbios(it) }
            done = true
        }
    }

    ToolScaffold("Имя хоста", onBack) {
        ToolInput(host, { host = it }, "Имя или адрес", "hosts", !job.running, ::start, "server.corp.local")
        RunButton(job.running, "Узнать", host.isNotBlank(), ::start, job::stop)
        answer?.let { a ->
            if (a.addresses.isEmpty()) {
                Note("Имя не разрешается", error = true)
            } else {
                Card { a.addresses.forEach { ip -> ValueRow(ip, a.reverse[ip] ?: "обратной записи нет") } }
            }
        }
        nb?.let { n ->
            Card {
                n.hostName?.let { ValueRow("NetBIOS", it) }
                n.workgroup?.let { ValueRow("Рабочая группа", it) }
                n.mac?.let { ValueRow("MAC", it) }
            }
        }
        if (done && nb == null && answer?.addresses?.isNotEmpty() == true) Note("NetBIOS не отвечает: это не Windows и не Samba.")
    }
}

// ---- ports ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortsScreen(initialHost: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var spec by rememberSaveable { mutableStateOf(PORT_PRESETS.first().second) }
    val open = remember { mutableStateListOf<Int>() }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val job = rememberJob()

    fun start() {
        val h = host.trim()
        val ports = parsePorts(spec)
        if (h.isEmpty()) return
        if (ports == null) { error = "Порты: например 22,80,443 или 8000-8010"; return }
        Recents.push(ctx, "hosts", h)
        open.clear(); error = null; progress = null
        job.start {
            val addr = NetTools.resolve(h)
            if (addr == null) { error = "Имя «$h» не разрешается"; return@start }
            NetTools.portScan(addr, ports).collect { e ->
                when (e) {
                    is PortEvent.Open -> { open += e.port; open.sort() }
                    is PortEvent.Progress -> progress = e.done to e.total
                }
            }
        }
    }

    ToolScaffold("Проверка портов", onBack) {
        ToolInput(host, { host = it }, "Адрес или имя", "hosts", !job.running, ::start, "10.0.0.1")
        OutlinedTextField(
            spec, { spec = it }, label = { Text("Порты") }, singleLine = true, enabled = !job.running,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.merge(MonoStyle),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PORT_PRESETS.forEach { (label, value) -> FilterChip(selected = spec == value, onClick = { if (!job.running) spec = value }, label = { Text(label) }) }
        }
        RunButton(job.running, "Проверить", host.isNotBlank(), ::start, job::stop)
        progress?.let { (d, t) -> if (job.running) LinearProgressIndicator(progress = { d.toFloat() / t }, modifier = Modifier.fillMaxWidth()) }
        error?.let { Note(it, error = true) }
        if (open.isNotEmpty()) Card { open.forEach { p -> ValueRow(p.toString(), SERVICE_NAMES[p] ?: "—", mono = false) } }
        else if (!job.running && progress != null) Note("Открытых портов не найдено.")
    }
}

// ---- HTTP and certificate ----

@Composable
private fun HttpScreen(initialHost: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var probe by remember { mutableStateOf<com.itmanager.app.core.nettools.HttpProbe?>(null) }
    val job = rememberJob()

    fun start() {
        val h = host.trim()
        if (h.isEmpty()) return
        Recents.push(ctx, "hosts", h)
        probe = null
        // Certificates of switches and printers are mostly self-signed: look at them instead of refusing.
        job.start { probe = NetTools.http(h, verifyTls = false) }
    }

    ToolScaffold("HTTP и сертификат", onBack) {
        ToolInput(host, { host = it }, "Адрес или URL", "hosts", !job.running, ::start, "https://10.0.0.1")
        RunButton(job.running, "Проверить", host.isNotBlank(), ::start, job::stop)
        probe?.let { p ->
            p.error?.let { Note(it, error = true) }
            if (p.steps.isNotEmpty()) Card {
                p.steps.forEach { s ->
                    ValueRow("${s.code} · ${s.ms} мс", s.url + listOfNotNull(s.server, s.contentType).joinToString("") { "\n$it" } + (s.location?.let { "\n→ $it" }.orEmpty()))
                }
            }
            p.tls?.let { t ->
                Card { ValueRow("Сертификат", t.detail, mono = false) }
                if (t.severity != Severity.OK) Note(if (t.severity == Severity.FAIL) "Сертификат просрочен или не прочитан." else "Сертификат скоро истечёт.", error = t.severity == Severity.FAIL)
            }
        }
    }
}

// ---- SNMP ----

private enum class SnmpMode(val label: String) { SYSTEM("Система"), IFACES("Интерфейсы"), OID("OID") }

@Composable
private fun SnmpScreen(initialHost: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var community by rememberSaveable { mutableStateOf("public") }
    var mode by rememberSaveable { mutableStateOf(SnmpMode.SYSTEM) }
    var oid by rememberSaveable { mutableStateOf("1.3.6.1.2.1.1") }
    var rows by remember { mutableStateOf<List<SnmpRow>?>(null) }
    var ifaces by remember { mutableStateOf<List<com.itmanager.app.core.nettools.IfRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val job = rememberJob()

    fun start() {
        val h = host.trim()
        if (h.isEmpty()) return
        Recents.push(ctx, "hosts", h)
        rows = null; ifaces = null; error = null
        job.start {
            val addr = NetTools.resolve(h)
            if (addr == null) { error = "Имя «$h» не разрешается"; return@start }
            withContext(Dispatchers.IO) {
                val client = SnmpClient(addr, community.trim(), timeoutMs = 1500)
                when (mode) {
                    SnmpMode.SYSTEM -> SnmpTools.system(client)?.let { rows = it } ?: run { error = "Устройство не отвечает по SNMP (проверьте community и доступ)" }
                    SnmpMode.IFACES -> ifaces = SnmpTools.interfaces(client).also { if (it.isEmpty()) error = "Таблица интерфейсов пуста или устройство не отвечает" }
                    SnmpMode.OID -> {
                        val base = oid.trim().trim('.')
                        val r = if (base.endsWith(".0")) SnmpTools.get(client, listOf(base)) else SnmpTools.walk(client, base)
                        if (r.isNullOrEmpty()) error = "Нет данных по $base" else rows = r
                    }
                }
            }
        }
    }

    ToolScaffold("SNMP", onBack) {
        Note("Только чтение (GET и обход). Community хранится только на экране и нигде не сохраняется.")
        ToolInput(host, { host = it }, "Адрес или имя", "hosts", !job.running, ::start, "10.0.0.1")
        OutlinedTextField(
            community, { community = it }, label = { Text("Community") }, singleLine = true, enabled = !job.running,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.merge(MonoStyle),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SnmpMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }) }
        }
        if (mode == SnmpMode.OID) {
            OutlinedTextField(
                oid, { oid = it }, label = { Text("OID") }, singleLine = true, enabled = !job.running,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.merge(MonoStyle),
            )
        }
        RunButton(job.running, "Опросить", host.isNotBlank(), ::start, job::stop)
        if (job.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Note(it, error = true) }
        rows?.let { r -> Card { r.forEach { ValueRow(it.name ?: it.oid, it.value) } } }
        ifaces?.let { l ->
            Card {
                l.forEach { i ->
                    val state = when (i.up) { true -> "up"; false -> "down"; null -> "?" }
                    val speed = if (i.speedMbps > 0) " · ${i.speedMbps} Мбит/с" else ""
                    ValueRow(i.name.ifBlank { "if${i.index}" }, state + speed + (i.alias.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()))
                }
            }
        }
    }
}

// ---- iperf3 ----

@Composable
private fun IperfScreen(initialHost: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var reverse by rememberSaveable { mutableStateOf(false) }
    var live by remember { mutableStateOf<List<Double>>(emptyList()) }
    var result by remember { mutableStateOf<Iperf3Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var client by remember { mutableStateOf<Iperf3Client?>(null) }
    val job = rememberJob()

    fun start() {
        val raw = host.trim()
        if (raw.isEmpty()) return
        Recents.push(ctx, "hosts", raw)
        val h = raw.substringBefore(':')
        val port = raw.substringAfter(':', "").toIntOrNull() ?: 5201
        live = emptyList(); result = null; error = null
        job.start {
            val c = Iperf3Client(h, port, seconds = 5, reverse = reverse).also { client = it }
            try {
                withContext(Dispatchers.IO) { c.run { live = live + it.mbps } }.let { result = it }
            } catch (e: java.io.IOException) {
                error = (e as? Iperf3Exception)?.message ?: e.readable()
            } finally {
                client = null
            }
        }
    }

    ToolScaffold("Тест скорости (iperf3)", onBack) {
        Note("Нужен запущенный `iperf3 -s` на другой стороне. Адрес можно с портом: 10.0.0.5:5201.")
        ToolInput(host, { host = it }, "Сервер iperf3", "hosts", !job.running, ::start, "10.0.0.5")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !reverse, onClick = { reverse = false }, label = { Text("Отдача (вверх)") })
            FilterChip(selected = reverse, onClick = { reverse = true }, label = { Text("Загрузка (вниз)") })
        }
        RunButton(job.running, "Измерить", host.isNotBlank(), ::start) { client?.abort(); job.stop() }
        if (job.running) {
            LinearProgressIndicator(progress = { live.size / 5f }, modifier = Modifier.fillMaxWidth())
            live.lastOrNull()?.let { Note("Сейчас: ${formatMs(it)} Мбит/с") }
        }
        error?.let { Note(it, error = true) }
        result?.let { r ->
            Card {
                ValueRow("Скорость", "${formatMs(r.receiverMbps)} Мбит/с")
                ValueRow("Направление", if (r.reverse) "телефон принимает" else "телефон отправляет", mono = false)
                ValueRow("По секундам", r.intervals.joinToString(" ") { formatMs(it) })
            }
        }
    }
}

// ---- MAC vendor ----

@Composable
private fun OuiScreen(initial: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var mac by rememberSaveable { mutableStateOf(initial) }
    val hex = macHex(mac)
    var vendor by remember { mutableStateOf<String?>(null) }
    var looked by remember { mutableStateOf(false) }
    LaunchedEffect(hex) {
        vendor = null; looked = false
        if (hex != null) { vendor = OuiDatabase.vendor(ctx, hex); looked = true }
    }

    ToolScaffold("MAC → производитель", onBack) {
        ToolInput(mac, { mac = it }, "MAC или его первые 6 цифр", "macs", true, {}, "F4:F5:D8:01:02:03", KeyboardType.Ascii)
        if (mac.isNotBlank() && hex == null) Note("MAC-адрес — 12 шестнадцатеричных цифр; для поиска хватит первых 6.")
        if (hex != null && looked) {
            val random = isLocallyAdministered(hex)
            Card {
                ValueRow("Производитель", vendor ?: if (random) "нет: случайный адрес" else "не найден в базе", mono = false)
                ValueRow("Тип", if (isMulticastMac(hex)) "групповой (multicast)" else "индивидуальный (unicast)", mono = false)
                ValueRow("Происхождение", if (random) "локальный (случайный)" else "заводской", mono = false)
                if (hex.length == 12) {
                    val pairs = hex.chunked(2)
                    ValueRow("AA:BB:CC", pairs.joinToString(":"))
                    ValueRow("Cisco", hex.lowercase().chunked(4).joinToString("."))
                }
            }
            if (random) Note("Установлен бит «локально администрируемый»: это не заводской адрес. Телефоны и ноутбуки используют такие адреса для каждой сети Wi-Fi.")
        }
    }
}

// ---- Wake-on-LAN ----

@Composable
private fun WolScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mac by rememberSaveable { mutableStateOf("") }
    var broadcast by rememberSaveable { mutableStateOf("255.255.255.255") }
    var message by remember { mutableStateOf<String?>(null) }
    var bad by remember { mutableStateOf(false) }

    fun send() {
        val bytes = parseMac(mac)
        if (bytes == null) { message = "MAC-адрес — 12 шестнадцатеричных цифр, например 00:1A:2B:3C:4D:5E"; bad = true; return }
        Recents.push(ctx, "macs", macToString(bytes))
        scope.launch {
            val n = NetTools.wake(bytes, listOf(broadcast.trim(), "255.255.255.255"))
            bad = n == 0
            message = if (n == 0) "Не удалось отправить пакет. Есть ли подключение к сети?"
            else "Пакет отправлен на ${macToString(bytes)}. Компьютер включится, только если в BIOS и сетевой карте включён Wake-on-LAN и он в этой же сети."
        }
    }

    ToolScaffold("Wake-on-LAN", onBack) {
        ToolInput(mac, { mac = it }, "MAC-адрес", "macs", true, ::send, "00:1A:2B:3C:4D:5E", KeyboardType.Ascii)
        OutlinedTextField(
            broadcast, { broadcast = it }, label = { Text("Широковещательный адрес") }, singleLine = true,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.merge(MonoStyle),
        )
        Button(onClick = ::send, enabled = mac.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Отправить пакет") }
        message?.let { Note(it, error = bad) }
    }
}

// ---- subnet calculator ----

@Composable
private fun SubnetScreen(onBack: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("192.168.1.0/24") }
    var split by rememberSaveable { mutableStateOf(26) }
    val c = Cidr.parse(input)

    ToolScaffold("Калькулятор подсетей", onBack) {
        OutlinedTextField(
            input, { input = it.trim() }, singleLine = true, label = { Text("Адрес с префиксом или маской") },
            textStyle = MaterialTheme.typography.titleMedium.merge(MonoStyle), isError = c == null,
            supportingText = { if (c == null) Text("Например 10.0.0.0/24 или 10.0.0.0 255.255.255.0") },
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        )
        if (c != null) {
            Card {
                ValueRow("Сеть", "${ipv4ToString(c.network)}/${c.prefix}")
                ValueRow("Маска", ipv4ToString(c.mask))
                ValueRow("Обратная маска", ipv4ToString(c.wildcard))
                ValueRow("Широковещат.", ipv4ToString(c.broadcast))
                ValueRow("Хосты", "${ipv4ToString(c.firstHost)} – ${ipv4ToString(c.lastHost)}")
                ValueRow("Доступно", c.usableHosts.toString())
            }
            val options = (c.prefix + 1..minOf(c.prefix + 4, 30)).toList()
            if (options.isNotEmpty()) {
                val np = split.takeIf { it in options } ?: options.first()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { p -> FilterChip(selected = np == p, onClick = { split = p }, label = { Text("/$p") }) }
                }
                val size = 1L shl (32 - np)
                Card {
                    for (i in 0 until (1 shl (np - c.prefix))) {
                        val net = c.network + i * size
                        ValueRow("${ipv4ToString(net)}/$np", "${ipv4ToString(net + 1)} – ${ipv4ToString(net + size - 2)}")
                    }
                }
            }
        }
    }
}
