package com.itmanager.app.data.model

/** An active device in the network picture. */
data class TopologyNode(
    val id: String,
    val name: String,
    val type: DeviceType,
    val vendor: String?,
    val model: String?,
    val ip: String?,
    val isOnline: Boolean,
    val floorId: String?,
    val hostId: String?,
)

enum class TopologyLinkKind { CABLE, VIRTUAL }

/** Two active devices joined by a cable, perhaps through sockets and patch panels ([via]). */
data class TopologyLink(
    val aId: String,
    val bId: String,
    val kind: TopologyLinkKind,
    val aPort: String?,
    val bPort: String?,
    val via: List<String>,
    val cableTypes: List<String>,
    /** From the switch side; null when neither end is a switch. */
    val isUp: Boolean?,
    val speedMbps: Int?,
    /** The cables of the chain from end A to end B; empty for a VIRTUAL link. */
    val connectionIds: List<String> = emptyList(),
) {
    /** The cable plugged into [deviceId]'s port: the one to remove to unplug that device. */
    fun cableAt(deviceId: String): String? = if (deviceId == aId) connectionIds.firstOrNull() else connectionIds.lastOrNull()

    fun other(id: String): String = if (id == aId) bId else aId
    fun portOf(id: String): String? = if (id == aId) aPort else bPort
}

data class Topology(val nodes: List<TopologyNode>, val links: List<TopologyLink>)

/** A node's place in the drawing, in cells: [x] is the centre column, [y] the row. */
data class NodePlace(val x: Float, val y: Float)

data class TopologyLayout(
    val places: Map<String, NodePlace>,
    /** Size of the drawing in cells. */
    val width: Float,
    val height: Float,
    val links: List<TopologyLink>,
    /** Nodes laid out, in drawing order. */
    val nodes: List<TopologyNode>,
)

/**
 * Lays the network out as a tree from the top: routers and firewalls (or the busiest switch) at the top,
 * switches below the device they hang off, end devices in a compact grid under their switch.
 * Every component gets its own tree, side by side; devices with no links go into a row at the bottom.
 */
object TopologyLayouter {
    /** Devices that carry traffic for others: the frame of the tree. */
    private val INFRA = setOf(DeviceType.ROUTER, DeviceType.FIREWALL, DeviceType.SWITCH)
    private const val GRID_COLUMNS = 6
    private const val COMPONENT_GAP = 1f
    private const val ROW_GAP = 1.6f

    fun layout(topology: Topology, showEndDevices: Boolean): TopologyLayout {
        val nodes = topology.nodes.filter { showEndDevices || it.type in INFRA }
        val byId = nodes.associateBy { it.id }
        val links = topology.links.filter { it.aId in byId && it.bId in byId && it.aId != it.bId }
        val adj = HashMap<String, MutableList<String>>()
        links.forEach {
            adj.getOrPut(it.aId) { mutableListOf() } += it.bId
            adj.getOrPut(it.bId) { mutableListOf() } += it.aId
        }
        val infra = nodes.filter { it.type in INFRA }
        val infraIds = infra.mapTo(HashSet()) { it.id }

        // Tree over infrastructure: BFS from the best root of each component.
        val parent = HashMap<String, String?>()
        val children = HashMap<String, MutableList<String>>()
        val roots = mutableListOf<String>()
        val rank = compareByDescending<TopologyNode> { rootRank(it.type) }
            .thenByDescending { n -> adj[n.id].orEmpty().count { it in infraIds } }
            .thenBy { it.name }
        for (root in infra.sortedWith(rank)) {
            if (root.id in parent) continue
            parent[root.id] = null
            roots += root.id
            val queue = ArrayDeque(listOf(root.id))
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                adj[id].orEmpty().filter { it in infraIds && it !in parent }.sortedBy { byId[it]?.name }.forEach { next ->
                    parent[next] = id
                    children.getOrPut(id) { mutableListOf() } += next
                    queue += next
                }
            }
        }

