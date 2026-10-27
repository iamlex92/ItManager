package com.itmanager.app.data.model

import java.time.Instant

enum class ScanStatus {
    RUNNING, DONE, FAILED;

    companion object {
        fun parse(raw: String?): ScanStatus = when (raw?.lowercase()) {
            "done" -> DONE
            "failed" -> FAILED
            else -> RUNNING
        }
    }
}

/** A host that answered during a scan, and whether the inventory already knows it. */
data class FoundDevice(
    val ip: String,
    /** sysName; null for hosts that answered ping but not SNMP. */
    val name: String?,
    val mac: String?,
    val vendor: String?,
    val model: String?,
    val type: DeviceType,
    val description: String?,
    val snmp: Boolean,
    val inventoryId: String?,
    val inventoryName: String?,
    /** How it was found: ping, tcp (ICMP blocked, a port answered) or arp (only the server's ARP cache knew it). */
    val seenBy: String = "ping",
    val openPorts: List<Int> = emptyList(),
    /** Reverse DNS name. */
    val hostname: String? = null,
) {
    val isNew: Boolean get() = inventoryId == null
    val displayName: String get() = inventoryName ?: name ?: ip
}

enum class LinkSource(val wire: String, val label: String) {
    LLDP("lldp", "LLDP"),
    CDP("cdp", "CDP"),
    FDB("fdb", "MAC-таблица");

    companion object {
        fun parse(raw: String?): LinkSource = entries.firstOrNull { it.wire == raw?.lowercase() } ?: FDB
    }
}

data class LinkEnd(val name: String, val port: String, val ip: String?, val inventoryId: String?) {
    val label: String get() = if (port.isBlank()) name else "$name $port"
}

/** A cable the scan inferred from neighbor or MAC tables. [known]: the inventory already has it. */
data class FoundLink(val a: LinkEnd, val b: LinkEnd, val source: LinkSource, val known: Boolean)

data class NetworkScan(
    val id: String,
    val status: ScanStatus,
    /** Answered by an emulator (a server with DISCOVERY_MODE=emulator), not by the network. */
    val emulated: Boolean,
    val subnets: List<String>,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val total: Int,
    val probed: Int,
    val devices: List<FoundDevice>,
    val links: List<FoundLink>,
    val error: String?,
    /** What limited the scan, in plain words: no ping on the server, server outside the subnet, no SNMP. */
    val warnings: List<String> = emptyList(),
) {
    val progress: Float get() = if (total <= 0) 0f else (probed.toFloat() / total).coerceIn(0f, 1f)
    val newDevices: List<FoundDevice> get() = devices.filter { it.isNew }
    val knownDevices: List<FoundDevice> get() = devices.filterNot { it.isNew }
    val newLinks: List<FoundLink> get() = links.filterNot { it.known }
}

/** What "add to inventory" did: names of added devices, added links, and what was left alone and why. */
data class ImportReport(val created: List<String>, val links: List<String>, val skipped: List<String>)

/** The same limits the server applies, checked on the phone so mistakes show up before a request. */
object ScanRanges {
    const val MAX_SUBNETS = 8
    const val MIN_PREFIX = 22
    const val MAX_HOSTS = 2048

    /** Splits free text (commas, spaces, new lines) into normalized "a.b.c.d/nn" networks, or explains what is wrong. */
    fun parse(text: String): Result<List<String>> {
        val items = text.split(',', ';', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return Result.failure(IllegalArgumentException("Укажите хотя бы одну подсеть, например 10.99.0.0/24."))
        val nets = LinkedHashSet<String>()
        var hosts = 0
        for (item in items) {
            val (ip, prefix) = parseCidr(item)
                ?: return Result.failure(IllegalArgumentException("«$item» не похоже на подсеть, пример: 10.10.20.0/24."))
            if (!isPrivate(ip)) {
                return Result.failure(IllegalArgumentException("$item: сканировать можно только внутренние адреса (10.x, 172.16–31.x, 192.168.x)."))
            }
            if (prefix < MIN_PREFIX) return Result.failure(IllegalArgumentException("$item: слишком большой диапазон, максимум /$MIN_PREFIX."))
            val network = ip and mask(prefix)
            if (nets.add("${format(network)}/$prefix")) hosts += hostCount(prefix)
        }
        if (nets.size > MAX_SUBNETS) return Result.failure(IllegalArgumentException("Не больше $MAX_SUBNETS подсетей за раз."))
        if (hosts > MAX_HOSTS) return Result.failure(IllegalArgumentException("Слишком много адресов, максимум $MAX_HOSTS за одно сканирование."))
        return Result.success(nets.toList())
    }

    fun hostCount(prefix: Int): Int {
        val all = 1 shl (32 - prefix)
        return if (prefix >= 31) all else all - 2
    }

    /** Usable host addresses of a parsed network, in order. */
    fun hosts(cidr: String): List<String> {
        val (ip, prefix) = parseCidr(cidr) ?: return emptyList()
        val network = ip and mask(prefix)
        val all = 1L shl (32 - prefix)
        val range = if (prefix >= 31) 0L until all else 1L until all - 1
        return range.map { format(network + it.toInt()) }
    }

    fun contains(cidr: String, ip: String): Boolean {
        val (net, prefix) = parseCidr(cidr) ?: return false
        val addr = parseIp(ip) ?: return false
        return (addr and mask(prefix)) == (net and mask(prefix))
    }

    private fun parseCidr(text: String): Pair<Int, Int>? {
        val parts = text.split('/')
        if (parts.size > 2) return null
        val ip = parseIp(parts[0]) ?: return null
        val prefix = when (parts.size) {
            2 -> parts[1].toIntOrNull()
            else -> 32
        }
        if (prefix == null || prefix !in 0..32) return null
        return ip to prefix
    }

    private fun parseIp(text: String): Int? {
        val octets = text.split('.')
        if (octets.size != 4) return null
        var value = 0
        for (o in octets) {
            val n = o.toIntOrNull()?.takeIf { it in 0..255 && o.length <= 3 } ?: return null
            value = (value shl 8) or n
        }
        return value
    }

    private fun mask(prefix: Int): Int = if (prefix == 0) 0 else -1 shl (32 - prefix)

    private fun format(ip: Int): String = (3 downTo 0).joinToString(".") { ((ip ushr (it * 8)) and 0xFF).toString() }

    private fun isPrivate(ip: Int): Boolean {
        val a = (ip ushr 24) and 0xFF
        val b = (ip ushr 16) and 0xFF
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
