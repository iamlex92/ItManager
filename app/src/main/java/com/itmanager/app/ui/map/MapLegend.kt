package com.itmanager.app.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itmanager.app.data.model.Device
import com.itmanager.app.ui.components.DEVICE_TYPES_IN_ORDER
import com.itmanager.app.ui.components.icon
import com.itmanager.app.ui.components.tint
import com.itmanager.app.ui.theme.Ink600
import com.itmanager.app.ui.theme.Ink800
import com.itmanager.app.ui.theme.Motion

/** What each pictogram on this floor means, with how many of each there are. */
@Composable
fun MapLegend(devices: List<Device>, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val counts = devices.groupingBy { it.type }.eachCount()
    val types = DEVICE_TYPES_IN_ORDER.filter { (counts[it] ?: 0) > 0 }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(
            visible = open && types.isNotEmpty(),
            enter = fadeIn(Motion.smooth()) + expandVertically(Motion.smooth(), expandFrom = Alignment.Bottom),
            exit = fadeOut(Motion.smooth()) + shrinkVertically(Motion.smooth(), shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                types.forEach { type ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .background(Ink800, CircleShape)
                                .border(1.dp, Ink600, CircleShape),
                        ) {
                            Icon(type.icon, contentDescription = null, tint = type.tint, modifier = Modifier.size(14.dp))
                        }
                        Text(
                            "${type.label} · ${counts[type]}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
        SmallFloatingActionButton(onClick = onToggle, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(Icons.Outlined.Category, contentDescription = if (open) "Скрыть легенду" else "Легенда: что значат значки")
        }
    }
}
