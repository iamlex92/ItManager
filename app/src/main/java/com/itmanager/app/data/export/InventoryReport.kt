package com.itmanager.app.data.export

import com.itmanager.app.data.model.Device
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.SwitchPort
import com.itmanager.app.data.model.Vlan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One table of the report: the same rows go to an Excel sheet and to a PDF section. */
data class ReportTable(
    val title: String,
    val header: List<String>,
    val rows: List<List<String>>,
    /** Relative column widths, in characters for Excel. */
    val widths: List<Int>,
)

/** The inventory as it is now: every device, and every switch port with its VLANs and cabling. */
class InventoryReport(
    val generatedAt: Instant,
    /** "Демо-данные" or the server address, so a printed copy says where it came from. */
    val source: String,
    devices: List<Device>,
    vlans: List<Vlan>,
    switchPorts: List<Pair<Device, List<SwitchPort>>>,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    private val stamp = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone)
    private val vlanNames = vlans.associate { it.id to it.name }
    private val sortedDevices = devices.sortedWith(compareBy<Device> { it.type.ordinal }.thenBy { it.name })

    val title = "Учёт сетевого оборудования"
    val subtitle: String get() = "${stamp.format(generatedAt)} · $source"

    val summary: List<Pair<String, String>> = buildList {
        add("Устройств" to devices.size.toString())
        add("Не отвечают" to devices.count { !it.isOnline && it.type !in PASSIVE }.toString())
        DeviceType.entries.forEach { t ->
            val n = devices.count { it.type == t }
            if (n > 0) add(t.label to n.toString())
        }
        val ports = switchPorts.flatMap { it.second }
        if (ports.isNotEmpty()) add("Портов коммутаторов" to "${ports.size}, с линком ${ports.count { it.isOperUp }}")
    }

    val devicesTable = ReportTable(
        title = "Устройства",
        header = listOf("Имя", "Тип", "Производитель", "Модель", "IP", "MAC", "VLAN", "Состояние"),
        rows = sortedDevices.map { d ->
            listOf(
                d.name,
                d.type.label,
                d.vendor.orEmpty(),
                d.model.orEmpty(),
                d.ip.orEmpty(),
                d.mac.orEmpty(),
                d.vlanId?.let(::vlanLabel).orEmpty(),
                when {
                    d.type in PASSIVE -> "пассивное"
                    d.isOnline -> "в сети"
                    else -> "не отвечает"
                },
            )
        },
        widths = listOf(22, 16, 14, 18, 16, 19, 18, 13),
    )

    val portsTable = ReportTable(
        title = "Порты коммутаторов",
        header = listOf("Коммутатор", "Порт", "Режим", "VLAN", "Состояние", "Скорость", "PoE", "Подключено"),
        rows = switchPorts.sortedBy { it.first.name }.flatMap { (sw, ports) ->
            ports.sortedBy { it.number }.map { p ->
                listOf(
                    sw.name,
                    p.name + if (p.isUplink) " (аплинк)" else "",
                    if (p.mode == PortMode.TRUNK) "trunk" else "access",
                    p.vlanIds.joinToString(", ") { vlanLabel(it) },
                    when {
                        !p.isAdminUp -> "shutdown"
                        p.isOperUp -> "линк"
                        else -> "нет линка"
                    },
                    if (p.isOperUp) speed(p.speedMbps) else "",
                    if (p.isPoeActive) "питает" else if (p.poeEnabled) "вкл" else "",
                    p.connectedDeviceName.orEmpty(),
                )
            }
        },
        widths = listOf(16, 18, 8, 22, 11, 11, 7, 22),
    )

    val tables: List<ReportTable> get() = listOf(devicesTable, portsTable).filter { it.rows.isNotEmpty() }

    private fun vlanLabel(id: Int) = vlanNames[id]?.let { "$id $it" } ?: id.toString()

    private fun speed(mbps: Int) = if (mbps >= 1000) "${mbps / 1000} Гбит/с" else "$mbps Мбит/с"

    private companion object {
        val PASSIVE = setOf(DeviceType.SOCKET, DeviceType.PATCH_PANEL)
    }
}
