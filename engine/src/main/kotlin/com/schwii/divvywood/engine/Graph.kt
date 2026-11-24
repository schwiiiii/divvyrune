package com.schwii.divvywood.engine

/**
 * One node of the graph: a piece, a bridge's lane (a straight belt, [bridge] its tile), or a way out. A machine's
 * build is laid into the graph node by node, all on the machine's cell, with [machine] its tile: its inputs become
 * belts taking marbles in from side [inPort], its ways out belts sending them out of side [outPort].
 */
class Node(
    val cell: Cell, val tile: Tile, val bridge: Tile? = null,
    val machine: Tile? = null, val inPort: Int = NONE, val outPort: Int = NONE,
    /** Where it's drawn: cell [local] of the grid [frame] places on the board (the board's own grid outside machines). */
    val local: Cell = cell, val frame: Frame = Frame.BOARD,
    /** A machine's input or way out, at any depth: the machine as drawn, and the side of it its line meets. */
    val portView: MachineView? = null, val portSide: Int = NONE,
) {
    /** Inside a machine (drawn scaled down within the machine's cell). */
    val inside get() = machine != null
    val kind get() = tile.kind
    val id get() = tile.id
    /** An input's number (an unnumbered input is the first). */
    val inputId get() = if (tile.id >= 0) tile.id else 0
    val succ = mutableListOf<Int>()
    val pred = mutableListOf<Int>()
    val errs = mutableListOf<String>()
}

/**
 * A grid drawn on the board: its cell (x, y) spans [ox] + x·[s] to [ox] + (x + 1)·[s] across, in board cells, and
 * likewise down. The board's own grid is [BOARD]; a machine's build is drawn in a smaller grid within its cell.
 */
data class Frame(val ox: Double, val oy: Double, val s: Double) {
    fun cx(c: Cell) = ox + (c.x + 0.5) * s
    fun cy(c: Cell) = oy + (c.y + 0.5) * s
    /** [inner] (a frame relative to a grid of unit cells) placed within this one. */
    fun compose(inner: Frame) = Frame(ox + inner.ox * s, oy + inner.oy * s, inner.s * s)
    companion object { val BOARD = Frame(0.0, 0.0, 1.0) }
}

/** A machine as drawn: [tile] filling the square at ([x], [y]) of side [size], in board cells. */
class MachineView(val tile: Tile, val x: Double, val y: Double, val size: Double)

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
    /** Every machine in the build, nested ones after the one holding them, where each is drawn. */
    val machines = mutableListOf<MachineView>()
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
 * Marbles enter and leave on the sides of the box around the machine (its pieces but the inputs). Each input sits
 * just outside it and feeds the first piece in its row or column; each belt, converger or open splitter side pointing
 * at empty space with a clear line to the box's edge lets them out. Each side holds at most one of these ports. A way out becomes an [Kind.OUT] node after the board's nodes, in board
 * order of where it leaves.
 */
