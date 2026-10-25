package com.itmanager.app.ui.automation

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.TabTopBar

private data class Runbook(val title: String, val description: String, val icon: ImageVector)

private val catalog = listOf(
    "Active Directory" to listOf(
        Runbook("Сброс пароля", "Новый пароль со сменой при входе (pwdLastSet = 0)", Icons.Outlined.Password),
        Runbook("Разблокировка учётной записи", "Сброс блокировки после неверных паролей", Icons.Outlined.LockOpen),
        Runbook("Включение и отключение учётной записи", "Для увольнений и длительного отсутствия", Icons.Outlined.PersonOff),
    ),
    "Файловые серверы" to listOf(
        Runbook("Проверка прав на папку", "Эффективные права пользователя или группы (NTFS)", Icons.Outlined.FolderShared),
    ),
    "Инфраструктура" to listOf(
        Runbook("Резервная копия баз данных", "Запуск задания резервного копирования", Icons.Outlined.Backup),
        Runbook("Снапшот виртуальной машины", "Перед обновлением или рискованным изменением", Icons.Outlined.Storage),
        Runbook("Перезапуск службы", "Перезапуск системной службы на сервере", Icons.Outlined.RestartAlt),
    ),
)

/**
 * Placeholder for runbooks: shows what is coming so the tab is not empty,
 * without run buttons that would do nothing.
 */
@Composable
fun AutomationScreen(contentPadding: PaddingValues, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TabTopBar(title = "Автоматизация", subtitle = "Регламентные операции", onOpenSettings = onOpenSettings)
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { ComingSoonNote() }
            catalog.forEach { (category, runbooks) ->
                item(key = category) { SectionLabel(category, Modifier.padding(top = 16.dp)) }
                items(runbooks, key = { it.title }) { RunbookRow(it) }
            }
        }
    }
}

@Composable
private fun ComingSoonNote() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(12.dp))
        Text(
            "Запуск сценариев появится на отдельном этапе. Каждый запуск будет подтверждаться отпечатком пальца и попадать в журнал аудита.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun RunbookRow(runbook: Runbook) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        ) {
            Icon(runbook.icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(runbook.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(runbook.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
