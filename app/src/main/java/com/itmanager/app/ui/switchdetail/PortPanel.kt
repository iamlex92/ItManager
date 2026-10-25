package com.itmanager.app.ui.switchdetail

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itmanager.app.data.model.PortStatus
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.ui.components.StatusDot
import com.itmanager.app.ui.components.pressScale
import com.itmanager.app.ui.theme.Ink950
import com.itmanager.app.ui.theme.Motion
import com.itmanager.app.ui.theme.StatusColors

val PortStatus.color: Color
    get() = when (this) {
        PortStatus.GIGABIT -> StatusColors.Online
        PortStatus.FAST -> StatusColors.LinkFast
        PortStatus.DOWN -> StatusColors.LinkDown
        PortStatus.DISABLED -> StatusColors.Disabled
    }

/**
 * Front panel laid out like the real hardware: odd ports on top, even below, in blocks of 12,
 * then the SFP uplinks. Wide switches scroll sideways rather than shrinking ports below a tappable size.
 */
@Composable
fun PortPanel(
    ports: List<SwitchPort>,
    selectedId: String?,
    onPortClick: (SwitchPort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val copper = ports.filterNot { it.isUplink }
    val uplinks = ports.filter { it.isUplink }
    val blocks = copper.chunked(12)

    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(Ink950, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        blocks.forEach { block ->
            PortBlock(block, selectedId, onPortClick)
        }
        if (uplinks.isNotEmpty()) {
            PortBlock(uplinks, selectedId, onPortClick, sfp = true)
        }
    }
}

@Composable
private fun PortBlock(
    ports: List<SwitchPort>,
    selectedId: String?,
    onPortClick: (SwitchPort) -> Unit,
    sfp: Boolean = false,
) {
    val top = ports.filterIndexed { i, _ -> i % 2 == 0 }
    val bottom = ports.filterIndexed { i, _ -> i % 2 == 1 }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(top, bottom).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { port -> PortCell(port, port.id == selectedId, sfp, onPortClick) }
            }
        }
    }
}

@Composable
private fun PortCell(port: SwitchPort, selected: Boolean, sfp: Boolean, onClick: (SwitchPort) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val fill by animateColorAsState(port.status.color, Motion.smooth(), label = "portColor")
    val textColor = if (port.status == PortStatus.DOWN || port.status == PortStatus.DISABLED) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Color.Black.copy(alpha = 0.8f)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = if (sfp) 34.dp else 28.dp, height = 30.dp)
            .pressScale(interaction, pressedScale = 0.9f)
            .background(fill, RoundedCornerShape(5.dp))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Color.White else Color.Transparent,
                shape = RoundedCornerShape(5.dp),
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onClick(port) }
            .semantics { contentDescription = "Порт ${port.name}, ${port.status.label}" },
    ) {
        Text("${port.number}", fontSize = 10.sp, color = textColor, style = MaterialTheme.typography.labelSmall)
        if (port.isPoeActive) {
            Icon(
                Icons.Filled.Bolt,
                contentDescription = null,
                tint = StatusColors.Poe,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(1.dp)
                    .size(9.dp),
            )
        }
    }
}

@Composable
fun PortLegend(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(PortStatus.GIGABIT.color, "1/10 Гбит/с")
            LegendItem(PortStatus.FAST.color, "10/100 Мбит/с")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(PortStatus.DOWN.color, "Нет линка")
            LegendItem(PortStatus.DISABLED.color, "Shutdown")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = StatusColors.Poe, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("PoE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, size = 10.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
