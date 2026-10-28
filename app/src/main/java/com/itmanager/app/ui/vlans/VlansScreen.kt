package com.itmanager.app.ui.vlans

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.VLAN_COLORS
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.FormSection
import com.itmanager.app.ui.components.FormSheet
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.theme.MonoStyle

/**
 * The VLANs of the plant with their subnets: what the map's L2 and L3 layers colour and group by.
 * The server's own list: nothing here is sent to a switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlansScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: VlansViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("VLAN и подсети") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openNew,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("VLAN") },
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, viewModel::refresh, onOpenSettings)
                is UiState.Content -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    item {
                        Text(
                            "Слой L2 на карте красит устройства цветом VLAN их порта, L3 собирает их по подсетям. " +
                                "VLAN порта меняется в карточке устройства или на панели портов коммутатора. " +
                                "Импорт конфигурации коммутатора добавляет VLAN сам.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (s.data.isEmpty()) item { EmptyView("VLAN пока нет. Добавьте первый или импортируйте конфигурацию коммутатора.", action = "Добавить первый VLAN", onAction = viewModel::openNew) }
                    items(s.data, key = { it.id }) { vlan -> VlanRow(vlan) { viewModel.openEdit(vlan) } }
                }
            }
        }
    }

    editor?.let { e ->
        VlanDialog(
            editor = e,
            onChange = viewModel::change,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
            onDismiss = viewModel::close,
        )
    }
}

@Composable
private fun VlanRow(vlan: Vlan, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(Modifier.size(14.dp).background(Color(vlan.colorArgb), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${vlan.id} · ${vlan.name}", style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(vlan.subnet.ifBlank { "без подсети" }, vlan.description).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Adds or changes a VLAN. Delete asks first and is refused while ports are in the VLAN. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VlanDialog(
    editor: VlanEditor,
    onChange: (com.itmanager.app.data.model.VlanDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val d = editor.draft
    val show = editor.triedSave
    FormSheet(
        title = if (editor.isNew) "Новый VLAN" else "VLAN ${d.id}",
        onDismiss = onDismiss,
        onSave = onSave,
        busy = editor.busy,
        error = editor.error,
        deleteLabel = if (editor.isNew) null else "Удалить VLAN",
        deleteQuestion = "Удалить VLAN ${d.id}?" to "VLAN пропадёт из справочника. Если он назначен портам, сервер откажет: сначала переведите порты.",
        onDelete = { onDelete(d.id) },
    ) {
        FormSection("VLAN") {
            if (editor.isNew) {
                val idError = d.idProblem().takeIf { show }
                OutlinedTextField(
                    value = if (d.id == 0) "" else d.id.toString(),
                    onValueChange = { v -> onChange(d.copy(id = v.filter(Char::isDigit).take(4).toIntOrNull() ?: 0)) },
                    label = { Text("Номер VLAN (1–4094)") },
                    isError = idError != null,
                    supportingText = if (idError != null) ({ Text(idError) }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val nameError = d.nameProblem().takeIf { show }
            OutlinedTextField(
                value = d.name,
                onValueChange = { onChange(d.copy(name = it.take(50))) },
                label = { Text("Название") },
                placeholder = { Text("Офис, АСУ ТП, Камеры") },
                isError = nameError != null,
                supportingText = if (nameError != null) ({ Text(nameError) }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FormSection("Подсеть (L3)") {
            val subnetError = d.subnetProblem()
            OutlinedTextField(
                value = d.subnet,
                onValueChange = { onChange(d.copy(subnet = it.trim().take(18))) },
                label = { Text("Подсеть, если есть") },
                placeholder = { Text("10.10.20.0/24") },
                isError = subnetError != null && (show || d.subnet.contains('/')),
                supportingText = { Text(subnetError?.takeIf { show || d.subnet.contains('/') } ?: "По ней слой «Подсети» собирает устройства, IPAM считает адреса.") },
                singleLine = true,
                textStyle = MonoStyle,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FormSection("Цвет на карте") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VLAN_COLORS.forEach { c ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .border(2.dp, if (c == d.colorArgb) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                            .padding(4.dp)
                            .background(Color(c), CircleShape)
                            .clickable { onChange(d.copy(colorArgb = c)) },
                    )
                }
            }
        }
        FormSection("Заметки") {
            OutlinedTextField(
                value = d.description.orEmpty(),
                onValueChange = { onChange(d.copy(description = it.take(500))) },
                label = { Text("Описание") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
