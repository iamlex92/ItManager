package com.itmanager.app.ui.switchdetail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itmanager.app.data.model.LoadLevel
import com.itmanager.app.data.model.PortLoad
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.PortStatus
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import com.itmanager.app.data.model.formatBps
import com.itmanager.app.data.model.vlanUsage
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

/** What the port colours mean: link state and speed, or the VLANs a port carries. */
enum class PortView(val label: String) { STATUS("Статус"), VLAN("VLAN"), LOAD("Загрузка") }

/** Colours of the VLAN view: one per VLAN id, from the server's VLAN list. */
class VlanPalette(vlans: List<Vlan>) {
    private val colors = vlans.associate { it.id to Color(it.colorArgb) }
    val names = vlans.associate { it.id to it.name }

    /** VLANs missing from the server's list (typed on the switch by hand) still get a neutral colour. */
    fun colorOf(vlanId: Int): Color = colors[vlanId] ?: UnknownVlan

    private companion object {
        val UnknownVlan = Color(0xFF8E8E93)
    }
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
    view: PortView = PortView.STATUS,
    palette: VlanPalette = VlanPalette(emptyList()),
    highlightVlan: Int? = null,
    load: Map<String, PortLoad> = emptyMap(),
) {
    val look = PortLook(view, palette, highlightVlan, load)
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
            PortBlock(block, selectedId, look, onPortClick)
        }
        if (uplinks.isNotEmpty()) {
            PortBlock(uplinks, selectedId, look, onPortClick, sfp = true)
        }
    }
}

@Composable
private fun PortBlock(
    ports: List<SwitchPort>,
    selectedId: String?,
    look: PortLook,
    onPortClick: (SwitchPort) -> Unit,
    sfp: Boolean = false,
) {
    val top = ports.filterIndexed { i, _ -> i % 2 == 0 }
    val bottom = ports.filterIndexed { i, _ -> i % 2 == 1 }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(top, bottom).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { port -> PortCell(port, port.id == selectedId, sfp, look, onPortClick) }
            }
        }
    }
}

private data class PortLook(
    val view: PortView,
    val palette: VlanPalette,
    val highlightVlan: Int?,
    val load: Map<String, PortLoad>,
)

val LoadLevel.color: Color
    get() = when (this) {
        LoadLevel.LOW -> StatusColors.Online
        LoadLevel.MEDIUM -> StatusColors.Warning
        LoadLevel.HIGH -> StatusColors.Offline
    }

/**
 * Port face in the VLAN view: the access VLAN's colour, or one stripe per tagged VLAN on a trunk.
 * Link state stays readable: a port without link is faded, a shut one is drawn as switched off.
 */
private fun vlanFill(port: SwitchPort, palette: VlanPalette): Brush {
    val colors = port.vlanIds.map(palette::colorOf)
    return when {
        port.status == PortStatus.DISABLED || colors.isEmpty() -> SolidColor(StatusColors.Disabled)
        colors.size == 1 -> SolidColor(colors.single())
        else -> Brush.horizontalGradient(
            *colors.flatMapIndexed { i, c -> listOf(i.toFloat() / colors.size to c, (i + 1f) / colors.size to c) }.toTypedArray(),
        )
    }
}

