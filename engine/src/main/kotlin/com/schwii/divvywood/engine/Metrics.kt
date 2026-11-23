package com.schwii.divvywood.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How a build measures up, three ways that pull against each other:
 * - [pieces]: every cell but the inputs and outputs (belts, splitters, convergers, bypasses). Fewer is better.
 * - [side]: the side of the smallest square around the machine: every cell but the inputs and outputs. Smaller is
 *   better. [w] × [h] is the machine's own bounding box.
 * - [io]: how easy the build is to slot into a bigger machine, 0–100. Higher is better. See [ioLayout].
 */
class Metrics(
    val pieces: Int, val belts: Int, val splits: Int, val merges: Int, val bypasses: Int,
    val w: Int, val h: Int, val layout: IoLayout,
) {
    val side get() = maxOf(w, h)
    val io get() = layout.score
}

/**
 * The I/O layout score, from each port's open face: the side opposite its connection (an input's back, an output's
 * or bypass's mouth), where a bigger machine would join it.
 * - [edge]: share of ports whose open face has a clear line out: no other piece in that direction (40% of the score)
 * - [grouping]: how tightly the inputs sit together, and the outputs with the bypasses (30%)
 * - [opposition]: inputs facing out on the side opposite the outputs scores 1; perpendicular sides 0.5; the same
 *   side 0.25; no port on the edge 0 (30%)
 */
class IoLayout(val ports: Int, val onEdge: Int, val grouping: Double, val opposition: Double) {
    val edge get() = if (ports == 0) 0.0 else onEdge.toDouble() / ports
    val score get() = if (ports == 0) 0 else (100 * (0.4 * edge + 0.3 * grouping + 0.3 * opposition)).roundToInt()
}

fun metricsOf(tiles: Map<Cell, Tile>): Metrics {
    if (tiles.isEmpty()) return Metrics(0, 0, 0, 0, 0, 0, 0, IoLayout(0, 0, 0.0, 0.0))
    val v = tiles.values
    val belts = v.count { it.kind == Kind.BELT }
    val splits = v.count { it.kind == Kind.SPLIT }
    val merges = v.count { it.kind == Kind.MERGE }
    val bypasses = v.count { it.kind == Kind.DUMP }
    // the footprint is the machine alone: the inputs and outputs don't count
    val body = tiles.filterValues { it.kind != Kind.SRC && it.kind != Kind.OUT }.keys
    val w = if (body.isEmpty()) 0 else body.maxOf { it.x } - body.minOf { it.x } + 1
    val h = if (body.isEmpty()) 0 else body.maxOf { it.y } - body.minOf { it.y } + 1
    return Metrics(belts + splits + merges + bypasses, belts, splits, merges, bypasses, w, h, ioLayout(tiles))
}

/** The side a port's open face looks out of. */
private fun openFace(t: Tile): Int = when (t.kind) {
    Kind.SRC -> opp(t.out)
    else -> opp(t.inSide)
}

private fun ioLayout(tiles: Map<Cell, Tile>): IoLayout {
    val ports = tiles.filterValues { it.kind == Kind.SRC || it.kind == Kind.OUT || it.kind == Kind.DUMP }
    if (ports.isEmpty()) return IoLayout(0, 0, 0.0, 0.0)
    val x0 = tiles.keys.minOf { it.x }; val x1 = tiles.keys.maxOf { it.x }
    val y0 = tiles.keys.minOf { it.y }; val y1 = tiles.keys.maxOf { it.y }
    // a clear line out: no other piece between the port and the edge of everything built, in its open face's direction
    fun outward(c: Cell, d: Int): Boolean {
        var p = c.step(d)
        while (p.x in x0..x1 && p.y in y0..y1) { if (p in tiles) return false; p = p.step(d) }
        return true
    }
    val edgeFace = ports.mapNotNull { (c, t) -> openFace(t).takeIf { it != NONE && outward(c, it) }?.let { c to it } }.toMap()
    val inputs = ports.filterValues { it.kind == Kind.SRC }.keys.toList()
    val outputs = ports.filterValues { it.kind != Kind.SRC }.keys.toList()
    val groups = listOf(inputs, outputs).filter { it.isNotEmpty() }
    val grouping = groups.map { compactness(it) }.average()
    return IoLayout(ports.size, edgeFace.size, grouping, opposition(inputs.mapNotNull { edgeFace[it] }, outputs.mapNotNull { edgeFace[it] }))
}

/** 1 for cells side by side in a row; less the further apart they are: (n − 1) over the length of the shortest tree joining them. */
private fun compactness(cells: List<Cell>): Double {
    if (cells.size <= 1) return 1.0
    // Prim's algorithm over Manhattan distances
    val inTree = mutableSetOf(cells[0])
    var length = 0
    while (inTree.size < cells.size) {
        var best = Int.MAX_VALUE; var next: Cell? = null
        for (a in inTree) for (b in cells) if (b !in inTree) {
            val d = abs(a.x - b.x) + abs(a.y - b.y)
            if (d < best) { best = d; next = b }
        }
        inTree += next!!; length += best
    }
    return (cells.size - 1).toDouble() / length
}

/** How well the inputs' and the outputs' open faces look away from each other, by each group's most common face. */
private fun opposition(inFaces: List<Int>, outFaces: List<Int>): Double {
    if (inFaces.isEmpty() || outFaces.isEmpty()) return 0.0
    fun main(fs: List<Int>): List<Int> { val c = fs.groupingBy { it }.eachCount(); val m = c.values.max(); return c.filterValues { it == m }.keys.toList() }
    // when a group's faces tie, it is read the way that suits the build best
    return main(inFaces).maxOf { a -> main(outFaces).maxOf { b -> if (b == opp(a)) 1.0 else if (b == a) 0.25 else 0.5 } }
}
