package com.itmanager.app.ui.peripherals

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.PeripheralDraft
import com.itmanager.app.data.model.PeripheralKind
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.UiState

private enum class PeripheralFilter(val label: String) { ALL("Все"), ATTACHED("Подключённые"), STOCK("На складе") }

/** Every peripheral in the inventory, by device, with the stock at the end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeripheralsScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: PeripheralsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PeripheralFilter.ALL) }
    var kind by rememberSaveable { mutableStateOf<PeripheralKind?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Периферия") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.openNew(PeripheralDraft(kind ?: PeripheralKind.MONITOR)) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("Добавить") },
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = query,
                onValueChange = { query = it.take(60) },
                singleLine = true,
                placeholder = { Text("Модель, S/N, инв. номер, компьютер") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                items(PeripheralFilter.entries) { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
                items(PeripheralKind.entries) { k ->
                    FilterChip(selected = kind == k, onClick = { kind = if (kind == k) null else k }, label = { Text(k.label) })
                }
            }
            when (val s = state) {
                UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, viewModel::refresh, onOpenSettings)
                is UiState.Content -> {
                    if (s.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val shown = s.data.filter { p ->
                        p.matches(query) && (kind == null || p.kind == kind) && when (filter) {
                            PeripheralFilter.ALL -> true
                            PeripheralFilter.ATTACHED -> p.deviceId != null
                            PeripheralFilter.STOCK -> p.deviceId == null
                        }
                    }
                    // Grouped by the device they sit on, devices by name; the stock goes last.
                    val groups = shown.groupBy { it.deviceId }.toList()
                        .sortedWith(compareBy({ it.first == null }, { it.second.first().deviceName?.lowercase() }))
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        if (shown.isEmpty()) {
                            item {
                                EmptyView(
                                    if (s.data.isEmpty()) "Периферии пока нет. Добавьте монитор или клавиатуру кнопкой ниже." else "Ничего не найдено",
                                )
                            }
                        }
                        groups.forEach { (deviceId, items) ->
                            item(key = "h:${deviceId ?: ""}") {
                                Spacer(Modifier.height(6.dp))
                                SectionLabel("${if (deviceId == null) "На складе" else items.first().deviceName ?: "Устройство"} · ${items.size}")
                            }
                            items(items, key = { it.id }) { item ->
                                PeripheralRow(item, showDevice = false, onClick = { viewModel.openEdit(item) }, modifier = Modifier.animateItem())
                            }
                        }
                    }
                }
            }
        }
    }

    editor?.let { e ->
        PeripheralEditorDialog(
            editor = e,
            devices = devices,
            onChange = viewModel::updateDraft,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
            onDismiss = viewModel::closeEditor,
        )
    }
}
