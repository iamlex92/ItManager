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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.Consumable
import com.itmanager.app.data.model.Printer
import com.itmanager.app.ui.components.EmptyView
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.TabTopBar
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.theme.Ink950
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.Motion
import com.itmanager.app.ui.theme.StatusColors
import java.text.NumberFormat
import java.util.Locale

@Composable
fun PrintersScreen(
    contentPadding: PaddingValues,
    onOpenSettings: () -> Unit,
    viewModel: PrintersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val printers = (state as? UiState.Content)?.data
    val lowCount = printers?.count { it.hasLowConsumable } ?: 0

    Column(Modifier.fillMaxSize()) {
        TabTopBar(
            title = "МФУ",
            subtitle = if (lowCount > 0) "Нужна замена расходников: $lowCount" else printers?.let { "Расходников хватает" },
            onOpenSettings = onOpenSettings,
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
                if (s.data.isEmpty()) item { EmptyView("МФУ пока не добавлены") }
                items(s.data, key = { it.id }) { PrinterCard(it) }
            }
        }
    }
}

private val pageFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru"))

@Composable
fun PrinterCard(printer: Printer, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Print, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(printer.name, style = MaterialTheme.typography.titleMedium)
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
            if (printer.hasLowConsumable) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = "Заканчивается расходник", tint = StatusColors.Warning)
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            printer.consumables.forEach { ConsumableBar(it) }
        }
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
            item.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
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
