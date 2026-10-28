package com.itmanager.app.ui.printers

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.Consumable
import com.itmanager.app.data.model.ConsumableKind
import com.itmanager.app.data.model.Printer
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.components.TabTopBar
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.components.formatAgo
import com.itmanager.app.ui.theme.Ink950
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.Motion
import com.itmanager.app.ui.theme.StatusColors
import java.text.NumberFormat
import java.util.Locale

@Composable
fun PrintersScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: PrintersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val polling by viewModel.polling.collectAsStateWithLifecycle()
    val emulated by viewModel.emulated.collectAsStateWithLifecycle()
    val printers = (state as? UiState.Content)?.data
    val lowCount = printers?.count { it.hasLowConsumable } ?: 0
    val silentCount = printers?.count { !it.isOnline } ?: 0
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabTopBar(
                title = "МФУ",
                subtitle = printers?.let {
                    listOfNotNull(
                        if (lowCount > 0) "Нужна замена расходников: $lowCount" else "Расходников хватает",
                        "не отвечают: $silentCount".takeIf { silentCount > 0 },
                    ).joinToString(" · ")
                },
                onBack = onBack,
            )
            when (val s = state) {
                UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, viewModel::retry, onOpenSettings, contentPadding = contentPadding)
                is UiState.Content -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (emulated) {
                        item {
                            Text(
                                "Сервер не опрашивает МФУ: в его настройках MONITOR_MODE=emulator, и уровни тонера и " +
                                    "счётчики страниц здесь ненастоящие. Поставьте MONITOR_MODE=real в netadmin.env и " +
                                    "перезапустите службу NetAdmin.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp))
                                    .padding(14.dp),
                            )
                        }
                    }
                    if (s.data.isEmpty()) item { EmptyView("МФУ в учёте нет. Добавьте их на карте с типом «МФУ» и IP-адресом: сервер начнёт опрашивать расходники.") }
                    items(s.data, key = { it.id }) { printer ->
                        PrinterCard(
                            printer = printer,
                            isPolling = printer.id in polling,
                            onPoll = { viewModel.poll(printer) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        )
    }
}

private val pageFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru"))

@Composable
fun PrinterCard(
    printer: Printer,
    isPolling: Boolean,
    onPoll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Print, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (printer.isOnline) StatusColors.Online else StatusColors.Offline)
                    Spacer(Modifier.width(6.dp))
                    Text(printer.name, style = MaterialTheme.typography.titleMedium)
                    if (printer.hasLowConsumable) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = "Заканчивается расходник",
                            tint = StatusColors.Warning,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                printer.model?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    printer.ip?.let {
                        Text(it, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        "${pageFormat.format(printer.totalPages)} стр.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
            IconButton(onClick = onPoll, enabled = !isPolling) {
                if (isPolling) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Опросить сейчас")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // A silent printer shows its last known levels, dimmed: they may be out of date.
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(end = 12.dp).alpha(if (printer.isOnline) 1f else 0.5f),
        ) {
            printer.consumables.forEach { ConsumableBar(it) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                !printer.isOnline -> "Не отвечает по SNMP" + (printer.polledAt?.let { ", данные " + formatAgo(it) } ?: "")
                printer.polledAt != null -> "Опрошен " + formatAgo(printer.polledAt)
                else -> "Ещё не опрашивался"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (printer.isOnline) MaterialTheme.colorScheme.onSurfaceVariant else StatusColors.Offline,
        )
    }
}

@Composable
private fun ConsumableBar(item: Consumable) {
    // Bars fill from empty when they first appear, so low levels are noticed rather than just shown.
    val progress = remember(item) { Animatable(0f) }
    LaunchedEffect(item) { progress.animateTo(item.levelPercent / 100f, Motion.smooth(0.6f)) }

    // Black toner on a dark card would vanish, so very dark colors get a light outline-tone instead.
    val barColor = if (item.colorArgb.let { Color(it) }.luminance() < 0.05f) Color(0xFFD1D5DB) else Color(item.colorArgb)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (item.kind == ConsumableKind.WASTE) "${item.name} (свободно)" else item.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.width(96.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Ink950),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(5.dp))
                    .background(barColor),
            )
        }
        Text(
            "${item.levelPercent}%",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (item.isLow) StatusColors.Offline else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}
