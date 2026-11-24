package com.schwii.divvywood.engine

import java.security.MessageDigest

/**
 * A machine: a solved build placed in other builds as a single piece. [code] is its build in one-line text form;
 * [level] the name of the level it solves, when known (machines read in from someone else's code may not say).
 */
data class MachineDef(val code: String, val level: String? = null)

/**
 * The machines a build may use, by name: the first six hex digits of the SHA-256 of the build's one-line code
 * ([machineHash]), so a machine is the same build on every device, and a build placed once never changes.
 */
class MachineLib(val defs: Map<String, MachineDef> = emptyMap()) {
    private val cache = HashMap<String, Map<Cell, Tile>?>()

    operator fun get(hash: String): MachineDef? = defs[hash]

    /** The build of machine [hash], or null when unknown or unreadable. */
    fun tiles(hash: String): Map<Cell, Tile>? = cache.getOrPut(hash) { defs[hash]?.let { decodeGrid(it.code).tiles } }

    fun plus(more: Map<String, MachineDef>) = MachineLib(defs + more)

    companion object { val EMPTY = MachineLib() }
}

/** A machine's name: six hex digits of the SHA-256 of its one-line code. */
fun machineHash(code: String): String =
    MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).take(3).joinToString("") { "%02x".format(it) }

/** The definition of [tiles] as a machine: its one-line code and the hash naming it. */
fun machineOf(tiles: Map<Cell, Tile>): Pair<String, String> = encodeLine(tiles).let { machineHash(it) to it }

/** [tiles] turned [r] quarter turns clockwise. */
fun turned(tiles: Map<Cell, Tile>, r: Int): Tiles {
    val t: Tiles = LinkedHashMap(tiles)
    var cells = t.keys.toList()
    repeat(((r % 4) + 4) % 4) { cells = rotateGroup(t, cells) ?: cells }
    return t
}

/** Every machine [tiles] uses, nested ones included (unknown ones left out), by hash. */
fun machinesUsed(tiles: Map<Cell, Tile>, lib: MachineLib, seen: MutableSet<String> = linkedSetOf()): Set<String> {
    for (t in tiles.values) {
        val h = t.ref ?: continue
        if (t.kind != Kind.MACHINE || !seen.add(h)) continue
        lib.tiles(h)?.let { machinesUsed(it, lib, seen) }
    }
    return seen
}

/** How many pieces machine [hash] holds, nested machines counted by their own pieces; 1 when unknown. */
fun machinePieces(hash: String, lib: MachineLib, stack: Set<String> = emptySet()): Int {
    if (hash in stack) return 1
    val t = lib.tiles(hash) ?: return 1
    return t.values.sumOf { if (it.kind == Kind.SRC) 0 else if (it.kind == Kind.MACHINE && it.ref != null) machinePieces(it.ref, lib, stack + hash) else 1 }
}

/**
 * Where a build's ports line up, if they do: the column every top and bottom port runs in and the row every left and
 * right port runs in (null on an axis with no ports). A build qualifies as a machine only when they line up, so
 * every port can meet the middle of its side of the machine's cell, straight on to the lines around it.
 */
fun portAxes(g: Graph): Pair<Int?, Int?>? {
    val lanes = g.ports.map { p -> p.side to if (p.side % 2 == 0) p.edge.y else p.edge.x }
    val cols = lanes.filter { it.first % 2 == 1 }.map { it.second }.distinct()
    val rows = lanes.filter { it.first % 2 == 0 }.map { it.second }.distinct()
    if (cols.size > 1 || rows.size > 1) return null
    return cols.firstOrNull() to rows.firstOrNull()
}

/** Can [tiles] be a machine: do its ports line up across its middle (see [portAxes])? */
fun portsAligned(tiles: Map<Cell, Tile>, lib: MachineLib = MachineLib.EMPTY): Boolean {
    val ins = tiles.values.count { it.kind == Kind.SRC }
    return portAxes(buildGraph(tiles, Level("machine", List(ins) { Frac(1, 1) }, emptyList()), lib)) != null
}
