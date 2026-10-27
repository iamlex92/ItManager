package com.itmanager.app.ui.ipam

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.itmanager.app.data.model.IpamConflict
import com.itmanager.app.data.model.IpamOverview
import com.itmanager.app.data.model.IpamSubnet
import com.itmanager.app.ui.components.ErrorView
import com.itmanager.app.ui.components.LoadingView
import com.itmanager.app.ui.components.UiState
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.StatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpamScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: IpamViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("IP-адреса", style = MaterialTheme.typography.titleLarge) },
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
                is UiState.Content -> IpamContent(s.data, check, viewModel)
            }
        }
    }
}

@Composable
private fun IpamContent(data: IpamOverview, check: IpamCheckState, viewModel: IpamViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "check") { CheckCard(check, viewModel) }
        if (data.conflicts.isNotEmpty()) {
            item(key = "conflicts-title") {
                Text("Конфликты (${data.conflicts.size})", style = MaterialTheme.typography.titleMedium, color = StatusColors.Warning)
            }
            items(data.conflicts, key = { "c-${it.kind}-${it.ip}-${it.devices.joinToString()}" }) { ConflictRow(it) }
        } else {
            item(key = "no-conflicts") {
                Text("Конфликтов адресов нет.", style = MaterialTheme.typography.bodyMedium, color = StatusColors.Online)
            }
        }
        item(key = "subnets-title") { Text("Подсети", style = MaterialTheme.typography.titleMedium) }
        if (data.subnets.isEmpty()) {
            item(key = "no-subnets") {
                Text(
                    "У VLAN не заданы подсети. Задайте их в списке VLAN, и здесь появится заполнение и свободные адреса.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(data.subnets, key = { "s-${it.vlanId}" }) { SubnetCard(it) }
    }
}

@Composable
private fun CheckCard(check: IpamCheckState, viewModel: IpamViewModel) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Text("Проверить адрес", style = MaterialTheme.typography.titleMedium)
        Text(
            "Свободен ли адрес и входит ли он в подсеть VLAN. Ничего не назначает.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = check.ip,
            onValueChange = viewModel::setIp,
            label = { Text("IP-адрес") },
            textStyle = MonoStyle,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = viewModel::checkAddress, enabled = check.ip.isNotBlank() && !check.busy) { Text("Проверить") }
        if (check.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        check.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        check.result?.let { r ->
            val verdict = when {
                !r.valid -> "Это не IP-адрес."
                r.free && r.problems.isEmpty() -> "Свободен" + (r.vlanId?.let { ", подсеть VLAN $it" } ?: "")
                r.free -> "Свободен, но: " + r.problems.joinToString("; ")
                else -> "Занят: " + r.owners.joinToString()
            }
            Text(
                verdict,
                style = MaterialTheme.typography.bodyMedium,
                color = if (r.valid && r.free && r.problems.isEmpty()) StatusColors.Online else StatusColors.Warning,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ConflictRow(c: IpamConflict) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.ip, style = MonoStyle)
            Text(c.devices.joinToString(), style = MaterialTheme.typography.bodyMedium)
        }
        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SubnetCard(s: IpamSubnet) {
    val share = if (s.total > 0) s.used.toFloat() / s.total else 0f
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Text("VLAN ${s.vlanId} · ${s.name}", style = MaterialTheme.typography.titleMedium)
        Text(s.cidr, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth())
        Text(
            "Занято ${s.used} из ${s.total}, свободно ${s.free}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (s.nextFree.isNotEmpty()) {
            Text("Свободные: " + s.nextFree.take(5).joinToString(", "), style = MonoStyle, modifier = Modifier.padding(top = 6.dp))
        }
        if (s.hosts.isNotEmpty()) {
            Text(
                s.hosts.take(6).joinToString("\n") { "${it.ip}  ${it.name}" } + if (s.hosts.size > 6) "\n… ещё ${s.hosts.size - 6}" else "",
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
