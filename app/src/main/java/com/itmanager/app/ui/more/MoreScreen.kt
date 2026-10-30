package com.itmanager.app.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.itmanager.app.core.settings.ServerSettings
import com.itmanager.app.core.settings.SettingsRepository
import com.itmanager.app.ui.components.SectionLabel
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.components.TabTopBar
import com.itmanager.app.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MoreViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    val server: StateFlow<ServerSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Where every section of the app that is not a tab lives, grouped by what the person wants to do. */
enum class MoreItem(val title: String, val subtitle: String, val icon: ImageVector) {
    TOPOLOGY("Схема сети", "Коммутаторы и связи: что к какому порту подключено", Icons.Outlined.AccountTree),
    VLANS("VLAN и подсети", "Справочник VLAN, подсети и их цвета на карте", Icons.Outlined.Lan),
    IPAM("IP-адреса", "Заполнение подсетей, свободные адреса, конфликты", Icons.Outlined.Dns),
    TEMPLATES("Шаблоны устройств", "Типовые коммутаторы и патч-панели с портами", Icons.Outlined.Dashboard),
    PERIPHERALS("Периферия и склад", "Мониторы, клавиатуры, док-станции: где что стоит", Icons.Outlined.Inventory2),
    SCANNER("Сканер сети", "Найти устройства в подсетях и добавить в учёт", Icons.Outlined.TravelExplore),
    TOOLS("Сетевые утилиты", "Ping, трассировка, порты, SNMP, iperf3, Wake-on-LAN", Icons.Outlined.Construction),
    PCS("Компьютеры домена", "Сведения и ключи, диски, процессы, очистка, перезагрузка", Icons.Outlined.Computer),
    GPO("Групповые политики", "Какие действуют, где и на каких ПК; правка и применение", Icons.Outlined.Policy),
    RUNBOOKS("Сценарии", "Сброс пароля AD, права на папки, резервные копии", Icons.Outlined.Terminal),
    AUDIT("Журнал", "Кто, когда и что запускал и менял", Icons.AutoMirrored.Outlined.ManageSearch),
    ACCESS("Сервер и доступ", "Подключение, безопасность, параметры сервера, учётные данные", Icons.Outlined.Settings),
}

private val GROUPS = listOf(
    "Учёт" to listOf(MoreItem.TOPOLOGY, MoreItem.VLANS, MoreItem.IPAM, MoreItem.TEMPLATES, MoreItem.PERIPHERALS),
    "Диагностика" to listOf(MoreItem.SCANNER, MoreItem.TOOLS),
    "Windows" to listOf(MoreItem.PCS, MoreItem.GPO, MoreItem.RUNBOOKS),
    "Система" to listOf(MoreItem.AUDIT, MoreItem.ACCESS),
)

/** The parts of «Сервер и доступ»; each opens the screen that already existed for it. */
enum class AccessItem(val title: String, val subtitle: String, val icon: ImageVector) {
    CONNECTION("Подключение и безопасность", "Адрес сервера, ключ, QR-код, вход по биометрии", Icons.Outlined.Settings),
    SERVER("Параметры сервера", "Коммутаторы, SNMP, компьютеры домена, резервные копии — без правки файла", Icons.Outlined.Tune),
    CREDENTIALS("Учётные данные", "Логины SSH, SNMPv3 и WinRM на сервере", Icons.Outlined.Key),
}

@Composable
fun MoreScreen(
    contentPadding: PaddingValues,
    onOpen: (MoreItem) -> Unit,
    viewModel: MoreViewModel = hiltViewModel(),
) {
    val server by viewModel.server.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TabTopBar(
            title = "Ещё",
            subtitle = server?.let { if (it.isConfigured) "Сервер: ${it.baseUrl.removePrefix("http://")}" else "Сервер не настроен" },
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (server?.isConfigured == false) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
                            .clickable { onOpen(MoreItem.ACCESS) }
                            .padding(16.dp),
                    ) {
                        StatusDot(StatusColors.Warning)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Подключите сервер NetAdmin: адрес и ключ или QR-код. Без него в приложении нет данных.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            GROUPS.forEach { (title, items) ->
                item(key = title) {
                    Column {
                        SectionLabel(title)
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))) {
                            items.forEachIndexed { i, entry ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                MoreRow(entry.title, entry.subtitle, entry.icon) { onOpen(entry) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** «Сервер и доступ»: one entry in «Ещё» for what used to be three, with the same screens behind it. */
@Composable
fun AccessHubScreen(onBack: () -> Unit, onOpen: (AccessItem) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TabTopBar(title = "Сервер и доступ", onBack = onBack)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)),
        ) {
            AccessItem.entries.forEachIndexed { i, entry ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                MoreRow(entry.title, entry.subtitle, entry.icon) { onOpen(entry) }
            }
        }
    }
}
