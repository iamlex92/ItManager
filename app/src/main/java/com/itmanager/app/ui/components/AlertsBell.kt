package com.itmanager.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** Open alert count and the way to the alerts screen, provided once at the root for every tab's top bar. */
data class AlertsBellState(val openCount: Int, val onOpen: () -> Unit)

val LocalAlertsBell = compositionLocalOf<AlertsBellState?> { null }

@Composable
fun AlertsBell() {
    val state = LocalAlertsBell.current ?: return
    IconButton(onClick = state.onOpen) {
        BadgedBox(
            badge = {
                if (state.openCount > 0) Badge { Text(if (state.openCount > 99) "99+" else state.openCount.toString()) }
            },
        ) {
            Icon(
                Icons.Outlined.Notifications,
                contentDescription = if (state.openCount > 0) "События: ${state.openCount} открытых" else "События",
            )
        }
    }
}
