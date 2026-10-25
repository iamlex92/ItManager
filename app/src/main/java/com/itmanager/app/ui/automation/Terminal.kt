package com.itmanager.app.ui.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.theme.MonoStyle
import com.itmanager.app.ui.theme.StatusColors

fun runbookIcon(id: String): ImageVector = when (id) {
    "ad_reset_password" -> Icons.Outlined.Password
    "ad_unlock_account" -> Icons.Outlined.LockOpen
    "ad_set_enabled" -> Icons.Outlined.PersonOff
    "fs_effective_access" -> Icons.Outlined.FolderShared
    "backup_database" -> Icons.Outlined.Backup
    "vm_snapshot" -> Icons.Outlined.Storage
    "service_restart" -> Icons.Outlined.RestartAlt
    else -> Icons.Outlined.Terminal
}

/** Readable names for argument keys in the audit log; unknown keys are shown as they are. */
fun argumentLabel(key: String): String = when (key) {
    "account" -> "Учётная запись"
    "action" -> "Действие"
    "principal" -> "Пользователь или группа"
    "path" -> "Папка"
    "database" -> "База данных"
    "vm" -> "Виртуальная машина"
    "name" -> "Имя снапшота"
    "service" -> "Служба"
    "device" -> "Коммутатор"
    "port", "port_name" -> "Порт"
    "target" -> "Адрес"
    "count" -> "Пакетов"
    "hal_mode" -> "Режим сервера"
    "subnets" -> "Подсети"
    "mode" -> "Режим"
    "scan_id" -> "Сканирование"
    "ips" -> "Адреса"
    "links" -> "Со связями"
    else -> key
}

fun formatDuration(ms: Long): String = if (ms < 1_000) "$ms мс" else "%.1f с".format(ms / 1_000.0)

/** Exit code with a dot: green for 0, red otherwise, the way a terminal prompt would hint it. */
@Composable
fun ExitCodeLabel(exitCode: Int?, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        StatusDot(if (exitCode == 0) StatusColors.Online else StatusColors.Offline)
        Spacer(Modifier.width(6.dp))
        Text(
            if (exitCode == null) "без кода" else "код $exitCode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Command output as a terminal shows it: stdout, then stderr in red. Scrolls both ways. */
@Composable
fun TerminalBlock(stdout: String, stderr: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        if (stdout.isNotBlank()) Text(stdout, style = MonoStyle, softWrap = false)
        if (stderr.isNotBlank()) Text(stderr, style = MonoStyle, softWrap = false, color = MaterialTheme.colorScheme.error)
        if (stdout.isBlank() && stderr.isBlank()) {
            Text("Вывода нет", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