fun buildGraph(tiles: Map<Cell, Tile>, level: Level, lib: MachineLib = MachineLib.EMPTY, stack: Set<String> = emptySet()): Graph {
    val cells = tiles.keys.sortedWith(compareBy({ it.y }, { it.x }))
    val nodes = mutableListOf<Node>()
    val links = mutableListOf<Pair<Int, Int>>()   // inside machines, already known
    val machineIn = HashMap<Cell, Map<Int, Int>>()   // a machine's cell → its input nodes by side
    val machineErr = HashMap<Int, String>()
    val views = mutableListOf<MachineView>()
    for (c in cells) {
        val t = tiles.getValue(c)
        when (t.kind) {
            // a bridge is two nodes, its lanes, each a straight belt
            Kind.BRIDGE -> bridgeLanes(t).forEach { nodes += Node(c, it, t) }
            Kind.MACHINE -> {
                val base = nodes.size
                val why = layMachine(c, t, level, lib, stack, nodes, links, views)
                if (why != null) { nodes.subList(base, nodes.size).clear(); links.removeAll { it.first >= base }; nodes += Node(c, t, machine = t); machineErr[base] = why }
                else machineIn[c] = (base until nodes.size).filter { nodes[it].inPort != NONE }.associateBy { nodes[it].inPort }
            }
            else -> nodes += Node(c, t)
        }
    }
    val idx = HashMap<Cell, Int>().apply { nodes.forEachIndexed { i, n -> putIfAbsent(n.cell, i) } }
    /** The node at [c] that marbles entering from side [s] reach: on a bridge, the lane on that axis; on a machine, its input there. */
    fun nodeAt(c: Cell, s: Int): Int? {
        machineIn[c]?.let { return it[s] }
        val i = idx[c] ?: return null
        if (nodes[i].machine != null) return null
        return if (nodes[i].bridge != null && nodes[i].tile.from != s) i + 1 else i
    }
    val box = machineBox(tiles)
    val g = Graph(nodes, idx, mutableListOf(), box)
    g.machines += views
    for ((a, b) in links) g.link(a, b)
    for ((i, why) in machineErr) nodes[i].errs += why
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
            if (!sends(n.tile, d) || n.kind == Kind.SRC) continue
            if (n.inside && n.outPort != d) continue   // inside a machine, only its ways out reach the board
            val j = nodeAt(n.cell.step(d), opp(d))
            if (j != null && accepts(nodes[j].tile, opp(d))) { g.link(i, j); continue }
            // a side facing empty space with a clear line to the edge is a way out
            if (j == null && clearLine(n.cell, d) != null) { exitsAt += i to d; continue }
            // a belt or converger has one way on, so a dead end there is an error; a splitter's other sides may stay unused
            if (n.kind != Kind.BELT && n.kind != Kind.MERGE) continue
            val what = if (n.inside) "This machine" else if (n.bridge != null) "This bridge" else if (n.kind == Kind.BELT) "This belt" else "This converger"
            val m = j?.let { nodes[it] }
            if (m == null && tiles[n.cell.step(d)]?.kind == Kind.MACHINE) { n.errs += "$what runs into a machine where it has no input."; continue }
            if (m == null) { n.errs += "$what points at the edge of the build, but its way out there is blocked."; continue }
            n.errs += when (m.kind) {
                Kind.BELT -> "$what runs into the side of a belt. Join them with a converger."
                Kind.SPLIT -> "$what runs into a splitter away from its input side. Rotate the splitter to face this way."
                Kind.MERGE -> "$what runs into the output side of a converger. Rotate the converger to face away."
                Kind.SRC -> "$what runs into the input."
                Kind.OUT -> "$what runs into the way out."
                Kind.BRIDGE -> "$what runs into a bridge."
                Kind.MACHINE -> "$what runs into a machine where it has no input."
            }
        }
    }
    // an input sits outside the box and feeds the first piece in its lane
    nodes.forEachIndexed { i, n ->
        if (n.kind != Kind.SRC || n.tile.out == NONE) return@forEachIndexed
        val d = n.tile.out
        val line = mutableListOf<Cell>()
        var c = n.cell.step(d)
        while (box != null && c in box && c !in tiles) { line += c; c = c.step(d) }
        val j = if (box != null && c in box) nodeAt(c, opp(d)) else null
        when {
            j == null && box == null -> Unit   // nothing built yet: nothing to complain about
            j == null -> n.errs += "This input has nothing in its ${if (d % 2 == 0) "row" else "column"} to feed. Move it, or build in its way."
            !accepts(nodes[j].tile, opp(d)) -> n.errs += "The first piece in this input's way doesn't take marbles from this side."
            else -> { g.link(i, j); ports += Port(i, i, opp(d), line, n.cell) }
        }
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
            Kind.BELT -> if (p == 0) n.errs += if (n.inPort != NONE) "Nothing feeds this machine's input on its ${SIDE_NAMES[n.inPort]} side."
                else if (n.bridge != null) "Nothing feeds one of this bridge's lanes. A bridge needs a belt in and out on both." else "Nothing feeds this belt."
            Kind.SPLIT -> {
                if (p == 0) n.errs += "Nothing feeds this splitter."
                if (p > 1) n.errs += "A splitter takes exactly one input. Join the belts with a converger first."
                if (s == 0) n.errs += "This splitter feeds nothing. Put pieces or belts against its other sides."
                if (s == 3 && level.no3) n.errs += "3-way splits are off in this level."
            }
            Kind.MERGE -> if (p == 0) n.errs += "Nothing feeds this converger."
            Kind.SRC, Kind.OUT, Kind.BRIDGE, Kind.MACHINE -> Unit
        }
    }
    return g
}