@Composable
private fun PortCell(port: SwitchPort, selected: Boolean, sfp: Boolean, look: PortLook, onClick: (SwitchPort) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val statusFill by animateColorAsState(port.status.color, Motion.smooth(), label = "portColor")
    val byVlan = look.view == PortView.VLAN
    val byLoad = look.view == PortView.LOAD
    val portLoad = look.load[port.id]
    val loadFill by animateColorAsState(portLoad?.level?.color ?: StatusColors.Disabled, Motion.smooth(), label = "portLoad")
    val vlanColors = port.vlanIds.map(look.palette::colorOf)
    val dimmed = when {
        byVlan && look.highlightVlan != null && !port.carries(look.highlightVlan) -> 0.15f
        byVlan && port.status == PortStatus.DOWN -> 0.45f
        else -> 1f
    }
    val alpha by animateFloatAsState(dimmed, Motion.smooth(), label = "portAlpha")
    val lightFace = if (byLoad) {
        portLoad != null
    } else if (byVlan) {
        port.status != PortStatus.DISABLED && vlanColors.isNotEmpty() && vlanColors.first().luminance() > 0.35f
    } else {
        port.status != PortStatus.DOWN && port.status != PortStatus.DISABLED
    }
    val textColor = if (lightFace) Color.Black.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val vlanText = when {
        byLoad -> portLoad?.let { ", загрузка до ${it.peakUtilization.toInt()}%" } ?: ", нет данных о загрузке"
        !byVlan -> ""
        port.vlanIds.isEmpty() -> ", без VLAN"
        port.mode == PortMode.TRUNK -> ", trunk, VLAN ${port.vlanIds.joinToString()}"
        else -> ", VLAN ${port.vlanIds.single()}"
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = if (sfp) 34.dp else 28.dp, height = 30.dp)
            .pressScale(interaction, pressedScale = 0.9f)
            .alpha(alpha)
            .background(
                when {
                    byLoad -> SolidColor(loadFill)
                    byVlan -> vlanFill(port, look.palette)
                    else -> SolidColor(statusFill)
                },
                RoundedCornerShape(5.dp),
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Color.White else Color.Transparent,
                shape = RoundedCornerShape(5.dp),
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onClick(port) }
            .semantics { contentDescription = "Порт ${port.name}, ${port.status.label}$vlanText" },
    ) {
        Text("${port.number}", fontSize = 10.sp, color = textColor, style = MaterialTheme.typography.labelSmall)
        if (byVlan && port.mode == PortMode.TRUNK) {
            // Trunk mark, so a trunk with a single VLAN is not mistaken for an access port.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .size(width = 14.dp, height = 2.dp)
                    .background(textColor, RoundedCornerShape(1.dp)),
            )
        }
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

/** Legend of the load view, and the busiest ports with their peak and current throughput. */
@Composable
fun LoadLegend(ports: List<SwitchPort>, load: Map<String, PortLoad>, onPortClick: (SwitchPort) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LoadLevel.entries.forEach { LegendItem(it.color, it.label) }
        }
        Text(
            "Пиковая загрузка за 24 часа в самую загруженную сторону. Серые: нет данных.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val busiest = ports.mapNotNull { p -> load[p.id]?.let { p to it } }
            .filter { it.second.peakUtilization > 0f }
            .sortedByDescending { it.second.peakUtilization }
            .take(5)
        if (busiest.isNotEmpty()) {
            Spacer(Modifier.size(4.dp))
            Text("Самые загруженные", style = MaterialTheme.typography.titleSmall)
            busiest.forEach { (port, l) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onPortClick(port) }
                        .padding(vertical = 6.dp),
                ) {
                    StatusDot(l.level.color, size = 10.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(port.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
                    Text(
                        listOfNotNull(port.connectedDeviceName, l.lastInBps?.let { "сейчас ${formatBps(it)}" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${l.peakUtilization.toInt()}%", style = MaterialTheme.typography.labelLarge)
                }
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

/**
 * Legend of the VLAN view: one chip per VLAN found on this switch with its port count.
 * Tapping a chip highlights the ports carrying that VLAN; tapping it again clears the highlight.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VlanLegend(
    ports: List<SwitchPort>,
    palette: VlanPalette,
    highlightVlan: Int?,
    onHighlight: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val usage = ports.vlanUsage()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (usage.isEmpty()) {
            Text(
                "На портах этого коммутатора нет VLAN",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            usage.forEach { (vlanId, count) ->
                val name = palette.names[vlanId]
                FilterChip(
                    selected = highlightVlan == vlanId,
                    onClick = { onHighlight(if (highlightVlan == vlanId) null else vlanId) },
                    label = { Text(listOfNotNull("$vlanId", name, "$count").joinToString(" · ")) },
                    leadingIcon = { StatusDot(palette.colorOf(vlanId), size = 10.dp) },
                )
            }
        }
        highlightVlan?.let { id ->
            val carrying = ports.filter { it.carries(id) }
            val trunks = carrying.count { it.mode == PortMode.TRUNK }
            val up = carrying.count { it.isOperUp }
            Text(
                "VLAN $id: портов ${carrying.size} (access ${carrying.size - trunks}, trunk $trunks), с линком $up",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 14.dp, height = 10.dp)
                        .background(
                            Brush.horizontalGradient(
                                0f to StatusColors.Online, 0.5f to StatusColors.Online,
                                0.5f to StatusColors.LinkFast, 1f to StatusColors.LinkFast,
                            ),
                            RoundedCornerShape(2.dp),
                        ),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 1.dp)
                            .size(width = 8.dp, height = 1.dp)
                            .background(Color.Black.copy(alpha = 0.8f)),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text("Trunk", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Бледный — нет линка",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LegendItem(StatusColors.Disabled, "Shutdown / без VLAN")
        }
    }
}
