package com.itmanager.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.itmanager.app.ui.automation.AutomationScreen
import com.itmanager.app.ui.devices.DevicesScreen
import com.itmanager.app.ui.map.MapScreen
import com.itmanager.app.ui.printers.PrintersScreen
import com.itmanager.app.ui.settings.SettingsScreen
import com.itmanager.app.ui.switchdetail.SwitchScreen
import com.itmanager.app.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val isTopLevel = destination == null ||
        TopLevelDestination.entries.any { tab -> destination.hasRoute(tab.route::class) }
    val hazeState = rememberHazeState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Enters and leaves along the same path: down off the screen and back up.
            AnimatedVisibility(
                visible = isTopLevel,
                enter = slideInVertically(Motion.smooth(Motion.RESPONSE_SHEET, IntOffset.VisibilityThreshold)) { it },
                exit = slideOutVertically(Motion.smooth(Motion.RESPONSE_SHEET, IntOffset.VisibilityThreshold)) { it },
            ) {
                BottomBar(navController, hazeState)
            }
        },
    ) { padding ->
        // Content runs under the translucent bar; screens pad their scrollable content instead.
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(hazeState),
        ) {
            AppNavHost(navController, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()))
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController, hazeState: HazeState) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val haptics = LocalHapticFeedback.current
    val material = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.background,
        tint = HazeTint(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
        blurRadius = 24.dp,
    )

    Box(Modifier.hazeEffect(hazeState, material)) {
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
            TopLevelDestination.entries.forEach { tab ->
                val selected = backStackEntry?.destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                    label = { Text(tab.label, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
        // A hairline where the glass meets content, like light catching the top edge of the material.
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            thickness = 0.5.dp,
            color = Color.White.copy(alpha = 0.08f),
        )
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, contentPadding: PaddingValues) {
    val openSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } }
    val tabFade = Motion.smooth<Float>(Motion.RESPONSE_SHEET)
    val push = Motion.smooth<IntOffset>(Motion.RESPONSE_DEFAULT, IntOffset.VisibilityThreshold)

    NavHost(
        navController = navController,
        startDestination = MapRoute,
        // Tabs cross-fade; pushed screens slide in from the right and leave the way they came.
        enterTransition = { fadeIn(tabFade) },
        exitTransition = { fadeOut(tabFade) },
        popEnterTransition = { fadeIn(tabFade) },
        popExitTransition = { fadeOut(tabFade) },
    ) {
        composable<MapRoute> {
            MapScreen(
                contentPadding = contentPadding,
                onOpenSwitch = { navController.navigate(SwitchRoute(it)) },
                onOpenSettings = openSettings,
            )
        }
        composable<DevicesRoute> {
            DevicesScreen(
                contentPadding = contentPadding,
                onOpenSwitch = { navController.navigate(SwitchRoute(it)) },
                onShowOnMap = {
                    navController.navigate(MapRoute) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onOpenSettings = openSettings,
            )
        }
        composable<PrintersRoute> {
            PrintersScreen(contentPadding = contentPadding, onOpenSettings = openSettings)
        }
        composable<AutomationRoute> {
            AutomationScreen(contentPadding = contentPadding, onOpenSettings = openSettings)
        }
        composable<SwitchRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) { entry ->
            SwitchScreen(
                deviceId = entry.toRoute<SwitchRoute>().deviceId,
                onBack = { navController.popBackStack() },
                onOpenSettings = openSettings,
            )
        }
        composable<SettingsRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