/**
 * Lays machine [t] at [c] into [nodes]: its build turned its way, each node on [c], links inside it added to [links],
 * itself and any machines inside it to [views]. Its build is drawn within [c]: the smallest square centred where its
 * ports' lines cross (see [portAxes]) fills the cell, so each input and way out runs straight to the middle of its side. Its inputs become belts taking marbles in on their sides, its
 * ways out belts sending them out of theirs. Returns why it can't be laid (unknown, itself inside itself, its own
 * level's machine, or broken here), or null.
 */
private fun layMachine(
    c: Cell, t: Tile, level: Level, lib: MachineLib, stack: Set<String>,
    nodes: MutableList<Node>, links: MutableList<Pair<Int, Int>>, views: MutableList<MachineView>,
): String? {
    val h = t.ref ?: return "This machine has no build."
    val def = lib[h] ?: return "Machine #$h isn't one of yours. Build it from a code that includes it."
    if (h in stack) return "A machine can't hold itself."
    if (def.level != null && def.level == level.name) return "A level can't be solved with its own machine."
    val inner = lib.tiles(h) ?: return "Machine #$h can't be read."
    val ins = inner.values.count { it.kind == Kind.SRC }
    val tiles = turned(inner, t.out)
    val cg = buildGraph(tiles, Level(level.name, List(ins) { Frac(1, 1) }, emptyList(), level.no3), lib, stack + h)
    cg.nodes.firstOrNull { it.errs.isNotEmpty() }?.let { return "This machine doesn't work here: ${it.errs.first()}" }
    // the square centred where its ports' lines cross fills the cell, so each port meets the middle of its side
    val b = machineBox(tiles) ?: return "This machine is empty."
    val (col, row) = portAxes(cg) ?: return "This machine's ports don't line up across its middle, so it can't be a machine."
    val cx = col?.let { it + 0.5 } ?: ((b.x0 + b.x1 + 1) / 2.0)
    val cy = row?.let { it + 0.5 } ?: ((b.y0 + b.y1 + 1) / 2.0)
    val half = maxOf(cx - b.x0, b.x1 + 1 - cx, cy - b.y0, b.y1 + 1 - cy)
    val s = 1.0 / (2 * half)
    val fit = Frame(c.x + 0.5 - cx * s, c.y + 0.5 - cy * s, s)
    val me = MachineView(t, c.x.toDouble(), c.y.toDouble(), 1.0)
    views += me
    val inside = cg.machines.associateWith { v -> MachineView(v.tile, fit.ox + v.x * s, fit.oy + v.y * s, v.size * s) }
    views += inside.values
    val base = nodes.size
    val portOf = cg.ports.associateBy { it.node }
    cg.nodes.forEachIndexed { i, nd ->
        val p = portOf[i]
        val fr = fit.compose(nd.frame)
        nodes += when {
            nd.kind == Kind.SRC && p != null -> Node(c, Tile(Kind.BELT, from = p.side), machine = t, inPort = p.side, local = nd.local, frame = fr, portView = me, portSide = p.side)
            nd.kind == Kind.OUT && p != null -> Node(c, Tile(Kind.BELT, from = opp(p.side), out = p.side), machine = t, outPort = p.side, local = nd.local, frame = fr, portView = me, portSide = p.side)
            else -> Node(c, nd.tile, nd.bridge, machine = t, local = nd.local, frame = fr, portView = nd.portView?.let { inside[it] }, portSide = nd.portSide)
        }
    }
    cg.nodes.forEachIndexed { i, nd -> for (j in nd.succ) links += (base + i) to (base + j) }
    return null
}
