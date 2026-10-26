package com.itmanager.app.ui.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itmanager.app.core.security.BiometricGate
import com.itmanager.app.core.security.GateResult
import com.itmanager.app.core.security.findFragmentActivity
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.Runbook
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.TabTopBar
import com.itmanager.app.ui.components.UiState
import kotlinx.coroutines.launch

/** Runbook catalog grouped by area, with the audit log one tap away. */
@Composable
fun AutomationScreen(
    contentPadding: PaddingValues,
    onOpenSettings: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenTools: () -> Unit,
    viewModel: AutomationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val isDemo by viewModel.isDemo.collectAsStateWithLifecycle()
    val serverMode by viewModel.serverMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TabTopBar(title = "Автоматизация", subtitle = "Регламентные операции", onOpenSettings = onOpenSettings)
        when (val s = state) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(s.message, viewModel::retry, onOpenSettings, contentPadding = contentPadding)
            is UiState.Content -> {
                if (s.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "note") { EmulationNote(isDemo, serverMode) }
                    item(key = "scanner") {
                        LinkCard(
                            icon = Icons.Outlined.TravelExplore,
                            title = "Сканер сети",
                            subtitle = "Найти устройства в подсетях, построить связи и добавить в учёт",
                            onClick = onOpenScanner,
                        )
                    }
                    item(key = "tools") {
                        LinkCard(
                            icon = Icons.Outlined.Construction,
                            title = "Сетевые утилиты",
                            subtitle = "Ping, трассировка, порты, SNMP, iperf3, Wake-on-LAN, MAC и подсети с телефона",
                            onClick = onOpenTools,
                        )
                    }
                    item(key = "audit") {
                        LinkCard(
                            icon = Icons.AutoMirrored.Outlined.ManageSearch,
                            title = "Журнал аудита",
                            subtitle = "Кто, когда и с какими параметрами запускал сценарии, менял коммутаторы и сканировал сеть",
                            onClick = onOpenAudit,
                        )
                    }
                    s.data.groupBy { it.category }.forEach { (category, runbooks) ->
                        item(key = "cat-$category") { SectionLabel(category, Modifier.padding(top = 16.dp)) }
                        items(runbooks, key = { it.id }) { rb -> RunbookRow(rb) { viewModel.open(rb) } }
                    }
                }
            }
        }
    }

    session?.let { current ->
        RunbookSheet(
            session = current,
            onValue = viewModel::setValue,
            onRun = {
                scope.launch {
                    if (viewModel.validate() != null) return@launch
                    val rb = current.runbook
                    if (!rb.critical) {
                        viewModel.run(AuthMethod.NOT_REQUIRED)
                        return@launch
                    }
                    BiometricGate.unavailableReason(context)?.let {
                        viewModel.fail(it)
                        return@launch
                    }
                    val activity = context.findFragmentActivity() ?: return@launch viewModel.fail("Не удалось показать окно подтверждения.")
                    when (val gate = BiometricGate.confirm(activity, rb.title, "Подтвердите запуск сценария")) {
                        is GateResult.Confirmed -> viewModel.run(gate.method)
                        GateResult.Cancelled -> Unit
                        is GateResult.Failed -> viewModel.fail("Подтверждение не прошло: ${gate.message}")
                    }
                }
            },
            onAgain = viewModel::again,
            onDismiss = viewModel::close,
        )
    }
}

@Composable
private fun EmulationNote(isDemo: Boolean, server: ServerRunbooks?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Icon(Icons.Outlined.Science, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(12.dp))
        Text(
            when {
                isDemo -> "Демо-режим: сценарии выполняются в эмуляции внутри приложения. AD и серверы не затрагиваются."
                server?.real == true && server.writes ->
                    "Сценарии работают с настоящим AD. Изменения разрешены на сервере, каждое подтверждается биометрией."
                server?.real == true ->
                    "Сценарии читают настоящий AD и права на папки. Изменения на сервере выключены: покажется только, что нужно сделать."
                else -> "Сценарии выполняются на сервере в эмулированной среде: настоящие AD и серверы не затрагиваются."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun LinkCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RunbookRow(runbook: Runbook, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        ) {
            Icon(runbookIcon(runbook.id), contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(runbook.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(runbook.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (runbook.critical) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.Fingerprint,
                contentDescription = "Нужна биометрия",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
