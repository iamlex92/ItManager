package com.itmanager.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Print
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

@Serializable data object MapRoute
@Serializable data object DevicesRoute
@Serializable data object TopologyRoute
@Serializable data object PeripheralsRoute
@Serializable data object VlansRoute
@Serializable data object PrintersRoute
@Serializable data object AutomationRoute
@Serializable data object SettingsRoute
@Serializable data object AlertsRoute
@Serializable data object AuditRoute
@Serializable data object ScannerRoute
@Serializable data object CredentialsRoute
@Serializable data object ToolsRoute
@Serializable data class ToolRoute(val kind: String)
@Serializable data class SwitchRoute(val deviceId: String)
@Serializable data class HistoryRoute(val deviceId: String, val switchName: String)
@Serializable data class ConfigDiffRoute(val deviceId: String, val switchName: String? = null)
@Serializable data class ConfigImportRoute(val deviceId: String, val switchName: String? = null)

/** Bottom bar tabs. Labels name what is inside, not generic "Home"-style umbrellas. */
enum class TopLevelDestination(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    MAP(MapRoute, "Карта", Icons.Filled.Map, Icons.Outlined.Map),
    DEVICES(DevicesRoute, "Устройства", Icons.Filled.Devices, Icons.Outlined.Devices),
    TOPOLOGY(TopologyRoute, "Сеть", Icons.Filled.AccountTree, Icons.Outlined.AccountTree),
    PRINTERS(PrintersRoute, "МФУ", Icons.Filled.Print, Icons.Outlined.Print),
    AUTOMATION(AutomationRoute, "Автоматизация", Icons.Filled.Terminal, Icons.Outlined.Terminal),
}
