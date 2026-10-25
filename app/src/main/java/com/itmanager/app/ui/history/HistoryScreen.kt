package com.itmanager.app.ui.history

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.DeviceHistoryEntry
import com.itmanager.app.data.model.HistoryKind
import com.itmanager.app.ui.automation.TerminalBlock
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.components.formatAgo
import com.itmanager.app.ui.theme.StatusColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fullTime: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/** Every VLAN, mode and shutdown change on one switch, config saves and copies. Tap a row for the commands. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("История изменений", style = MaterialTheme.typography.titleLarge)
                        Text(viewModel.switchName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
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
                        if (s.data.isEmpty()) {
                            item { EmptyView("Изменений пока не было") }
                        }
                        itemsIndexed(s.data) { i, entry ->
                            HistoryCard(entry, expanded == i, onToggle = { expanded = if (expanded == i) null else i })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(entry: DeviceHistoryEntry, expanded: Boolean, onToggle: () -> Unit) {
    val (icon, tint, status) = when (entry.kind) {
        HistoryKind.CHANGE -> Triple(Icons.Outlined.Tune, MaterialTheme.colorScheme.primary, null)
        HistoryKind.BLOCKED -> Triple(Icons.Outlined.Block, StatusColors.Warning, "заблокировано сервером")
        HistoryKind.FAILED -> Triple(Icons.Outlined.ErrorOutline, StatusColors.Offline, "ошибка")
        HistoryKind.CONFIG_BACKUP -> Triple(Icons.Outlined.Inventory2, MaterialTheme.colorScheme.onSurfaceVariant, null)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = entry.commands.isNotEmpty(), role = Role.Button, onClick = onToggle)
            .animateContentSize()
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.summary, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        entry.title,
                        status,
                        entry.at?.let { formatAgo(it) },
                        entry.actor.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            entry.at?.let {
                Text(
                    fullTime.format(it.atZone(ZoneId.systemDefault())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }
            TerminalBlock(entry.commands.joinToString("\n"), "")
        }
    }
}
