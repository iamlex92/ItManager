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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.itmanager.app.data.model.AlertSeverity
import com.itmanager.app.ui.alerts.AlertNoticeBanner
import com.itmanager.app.ui.alerts.AlertsScreen
import com.itmanager.app.ui.automation.AuditScreen
import com.itmanager.app.ui.automation.AutomationScreen
import com.itmanager.app.ui.components.AlertsBellState
import com.itmanager.app.ui.components.LocalAlertsBell
import com.itmanager.app.ui.configdiff.ConfigDiffScreen
import com.itmanager.app.ui.configimport.ConfigImportScreen
import com.itmanager.app.ui.credentials.CredentialsScreen
import com.itmanager.app.ui.devices.DevicesScreen
import com.itmanager.app.ui.history.HistoryScreen
import com.itmanager.app.ui.map.MapScreen
import com.itmanager.app.ui.nfc.BindTagSheet
import com.itmanager.app.ui.peripherals.PeripheralsScreen
import com.itmanager.app.ui.topology.TopologyScreen
import com.itmanager.app.ui.vlans.VlansScreen
import com.itmanager.app.ui.printers.PrintersScreen
import com.itmanager.app.ui.scanner.ScannerScreen
import com.itmanager.app.ui.security.AppLockOverlay
import com.itmanager.app.ui.settings.SettingsScreen
import com.itmanager.app.ui.tools.ToolsHubScreen
import com.itmanager.app.ui.switchdetail.SwitchScreen
import com.itmanager.app.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay

