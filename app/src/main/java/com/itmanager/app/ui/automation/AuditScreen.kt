package com.itmanager.app.ui.automation

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.AuditEntry
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.components.formatAgo
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fullTime: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

private val filters = listOf(null to "Все", AuditKind.RUNBOOK to "Сценарии", AuditKind.SWITCH to "Коммутаторы")

/** Who ran what, when, with which arguments and what came out. Tap a row for the full record. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AuditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Журнал аудита", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                filters.forEachIndexed { i, (kind, label) ->
                    SegmentedButton(
                        selected = filter == kind,
                        onClick = { viewModel.setFilter(kind) },
                        shape = SegmentedButtonDefaults.itemShape(i, filters.size),
                    ) { Text(label) }
                }
            }
            when (val s = state) {
                UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, viewModel::refresh, onOpenSettings)
                is UiState.Content -> {
                    if (s.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        if (s.data.isEmpty()) item { EmptyView("Записей пока нет") }
                        items(s.data, key = { it.id }) { entry ->
                            AuditCard(
                                entry = entry,
                                expanded = expanded == entry.id,
                                onToggle = { expanded = if (expanded == entry.id) null else entry.id },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditCard(entry: AuditEntry, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .animateContentSize()
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            ) {
                val icon = if (entry.kind == AuditKind.RUNBOOK) runbookIcon(entry.action.removePrefix("runbook:")) else Icons.Outlined.Router
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(entry.executedAt?.let { formatAgo(it) }, entry.triggeredBy.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExitCodeLabel(entry.exitCode)
        }
        if (!expanded && entry.arguments.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                entry.arguments.joinToString(" · ") { it.second },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            entry.executedAt?.let { Field("Время", fullTime.format(it.atZone(ZoneId.systemDefault()))) }
            Field("Инициатор", entry.triggeredBy.ifBlank { "—" })
            entry.authMethod?.let { Field("Подтверждение", it.label) }
            entry.durationMs?.let { Field("Длительность", formatDuration(it)) }
            entry.arguments.forEach { (key, value) -> Field(argumentLabel(key), value) }
            Spacer(Modifier.height(8.dp))
            TerminalBlock(entry.stdout, entry.stderr)
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
