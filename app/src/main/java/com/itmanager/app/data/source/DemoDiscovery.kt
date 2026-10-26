package com.itmanager.app.data.source

import com.itmanager.app.core.network.UserFacingException
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.model.FoundDevice
import com.itmanager.app.data.model.FoundLink
import com.itmanager.app.data.model.LinkSource
import com.itmanager.app.data.model.NetworkScan
import com.itmanager.app.data.model.ScanRanges
import com.itmanager.app.data.model.ScanStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/**
 * Network scan in demo mode: answered from the demo inventory plus a few devices it does not know yet,
 * like the server's emulator. Progress follows the clock, so polling sees it move without a background job.
 */
internal object DemoDiscovery {

    /** What answers on the emulated network: the host, and the community it answers SNMP with (null: ping only). */
    class Host(val device: FoundDevice, val community: String?)

    /** What each kind of device has open on the emulated network, as the server's emulator has it. */
    fun openPorts(type: DeviceType): List<Int> = when (type) {
        DeviceType.SWITCH -> listOf(22, 23, 80, 443)
        DeviceType.PRINTER -> listOf(80, 443, 515, 631, 9100)
        DeviceType.CAMERA -> listOf(80, 443, 554, 8000)
        DeviceType.ACCESS_POINT -> listOf(22, 80, 443)
        DeviceType.SERVER -> listOf(22, 135, 445, 3389, 5985)
        DeviceType.WORKSTATION -> listOf(135, 445, 3389)
        else -> emptyList()
    }

    private class Run(val scan: NetworkScan, val startedMs: Long, val durationMs: Long, var audited: Boolean = false)

    private const val MS_PER_HOST = 6L
    private val lock = Mutex()
    private var last: Run? = null

    /**
     * Runs the whole scan at once and reveals it over time. A link is visible when its switch end (a) answered
     * SNMP with the given community; a host learned from a MAC table must itself have answered ping.
     */
    suspend fun start(subnets: List<String>, community: String, world: List<Host>, links: List<FoundLink>): NetworkScan =
        lock.withLock {
            val running = last?.takeIf { now() - it.startedMs < it.durationMs }
            if (running != null) throw UserFacingException("Уже идёт сканирование ${running.scan.subnets.joinToString()}, дождитесь его окончания.")
            val targets = subnets.flatMap(ScanRanges::hosts).distinct()
            val byIp = world.associateBy { it.device.ip }
            val found = targets.mapNotNull { ip ->
                val host = byIp[ip] ?: return@mapNotNull null
                if (host.community != null && host.community == community) {
                    host.device
                } else {
                    // No SNMP: no name, model or switch tables; the type still shows from open ports and the MAC vendor.
                    host.device.copy(
                        name = null, model = null, snmp = false,
                        description = host.device.description.takeIf { host.community == null },
                    )
                }
            }
            val foundByIp = found.associateBy { it.ip }
            val visible = links.filter { link ->
                val sw = foundByIp[link.a.ip]
                sw != null && sw.snmp && (link.source != LinkSource.FDB || link.b.ip in foundByIp)
            }.map { link ->
                if (link.source == LinkSource.FDB) link.copy(b = link.b.copy(name = foundByIp[link.b.ip]?.name ?: link.b.ip.orEmpty())) else link
            }
            val scan = NetworkScan(
                id = UUID.randomUUID().toString(),
                status = ScanStatus.DONE,
                emulated = true,
                subnets = subnets,
                startedAt = Instant.now(),
                finishedAt = null,
                total = targets.size,
                probed = targets.size,
                devices = found,
                links = visible,
                error = null,
            )
            val run = Run(scan, now(), targets.size * MS_PER_HOST)
            last = run
            view(run)
        }

    suspend fun get(id: String): NetworkScan = lock.withLock {
        val run = last?.takeIf { it.scan.id == id } ?: throw UserFacingException("Это сканирование уже не хранится. Запустите его заново.")
        view(run)
    }

    suspend fun latest(): NetworkScan? = lock.withLock { last?.let { view(it) } }

    /** The finished scan to import from. */
    suspend fun finished(id: String): NetworkScan {
        val scan = get(id)
        if (scan.status != ScanStatus.DONE) throw UserFacingException("Сканирование ещё не закончилось.")
        return scan
    }

    private suspend fun view(run: Run): NetworkScan {
        val elapsed = now() - run.startedMs
        if (elapsed < run.durationMs) {
            val probed = (elapsed / MS_PER_HOST).toInt().coerceAtMost(run.scan.total)
            return run.scan.copy(status = ScanStatus.RUNNING, probed = probed, devices = emptyList(), links = emptyList())
        }
        val done = run.scan.copy(finishedAt = run.scan.startedAt?.plusMillis(run.durationMs))
        if (!run.audited) {
            run.audited = true
            DemoRunbooks.recordNetwork(
                action = "network_scan",
                title = "Сканирование сети",
                arguments = listOf("subnets" to done.subnets.joinToString(), "mode" to "демо"),
                stdout = listOf(
                    "Подсети: ${done.subnets.joinToString()}",
                    "Проверено адресов: ${done.total} из ${done.total}",
                    "Ответили: ${done.devices.size}, по SNMP: ${done.devices.count { it.snmp }}",
                    "Связей найдено: ${done.links.size}",
                ).joinToString("\n"),
                stderr = "",
                durationMs = run.durationMs,
            )
        }
        return done
    }

    private fun now() = System.currentTimeMillis()
}
