package com.itmanager.app.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.AlertSeverity
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.components.TabTopBar
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.components.formatAgo
import com.itmanager.app.ui.theme.StatusColors

val AlertSeverity.color: Color
    get() = when (this) {
        AlertSeverity.CRITICAL -> StatusColors.Offline
        AlertSeverity.WARNING -> StatusColors.Warning
        AlertSeverity.INFO -> StatusColors.Info
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    onOpenDevice: (deviceId: String) -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showResolved by viewModel.showResolved.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TabTopBar(
                title = "События",
                subtitle = (state as? UiState.Content)?.data?.let { d ->
                    if (d.openCount > 0) "Открытых: ${d.openCount}" else "Открытых нет"
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            UiState.Loading -> LoadingView(Modifier.padding(padding))
            is UiState.Error -> ErrorView(s.message, viewModel::refresh, onOpenSettings, Modifier.padding(padding))
            is UiState.Content -> Column(Modifier.fillMaxSize().padding(padding)) {
                if (s.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SegmentedButton(
                        selected = !showResolved,
                        onClick = { viewModel.setShowResolved(false) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Открытые · ${s.data.openCount}") }
                    SegmentedButton(
                        selected = showResolved,
                        onClick = { viewModel.setShowResolved(true) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Все") }
                }
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (s.data.rows.isEmpty()) {
                        item { EmptyView(if (showResolved) "Событий нет" else "Открытых событий нет, всё работает") }
                    }
                    items(s.data.rows, key = { it.alert.id }) { row ->
                        AlertCard(
                            row = row,
                            isResolving = row.alert.id in resolving,
                            onOpenDevice = { onOpenDevice(row.alert.deviceId) },
                            onResolve = { viewModel.resolve(row.alert) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertCard(
    row: AlertRow,
    isResolving: Boolean,
    onOpenDevice: () -> Unit,
    onResolve: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alert = row.alert
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp))
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)
            // Handled incidents stay readable but step back.
            .alpha(if (alert.isResolved) 0.6f else 1f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (alert.isResolved) StatusColors.Online else alert.severity.color, size = 10.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (alert.isResolved) "Решено" else alert.severity.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (alert.isResolved) StatusColors.Online else alert.severity.color,
            )
            Spacer(Modifier.width(8.dp))
            Text(alert.typeLabel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            (alert.resolvedAt.takeIf { alert.isResolved } ?: alert.createdAt)?.let {
                Text(
                    formatAgo(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        row.deviceName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(
            alert.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onOpenDevice) { Text("Открыть устройство") }
            Spacer(Modifier.weight(1f))
            if (!alert.isResolved) {
                FilledTonalButton(onClick = onResolve, enabled = !isResolving) {
                    if (isResolving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Решено")
                    }
                }
            }
        }
    }
}
