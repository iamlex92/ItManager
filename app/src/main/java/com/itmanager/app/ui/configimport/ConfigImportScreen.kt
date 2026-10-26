package com.itmanager.app.ui.configimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.data.model.ConfigImportResult
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.StatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigImportScreen(
    switchName: String?,
    onBack: () -> Unit,
    viewModel: ConfigImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Импорт конфигурации", style = MaterialTheme.typography.titleLarge)
                        switchName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "Вставьте вывод show running-config или show vlan brief (Cisco, Eltex). " +
                    "Приложение сверит интерфейсы с портами в базе и обновит описание, режим, VLAN и состояние. " +
                    "На сам коммутатор ничего не отправляется.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::setText,
                label = { Text("Текст конфигурации") },
                textStyle = MonoStyle,
                minLines = 8,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::preview, enabled = state.text.isNotBlank() && !state.busy) {
                    Text("Показать изменения")
                }
                val r = state.result
                Button(
                    onClick = viewModel::apply,
                    enabled = r != null && !r.applied && r.changedPorts > 0 && !state.busy,
                ) { Text("Применить в базу") }
                if (state.busy) CircularProgressIndicator(Modifier.padding(start = 4.dp).height(32.dp))
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
            state.result?.let { ResultView(it) }
        }
    }
}

@Composable
private fun ResultView(r: ConfigImportResult) {
    Column(Modifier.padding(top = 16.dp)) {
        val kind = if (r.kind == "vlan_brief") "show vlan brief" else "running-config"
        val title = buildString {
            append(kind)
            r.hostname?.let { append(" · ").append(it) }
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                r.applied -> "Сохранено в базу: портов изменено ${r.changedPorts}."
                r.changedPorts == 0 -> "Изменений нет: база уже совпадает с текстом."
                else -> "Будет изменено портов: ${r.changedPorts}. Пока ничего не записано."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (r.applied) StatusColors.Online else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        r.ports.filter { it.changes.isNotEmpty() }.forEach { p ->
            Text(p.port, style = MonoStyle, modifier = Modifier.padding(top = 8.dp))
            p.changes.forEach { c ->
                Text(
                    "${c.field}: ${c.current} → ${c.imported}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        if (r.unknownVlans.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("VLAN, которых нет в базе (не созданы):", style = MaterialTheme.typography.titleSmall, color = StatusColors.Warning)
            Text(
                r.unknownVlans.entries.joinToString { "${it.key} ${it.value}".trim() },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (r.unmatchedInterfaces.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Интерфейсы без порта в базе:", style = MaterialTheme.typography.titleSmall, color = StatusColors.Warning)
            Text(r.unmatchedInterfaces.joinToString(", "), style = MonoStyle)
        }
    }
}