        // End devices hang under the infrastructure node they are cabled to, VMs under their host.
        val leaves = HashMap<String, MutableList<String>>()
        val placedLeaf = HashSet<String>()
        val endDevices = nodes.filter { it.type !in INFRA }.sortedBy { it.name }
        for (n in endDevices.filter { it.type != DeviceType.VIRTUAL }) {
            val owner = adj[n.id].orEmpty().firstOrNull { it in infraIds } ?: continue
            leaves.getOrPut(owner) { mutableListOf() } += n.id
            placedLeaf += n.id
        }
        // Servers cabled to nothing in the frame, but with VMs, still keep their VMs beside them.
        val loose = endDevices.filter { it.id !in placedLeaf && it.type != DeviceType.VIRTUAL }.map { it.id }.toMutableList()
        for (vm in endDevices.filter { it.type == DeviceType.VIRTUAL }) {
            val host = vm.hostId?.takeIf { it in byId }
            if (host != null && (host in placedLeaf || host in loose || host in infraIds)) {
                leaves.getOrPut(host) { mutableListOf() } += vm.id
            } else {
                loose += vm.id
            }
        }

        val places = HashMap<String, NodePlace>()

        fun gridRows(count: Int) = if (count == 0) 0 else (count + GRID_COLUMNS - 1) / GRID_COLUMNS
        fun gridWidth(count: Int) = minOf(count, GRID_COLUMNS).toFloat()

        // Leaves of a leaf (VMs of a server) sit in a row right under it, so they count in its block.
        fun leafBlock(id: String): Int = 1 + (leaves[id]?.size ?: 0)

        fun width(id: String): Float {
            val own = gridWidth(leaves[id]?.sumOf { leafBlock(it) } ?: 0)
            val kids = children[id].orEmpty().sumOf { width(it).toDouble() }.toFloat()
            return maxOf(1f, own, kids)
        }

        fun place(id: String, left: Float, top: Float): Float {
            val w = width(id)
            places[id] = NodePlace(left + w / 2f, top)
            // End devices: a grid centred under the node, a server's VMs following it.
            val flat = leaves[id].orEmpty().flatMap { listOf(it) + leaves[it].orEmpty() }
            val gw = gridWidth(flat.size)
            flat.forEachIndexed { i, leaf ->
                places[leaf] = NodePlace(left + (w - gw) / 2f + (i % GRID_COLUMNS) + 0.5f, top + ROW_GAP + (i / GRID_COLUMNS))
            }
            val childTop = top + ROW_GAP + gridRows(flat.size) + if (flat.isEmpty()) 0f else 0.6f
            val kids = children[id].orEmpty()
            val kidsWidth = kids.sumOf { width(it).toDouble() }.toFloat()
            var x = left + (w - kidsWidth) / 2f
            var bottom = top + if (flat.isEmpty()) 1f else ROW_GAP + gridRows(flat.size)
            for (kid in kids) {
                bottom = maxOf(bottom, place(kid, x, childTop))
                x += width(kid)
            }
            return bottom
        }

        var left = 0f
        var bottom = 0f
        for (root in roots) {
            bottom = maxOf(bottom, place(root, left, 0f))
            left += width(root) + COMPONENT_GAP
        }
        // Not cabled to the frame: a row (grid) at the bottom, servers followed by their VMs.
        val flatLoose = loose.flatMap { listOf(it) + leaves[it].orEmpty() }.distinct()
        val looseTop = if (roots.isEmpty()) 0f else bottom + 0.8f
        val columns = maxOf(GRID_COLUMNS, (left - COMPONENT_GAP).toInt().coerceAtLeast(1))
        flatLoose.forEachIndexed { i, id ->
            places.putIfAbsent(id, NodePlace((i % columns) + 0.5f, looseTop + (i / columns)))
        }
        val maxX = places.values.maxOfOrNull { it.x + 0.5f } ?: 0f
        val maxY = places.values.maxOfOrNull { it.y + 1f } ?: 0f
        return TopologyLayout(places, maxX, maxY, links, nodes.filter { it.id in places })
    }

    private fun rootRank(type: DeviceType) = when (type) {
        DeviceType.FIREWALL -> 3
        DeviceType.ROUTER -> 2
        else -> 0
    }
}