@Composable
fun AppRoot(appViewModel: AppViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val isTopLevel = destination == null ||
        TopLevelDestination.entries.any { tab -> destination.hasRoute(tab.route::class) }
    val hazeState = rememberHazeState()
    val snackbar = remember { SnackbarHostState() }
    val openAlertCount by appViewModel.openAlertCount.collectAsStateWithLifecycle()
    val notice by appViewModel.notice.collectAsStateWithLifecycle()
    val binding by appViewModel.binding.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Live alerts only while the app is on screen.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { appViewModel.followAlerts() }
    }
    LaunchedEffect(appViewModel) {
        appViewModel.navigation.collect { target ->
            when (target) {
                is AppNavigation.OpenSwitch -> navController.navigate(SwitchRoute(target.deviceId)) { launchSingleTop = true }
                AppNavigation.ShowOnMap -> navController.navigate(MapRoute) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        haptics.performHapticFeedback(
            if (current.alert.severity == AlertSeverity.CRITICAL) HapticFeedbackType.Reject else HapticFeedbackType.ContextClick,
        )
        delay(6_000)
        appViewModel.dismissNotice()
    }

    val bell = AlertsBellState(openAlertCount) { navController.navigate(AlertsRoute) { launchSingleTop = true } }
    CompositionLocalProvider(LocalAlertsBell provides bell) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
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
                AppNavHost(
                    navController,
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                    onOpenDevice = appViewModel::openDevice,
                )

                // The last notice stays composed while it slides away.
                var lastNotice by remember { mutableStateOf<AlertNotice?>(null) }
                if (notice != null) lastNotice = notice
                AnimatedVisibility(
                    visible = notice != null,
                    enter = slideInVertically(Motion.smooth(Motion.RESPONSE_SHEET, IntOffset.VisibilityThreshold)) { -it } + fadeIn(),
                    exit = slideOutVertically(Motion.smooth(Motion.RESPONSE_SHEET, IntOffset.VisibilityThreshold)) { -it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    lastNotice?.let {
                        AlertNoticeBanner(it, onOpen = appViewModel::openNotice, onDismiss = appViewModel::dismissNotice)
                    }
                }
            }
        }
    }

    val lock by appViewModel.lock.collectAsStateWithLifecycle()
    AppLockOverlay(lock, lifecycleOwner, onUnlocked = appViewModel::unlock)

    binding?.let { state ->
        BindTagSheet(
            state = state,
            checkAvailability = appViewModel::nfcAvailability,
            onConfirmMove = appViewModel::confirmMove,
            onClose = appViewModel::cancelBinding,
        )
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
private fun AppNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    onOpenDevice: (deviceId: String) -> Unit,
) {
    val openSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } }
    val openScanner = { navController.navigate(ScannerRoute) { launchSingleTop = true } }
    val openMapTab = {
        navController.navigate(MapRoute) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
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
                onOpenScanner = openScanner,
                onOpenVlans = { navController.navigate(VlansRoute) { launchSingleTop = true } },
            )
        }
        composable<VlansRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) {
            VlansScreen(onBack = { navController.popBackStack() }, onOpenSettings = openSettings)
        }
        composable<DevicesRoute> {
            DevicesScreen(
                contentPadding = contentPadding,
                onOpenSwitch = { navController.navigate(SwitchRoute(it)) },
                onShowOnMap = openMapTab,
                onOpenSettings = openSettings,
                onOpenScanner = openScanner,
                onOpenPeripherals = { navController.navigate(PeripheralsRoute) { launchSingleTop = true } },
            )
        }
        composable<TopologyRoute> {
            TopologyScreen(
                contentPadding = contentPadding,
                onOpenSwitch = { navController.navigate(SwitchRoute(it)) },
                onShowOnMap = openMapTab,
                onOpenSettings = openSettings,
            )
        }
        composable<PeripheralsRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) {
            PeripheralsScreen(onBack = { navController.popBackStack() }, onOpenSettings = openSettings)
        }
        composable<PrintersRoute> {
            PrintersScreen(contentPadding = contentPadding, onOpenSettings = openSettings)
        }
        composable<AutomationRoute> {
            AutomationScreen(
                contentPadding = contentPadding,
                onOpenSettings = openSettings,
                onOpenAudit = { navController.navigate(AuditRoute) { launchSingleTop = true } },
                onOpenScanner = openScanner,
                onOpenTools = { navController.navigate(ToolsRoute) { launchSingleTop = true } },
            )
        }
        composable<ScannerRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) {
            ScannerScreen(onBack = { navController.popBackStack() }, onOpenMap = openMapTab)
        }
        composable<AuditRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) {
            AuditScreen(onBack = { navController.popBackStack() }, onOpenSettings = openSettings)
        }
        composable<SwitchRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) { entry ->
            val deviceId = entry.toRoute<SwitchRoute>().deviceId
            SwitchScreen(
                deviceId = deviceId,
                onBack = { navController.popBackStack() },
                onOpenSettings = openSettings,
                onOpenDiff = { name -> navController.navigate(ConfigDiffRoute(deviceId, name)) },
                onOpenHistory = { name -> navController.navigate(HistoryRoute(deviceId, name)) },
                onOpenImport = { name -> navController.navigate(ConfigImportRoute(deviceId, name)) },
            )
        }
        composable<HistoryRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) {
            HistoryScreen(onBack = { navController.popBackStack() }, onOpenSettings = openSettings)
        }
        composable<ConfigDiffRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) { entry ->
            ConfigDiffScreen(
                switchName = entry.toRoute<ConfigDiffRoute>().switchName,
                onBack = { navController.popBackStack() },
            )
        }
        composable<ConfigImportRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) { entry ->
            ConfigImportScreen(
                switchName = entry.toRoute<ConfigImportRoute>().switchName,
                onBack = { navController.popBackStack() },
            )
        }
        composable<AlertsRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) {
            AlertsScreen(
                onBack = { navController.popBackStack() },
                onOpenDevice = onOpenDevice,
                onOpenSettings = openSettings,
            )
        }
        composable<SettingsRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenCredentials = { navController.navigate(CredentialsRoute) { launchSingleTop = true } },
            )
        }
        composable<ToolsRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) {
            ToolsHubScreen(
                onBack = { navController.popBackStack() },
                onOpen = { kind -> navController.navigate(ToolRoute(kind)) { launchSingleTop = true } },
            )
        }
        composable<ToolRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) { entry ->
            com.itmanager.app.ui.tools.ToolRoute(
                kind = entry.toRoute<ToolRoute>().kind,
                host = "",
                onBack = { navController.popBackStack() },
            )
        }
        composable<CredentialsRoute>(
            enterTransition = { slideInHorizontally(push) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) {
            CredentialsScreen(onBack = { navController.popBackStack() })
        }
    }
}
