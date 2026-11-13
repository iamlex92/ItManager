package com.itmanager.app.ui.lifecycle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.Expiration
import com.itmanager.app.data.model.LicenseInfo
import com.itmanager.app.data.model.LifecycleOverview
import com.itmanager.app.data.model.daysLeftText
import com.itmanager.app.data.model.licenseKindLabel
import com.itmanager.app.data.model.shortDate
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.Led
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.theme.StatusColors

/** «Сроки и лицензии»: what is about to run out, and the licenses with their seats. Keys are shown only on the web console. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifecycleScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDevice: (String) -> Unit,
    viewModel: LifecycleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Сроки и лицензии", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    IconButton(onClick = viewModel::load) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.navigationBars)) {
            when (val s = state) {
                UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, viewModel::load, onOpenSettings)
                is UiState.Content -> LifecycleContent(s.data, onOpenDevice)
            }
        }
    }
}

@Composable
private fun LifecycleContent(data: LifecycleOverview, onOpenDevice: (String) -> Unit) {
    if (data.expirations.isEmpty() && data.licenses.isEmpty()) {
        EmptyView("Сроков и лицензий пока нет. Заполните гарантию в карточке устройства и добавьте лицензии в веб-консоли: раздел «Сроки и лицензии».")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { SectionLabel("Что истекает") }
        if (data.expirations.isEmpty()) {
            item { Text("В ближайший год ничего не истекает.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(data.expirations, key = { "${it.source}:${it.title}:${it.due}" }) { ExpirationRow(it, onOpenDevice) }
        item { SectionLabel("Лицензии") }
        if (data.licenses.isEmpty()) {
            item { Text("Лицензий нет. Добавьте их в веб-консоли.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(data.licenses, key = { it.id }) { LicenseRow(it) }
    }
}

private fun levelColor(level: String): Color = when (level) {
    "expired", "week" -> StatusColors.Offline
    "month", "soon" -> StatusColors.Warning
    else -> StatusColors.Online
}

@Composable
private fun ExpirationRow(item: Expiration, onOpenDevice: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .then(if (item.deviceId != null) Modifier.clickable { onOpenDevice(item.deviceId) } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Led(levelColor(item.level), size = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${shortDate(item.due).orEmpty()} · ${daysLeftText(item.daysLeft)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (item.level == "expired" || item.level == "week") StatusColors.Offline else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LicenseRow(item: LicenseInfo) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(item.software, style = MaterialTheme.typography.bodyLarge)
        val seats = item.seats?.let { "${item.used} из $it мест" } ?: "${item.used} уст."
        Text(
            listOfNotNull(
                licenseKindLabel(item.kind),
                seats,
                item.validUntil?.let { "до ${shortDate(it)}" } ?: "бессрочная",
                item.supplier,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (item.over) StatusColors.Offline else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.over) {
            Text("Установлено больше, чем куплено", style = MaterialTheme.typography.labelMedium, color = StatusColors.Offline)
        }
    }
}
