package com.itmanager.app.data.source

import com.itmanager.app.data.model.DiagnosticResult
import com.itmanager.app.data.model.PortChange
import com.itmanager.app.data.model.PortMode
import com.itmanager.app.data.model.SwitchPort

/**
 * Cisco IOS / Eltex MES syntax for a port change, matching what the server sends.
 * Used by demo mode, so previews look exactly like the real thing without a server.
 */
object CommandTranslator {
    fun portChange(vendor: String?, port: SwitchPort, change: PortChange): List<String> {
        if (change.isEmpty) return emptyList()
        val eltex = vendor.equals("Eltex", ignoreCase = true)
        val commands = mutableListOf(interfaceLine(eltex, port.name))
        when (change.mode) {
            PortMode.ACCESS -> commands += "switchport mode access"
            PortMode.TRUNK -> commands += "switchport mode trunk"
            null -> Unit
        }
        change.accessVlan?.let { commands += "switchport access vlan $it" }
        change.taggedVlans?.let { vlans ->
            val list = vlans.joinToString(",")
            commands += if (eltex) "switchport trunk allowed vlan add $list" else "switchport trunk allowed vlan $list"
        }
        change.isAdminUp?.let { commands += if (it) "no shutdown" else "shutdown" }
        commands += "exit"
        return commands
    }

    fun bounce(vendor: String?, port: SwitchPort): List<String> {
        val eltex = vendor.equals("Eltex", ignoreCase = true)
        return listOf(interfaceLine(eltex, port.name), "shutdown", "! пауза 2 с", "no shutdown", "exit")
    }

    fun writeMemory(vendor: String?): List<String> =
        if (vendor.equals("Eltex", ignoreCase = true)) listOf("write memory") else listOf("copy running-config startup-config")

    /** What the switch prints for a ping; [reachable] decides whether the echoes come back. */
    fun ping(vendor: String?, target: String, count: Int, reachable: Boolean): DiagnosticResult {
        val received = if (reachable) count else 0
        val loss = 100 - received * 100 / count
        return if (vendor.equals("Eltex", ignoreCase = true)) {
            val lines = buildList {
                add("Pinging $target with 56 bytes of data:")
                add("")
                repeat(count) { i ->
                    add(if (reachable) "56 bytes from $target: icmp_seq=${i + 1}. time=1 ms" else "PING: timeout")
                }
                add("")
                add("----$target PING Statistics----")
                add("$count packets transmitted, $received packets received, $loss% packet loss")
                if (reachable) add("round-trip (ms) min/avg/max = 1/1/2")
            }
            DiagnosticResult("ping $target count $count", lines.joinToString("\n"), reachable)
        } else {
            val lines = buildList {
                add("Type escape sequence to abort.")
                add("Sending $count, 100-byte ICMP Echos to $target, timeout is 2 seconds:")
                add((if (reachable) "!" else ".").repeat(count))
                add(
                    "Success rate is ${100 - loss} percent ($received/$count)" +
                        if (reachable) ", round-trip min/avg/max = 1/1/2 ms" else "",
                )
            }
            DiagnosticResult("ping $target repeat $count", lines.joinToString("\n"), reachable)
        }
    }

    /** Traceroute through [hops] (the last one is the target); an unreachable target ends in timeouts. */
    fun traceroute(vendor: String?, target: String, hops: List<String>, reachable: Boolean): DiagnosticResult {
        val eltex = vendor.equals("Eltex", ignoreCase = true)
        val shown = if (reachable) hops else hops.dropLast(1)
        val lines = buildList {
            if (eltex) add("Tracing the route to $target") else add("Type escape sequence to abort.\nTracing the route to $target")
            add("")
            shown.forEachIndexed { i, hop -> add("  ${i + 1} $hop 1 msec 1 msec 1 msec") }
            if (!reachable) (shown.size + 1..shown.size + 3).forEach { add("  $it  *  *  *") }
        }
        return DiagnosticResult("traceroute $target", lines.joinToString("\n"), reachable)
    }

    /** A running-config fragment for the ports, enough to diff before and after a change. */
    fun runningConfig(vendor: String?, hostname: String, ports: List<SwitchPort>): List<String> {
        val eltex = vendor.equals("Eltex", ignoreCase = true)
        val lines = mutableListOf("hostname $hostname", "!")
        for (p in ports) {
            lines += interfaceLine(eltex, p.name)
            if (p.mode == PortMode.TRUNK) {
                lines += " switchport mode trunk"
                lines += " switchport trunk allowed vlan ${p.taggedVlans.joinToString(",")}"
            } else {
                lines += " switchport mode access"
                p.accessVlan?.let { lines += " switchport access vlan $it" }
            }
            if (!p.isAdminUp) lines += " shutdown"
            lines += "!"
        }
        return lines
    }

    private fun interfaceLine(eltex: Boolean, name: String): String {
        if (!eltex) return "interface $name"
        // gi1/0/5 -> gigabitethernet 1/0/5, te1/0/1 -> tengigabitethernet 1/0/1
        val kind = when {
            name.startsWith("te", ignoreCase = true) -> "tengigabitethernet"
            else -> "gigabitethernet"
        }
        return "interface $kind ${name.dropWhile { it.isLetter() }}"
    }
}

/** Minimal unified-style diff by interface block: only blocks that changed are shown. */
internal fun blockDiff(before: List<String>, after: List<String>): String {
    fun blocks(lines: List<String>): LinkedHashMap<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        var header: String? = null
        var body = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("interface ")) {
                header?.let { result[it] = body }
                header = line
                body = mutableListOf()
            } else if (line != "!" && header != null) {
                body += line
            }
        }
        header?.let { result[it] = body }
        return result
    }
    val a = blocks(before)
    val b = blocks(after)
    val out = StringBuilder()
    for ((header, newBody) in b) {
        val oldBody = a[header].orEmpty()
        if (oldBody == newBody) continue
        out.append("@@ ").append(header).append(" @@\n")
        oldBody.filterNot { it in newBody }.forEach { out.append('-').append(it).append('\n') }
        newBody.filterNot { it in oldBody }.forEach { out.append('+').append(it).append('\n') }
    }
    return out.toString()
}
