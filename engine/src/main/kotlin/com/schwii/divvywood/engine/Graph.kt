package com.schwii.divvywood.engine

class Node(val cell: Cell, val tile: Tile) {
    val kind get() = tile.kind
    val id get() = tile.id
    /** An input's number (an unnumbered input is the first). */
    val inputId get() = if (tile.id >= 0) tile.id else 0
    val succ = mutableListOf<Int>()
    val pred = mutableListOf<Int>()
    val errs = mutableListOf<String>()
}

/** The rectangle around everything built, corners included. */
data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    operator fun contains(c: Cell) = c.x in x0..x1 && c.y in y0..y1
}

fun boxOf(cells: Collection<Cell>): Box? = if (cells.isEmpty()) null else
    Box(cells.minOf { it.x }, cells.minOf { it.y }, cells.maxOf { it.x }, cells.maxOf { it.y })

/**
 * Where marbles enter or leave the build, on [side] of its box: an input (marbles come in behind it), or a way out
 * (a belt, converger or splitter side pointing at empty space). [node] is the input, or the way-out node; [line] the empty cells
 * between the piece and the edge of the box, which must stay clear; [edge] the first cell past the edge.
 */
class Port(val node: Int, val piece: Int, val side: Int, val line: List<Cell>, val edge: Cell) {
    val isInput get() = node == piece
}

/** Side names for messages: right, bottom, left, top. */
val SIDE_NAMES = listOf("right", "bottom", "left", "top")

class Graph(val nodes: List<Node>, val idx: Map<Cell, Int>, val ports: List<Port> = emptyList(), val box: Box? = null) {
    val errCount get() = nodes.count { it.errs.isNotEmpty() }

    /** The ways out: nodes where marbles leave the build, in board order. */
    val exits: List<Int> get() = nodes.indices.filter { nodes[it].kind == Kind.OUT }

    /** Links [a] → [b] (used directly by tests that build abstract networks). */
    fun link(a: Int, b: Int) { nodes[a].succ += b; nodes[b].pred += a }
}

/**
 * The board as a graph. Nodes run in board order (row by row, top to bottom, each row left to right), so the sim's
 * turn order, and with it every result, depends only on the layout, never on the order pieces were placed in.
 *
 * Marbles enter and leave on the sides of the box around the build. Each input takes them in from behind, and each
 * belt, converger or splitter side pointing at empty space lets them out; either way the line to the box's edge must be clear, and
 * each side holds at most one of these ports. A way out becomes an [Kind.OUT] node after the board's nodes, in board
 * order of where it leaves.
 */
fun buildGraph(tiles: Map<Cell, Tile>, level: Level): Graph {
    val cells = tiles.keys.sortedWith(compareBy({ it.y }, { it.x }))
    val idx = cells.withIndex().associate { (i, c) -> c to i }
    val nodes = cells.map { Node(it, tiles.getValue(it)) }.toMutableList()
    val box = boxOf(cells)
    val g = Graph(nodes, idx, mutableListOf(), box)
    val ports = g.ports as MutableList<Port>

    /** The empty cells from [from] (excluded) to the edge of the box in direction [d], or null when something is in the way. */
    fun clearLine(from: Cell, d: Int): List<Cell>? {
        val line = mutableListOf<Cell>()
        var c = from.step(d)
        while (box != null && c in box) { if (c in tiles) return null; line += c; c = c.step(d) }
        return line
    }
    fun pastEdge(from: Cell, d: Int): Cell { var c = from.step(d); while (box != null && c in box) c = c.step(d); return c }

    val exitsAt = mutableListOf<Pair<Int, Int>>()   // piece, side
    nodes.toList().forEachIndexed { i, n ->
        for (d in 0..3) {
            if (!sends(n.tile, d)) continue
            val j = idx[n.cell.step(d)]
            if (j != null && accepts(nodes[j].tile, opp(d))) { g.link(i, j); continue }
            // a side facing empty space with a clear line to the edge is a way out
            if (j == null && clearLine(n.cell, d) != null) { exitsAt += i to d; continue }
            // a belt or converger has one way on, so a dead end there is an error; a splitter's other sides may stay unused
            if (n.kind != Kind.BELT && n.kind != Kind.MERGE) continue
            val what = if (n.kind == Kind.BELT) "This belt" else "This converger"
            val m = j?.let { nodes[it] }
            if (m == null) { n.errs += "$what points at the edge of the build, but its way out there is blocked."; continue }
            n.errs += when (m.kind) {
                Kind.BELT -> "$what runs into the side of a belt. Join them with a converger."
                Kind.SPLIT -> "$what runs into a splitter away from its input side. Rotate the splitter to face this way."
                Kind.MERGE -> "$what runs into the output side of a converger. Rotate the converger to face away."
                Kind.SRC -> "$what runs into the input."
                Kind.OUT -> "$what runs into the way out."
            }
        }
    }
    // inputs take marbles in from the edge behind them
    nodes.forEachIndexed { i, n ->
        if (n.kind != Kind.SRC || n.tile.out == NONE) return@forEachIndexed
        val back = opp(n.tile.out)
        val line = clearLine(n.cell, back)
        if (line == null) n.errs += "Something sits behind this input. An input takes marbles in from the edge of the build, so nothing may be behind it."
        else ports += Port(i, i, back, line, pastEdge(n.cell, back))
    }
    // ways out, in board order of where they leave
    exitsAt.sortedWith(compareBy({ pastEdge(nodes[it.first].cell, it.second).y }, { pastEdge(nodes[it.first].cell, it.second).x }))
        .forEachIndexed { k, (i, d) ->
            val at = nodes[i].cell
            val v = nodes.size
            nodes += Node(at.step(d), Tile(Kind.OUT, inSide = opp(d), id = k))
            g.link(i, v)
            ports += Port(v, i, d, clearLine(at, d)!!, pastEdge(at, d))
        }
    // one port per side, and no two lines to the edge crossing
    for ((side, ps) in ports.groupBy { it.side }) if (ps.size > 1) for (p in ps) nodes[p.piece].errs +=
        "${ps.size} belts enter or leave on the ${SIDE_NAMES[side]} side of the build; each side takes only one. " +
            if (p.isInput) "Move this input to a free side." else "Join ways out with a converger, or lead this one to a free side."
    val lineCells = ports.flatMap { p -> p.line.map { it to p } }.groupBy({ it.first }, { it.second })
    for ((_, ps) in lineCells) if (ps.size > 1) for (p in ps) {
        val msg = "This port's line to the edge of the build crosses another's."
        if (msg !in nodes[p.piece].errs) nodes[p.piece].errs += msg
    }
    for (n in nodes) {
        val p = n.pred.size
        val s = n.succ.size
        when (n.kind) {
            Kind.BELT -> if (p == 0) n.errs += "Nothing feeds this belt."
            Kind.SPLIT -> {
                if (p == 0) n.errs += "Nothing feeds this splitter."
                if (p > 1) n.errs += "A splitter takes exactly one input. Join the belts with a converger first."
                if (s == 0) n.errs += "This splitter feeds nothing. Put pieces or belts against its other sides."
                if (s == 3 && level.no3) n.errs += "3-way splits are off in this level."
            }
            Kind.MERGE -> if (p == 0) n.errs += "Nothing feeds this converger."
            Kind.SRC -> if (s == 0) n.errs += "The input faces nothing. Rotate it, or put a belt or piece against its arrow side."
            Kind.OUT -> Unit
        }
    }
    return g
}
