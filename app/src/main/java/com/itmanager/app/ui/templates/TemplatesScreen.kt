package com.itmanager.app.ui.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.DeviceTemplate
import com.itmanager.app.data.model.DeviceTemplateDraft
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.UiState

/** Types worth a preset; the rest are added by hand. */
private val TemplateTypes = listOf(
    DeviceType.SWITCH, DeviceType.ROUTER, DeviceType.FIREWALL, DeviceType.ACCESS_POINT, DeviceType.SERVER,
    DeviceType.PATCH_PANEL, DeviceType.UPS, DeviceType.CAMERA, DeviceType.PRINTER, DeviceType.WORKSTATION,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: TemplatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }
    var using by remember { mutableStateOf<DeviceTemplate?>(null) }
    var deleting by remember { mutableStateOf<DeviceTemplate?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Шаблоны устройств", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, contentDescription = "Новый шаблон") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.navigationBars)) {
            when (val s = state) {
                UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, viewModel::load, onOpenSettings)
                is UiState.Content ->
                    if (s.data.isEmpty()) {
                        EmptyView("Шаблонов пока нет. Шаблон хранит тип, модель и порты типового устройства.", action = "Создать первый шаблон", onAction = { creating = true })
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(s.data, key = { it.id }) { t ->
                                TemplateCard(t, onUse = { using = t }, onDelete = { deleting = t })
                            }
                        }
                    }
            }
        }
    }

    if (creating) {
        TemplateDialog(onDismiss = { creating = false }, onSave = { viewModel.save(it); creating = false })
    }
    using?.let { t ->
        UseDialog(t, onDismiss = { using = null }, onCreate = { name, ip -> viewModel.createDevice(t, name, ip); using = null })
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить шаблон?") },
            text = { Text("«${t.name}» будет удалён. Устройства, созданные из него, останутся.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(t); deleting = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun TemplateCard(t: DeviceTemplate, onUse: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium)
                val details = listOfNotNull(
                    t.type.label,
                    listOfNotNull(t.vendor, t.model).joinToString(" ").ifEmpty { null },
                    if (t.portCount > 0) "${t.portCount} портов" else null,
                    t.rackH?.let { "${it}U" },
                ).joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Удалить шаблон") }
        }
        OutlinedButton(onClick = onUse, modifier = Modifier.padding(top = 8.dp)) { Text("Создать устройство") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TemplateDialog(onDismiss: () -> Unit, onSave: (DeviceTemplateDraft) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(DeviceType.SWITCH) }
    var vendor by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("0") }
    var prefix by remember { mutableStateOf("Port") }
    var rackH by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый шаблон") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TemplateTypes.forEach { t ->
                            FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                        }
                    }
                }
                item { OutlinedTextField(vendor, { vendor = it }, label = { Text("Производитель") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(model, { model = it }, label = { Text("Модель") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(ports, { ports = it.filter(Char::isDigit).take(2) }, label = { Text("Портов (0–96)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(prefix, { prefix = it }, label = { Text("Имя порта: префикс") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(rackH, { rackH = it.filter(Char::isDigit).take(2) }, label = { Text("Высота в стойке, U (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        DeviceTemplateDraft(
                            name.trim(), type, vendor, model,
                            (ports.toIntOrNull() ?: 0).coerceIn(0, 96), prefix, rackH.toIntOrNull()?.coerceIn(1, 60),
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun UseDialog(t: DeviceTemplate, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Из шаблона «${t.name}»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Имя устройства") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ip, { ip = it }, label = { Text("IP-адрес (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "Устройство попадёт в учёт без места на карте; расставить его можно на плане этажа.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, ip) }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
