package com.itmanager.app.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.components.pressScale
import com.itmanager.app.ui.navigation.AlertNotice

/** A new alert while the app is open: drops in from the top, tap to open the device. */
@Composable
fun AlertNoticeBanner(
    notice: AlertNotice,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alert = notice.alert
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 12.dp,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.98f)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            StatusDot(alert.severity.color, size = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(alert.typeLabel, notice.deviceName).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    color = alert.severity.color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(alert.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Скрыть") }
        }
    }
}
