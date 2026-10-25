package com.itmanager.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Print
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

@Serializable data object MapRoute
@Serializable data object DevicesRoute
@Serializable data object PrintersRoute
@Serializable data object AutomationRoute
@Serializable data object SettingsRoute
@Serializable data class SwitchRoute(val deviceId: String)

/** Bottom bar tabs. Labels name what is inside, not generic "Home"-style umbrellas. */
enum class TopLevelDestination(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    MAP(MapRoute, "Карта", Icons.Filled.Map, Icons.Outlined.Map),
    DEVICES(DevicesRoute, "Устройства", Icons.Filled.Devices, Icons.Outlined.Devices),
    PRINTERS(PrintersRoute, "МФУ", Icons.Filled.Print, Icons.Outlined.Print),
    AUTOMATION(AutomationRoute, "Автоматизация", Icons.Filled.Terminal, Icons.Outlined.Terminal),
}
