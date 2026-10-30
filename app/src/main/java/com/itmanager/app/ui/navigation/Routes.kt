package com.itmanager.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Map
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
/** First launch: settings opened as a welcome, server first. */
@Serializable data object ConnectRoute
@Serializable data object AlertsRoute
@Serializable data object AuditRoute
@Serializable data object ScannerRoute
@Serializable data object CredentialsRoute
@Serializable data object AccessRoute
@Serializable data object ServerConfigRoute
@Serializable data object GpoRoute
@Serializable data object ToolsRoute
@Serializable data object IpamRoute
@Serializable data object TemplatesRoute
@Serializable data class ToolRoute(val kind: String, val host: String = "")
/** Domain computers; [host] set: that computer opened at once (from a device card), [assist]: with remote help. */
@Serializable data class PcsRoute(val host: String = "", val assist: Boolean = false)
@Serializable data object MoreRoute
@Serializable data class DeviceRoute(val deviceId: String)
@Serializable data class SwitchRoute(val deviceId: String)
@Serializable data class HistoryRoute(val deviceId: String, val switchName: String)
@Serializable data class ConfigDiffRoute(val deviceId: String, val switchName: String? = null)
/** [fromSwitch]: take a copy of the running-config at once and show what it changes in the inventory. */
@Serializable data class ConfigImportRoute(val deviceId: String, val switchName: String? = null, val fromSwitch: Boolean = false)

/** Bottom bar tabs. Labels name what is inside, not generic "Home"-style umbrellas. The network diagram is not one:
 * it is looked at now and then, so it lives in «Ещё». */
enum class TopLevelDestination(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    MAP(MapRoute, "Карта", Icons.Filled.Map, Icons.Outlined.Map),
    DEVICES(DevicesRoute, "Устройства", Icons.Filled.Devices, Icons.Outlined.Devices),
    EVENTS(AlertsRoute, "События", Icons.Filled.Notifications, Icons.Outlined.Notifications),
    MORE(MoreRoute, "Ещё", Icons.Filled.Menu, Icons.Outlined.Menu),
}
