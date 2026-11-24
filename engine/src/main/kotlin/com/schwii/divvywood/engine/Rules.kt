package com.schwii.divvywood.engine

/** Sides: 0 right (east), 1 bottom (south), 2 left (west), 3 top (north). Screen coordinates: y grows downward. */
val DX = intArrayOf(1, 0, -1, 0)
val DY = intArrayOf(0, 1, 0, -1)
const val NONE = -1

fun opp(d: Int) = (d + 2) % 4
fun rotDir(d: Int) = (d + 1) % 4

data class Cell(val x: Int, val y: Int) {
    fun step(d: Int) = Cell(x + DX[d], y + DY[d])
    override fun toString() = "$x,$y"
}

/** The side of [a] that faces [b] (for neighbours). */
fun dirTo(a: Cell, b: Cell) = when {
    b.x > a.x -> 0
    b.y > a.y -> 1
    b.x < a.x -> 2
    else -> 3
}

/** [OUT] is never placed: it marks a way out of the build in the graph (see [Port]). */
enum class Kind { BELT, SPLIT, MERGE, SRC, OUT, BRIDGE }

/**
 * One placed block.
 * - belt: fed from [from], feeds [out]
 * - splitter: input on [inSide], outputs on its other open sides
 * - converger: output on [out], inputs on its other open sides
 * - input (src): sends out of [out] into the build from the edge behind it (see [settleInputs]); [id] numbers inputs
 *   IN1, IN2… (NONE reads as the first)
 * - way out (graph only): takes marbles on [inSide]; [id] numbers the build's ways out
 * - bridge: two belts crossing without meeting: the across lane runs out of [out] (right or left), the up-and-down
 *   lane out of [inSide] (bottom or top)
 * [closed] holds a splitter's or converger's closed sides, one bit per side (1 shl side): nothing joins them and
 * nothing leaves through them. Every side starts open.
 */
data class Tile(
    val kind: Kind,
    val from: Int = NONE,
    val out: Int = NONE,
    val inSide: Int = NONE,
    val id: Int = NONE,
    val closed: Int = 0,
) {
    fun isOpen(d: Int) = closed and (1 shl d) == 0
    fun open(d: Int) = copy(closed = closed and (1 shl d).inv())
    fun close(d: Int) = copy(closed = closed or (1 shl d))
}

/** A side mask turned a quarter clockwise. */
fun rotMask(m: Int) = ((m shl 1) or (m ushr 3)) and 15

typealias Tiles = MutableMap<Cell, Tile>

fun tilesOf(vararg pairs: Pair<Cell, Tile>): Tiles = linkedMapOf(*pairs)

fun sends(t: Tile?, d: Int): Boolean = when (t?.kind) {
    Kind.BELT, Kind.MERGE, Kind.SRC -> t.out == d
    Kind.BRIDGE -> t.out == d || t.inSide == d
    Kind.SPLIT -> t.inSide != d && t.isOpen(d)
    else -> false
}

fun accepts(t: Tile?, s: Int): Boolean = when (t?.kind) {
    Kind.BELT -> t.from == s
    Kind.SPLIT -> t.inSide == s
    Kind.MERGE -> t.out != s && t.isOpen(s)
    Kind.OUT -> t.inSide == s
    Kind.BRIDGE -> t.out == opp(s) || t.inSide == opp(s)
    else -> false
}

/** A bridge's two lanes as straight belts: across first, then up and down. */
fun bridgeLanes(t: Tile): List<Tile> = listOf(Tile(Kind.BELT, from = opp(t.out), out = t.out), Tile(Kind.BELT, from = opp(t.inSide), out = t.inSide))

/** A bridge carrying lanes leaving by [a] and [b] (one across, one up or down). */
fun bridgeOf(a: Int, b: Int): Tile = if (a % 2 == 0) Tile(Kind.BRIDGE, out = a, inSide = b) else Tile(Kind.BRIDGE, out = b, inSide = a)

private fun straight(t: Tile) = t.kind == Kind.BELT && t.from == opp(t.out)

/** Best guess for a new piece's direction from what touches it. */
fun inferDir(tiles: Map<Cell, Tile>, at: Cell, kind: Kind): Int {
    if (kind == Kind.SPLIT) {   // face whatever is sending into this cell
        for (pass in 0..1) for (d in 0..3) {
            val n = tiles[at.step(d)] ?: continue
            if (pass == 0 && n.kind == Kind.SPLIT) continue
            if (sends(n, opp(d))) return d
        }
        return 2
    }
    for (d in 0..3) {
        val n = tiles[at.step(d)] ?: continue
        if ((n.kind == Kind.BELT || n.kind == Kind.SPLIT) && accepts(n, opp(d))) return d
    }
    return 0
}

/** Fills in directions missing from layouts saved by earlier versions. */
fun orientMissing(tiles: Tiles) {
    for (c in tiles.keys.toList()) {
        val t = tiles.getValue(c)
        val fixed = when {
            t.kind == Kind.SPLIT && t.inSide == NONE -> t.copy(inSide = inferDir(tiles, c, t.kind))
            (t.kind == Kind.MERGE || t.kind == Kind.SRC) && t.out == NONE -> t.copy(out = inferDir(tiles, c, t.kind))
            else -> t
        }
        tiles[c] = fixed
    }
}

/**
 * Lays a belt along [path]. Pieces on the path are endpoints and never overwritten. A drag from piece to piece
 * sets their direction: a converger or input points out toward the drag; a splitter takes its input from it.
 */
fun commitPath(tiles: Tiles, path: List<Cell>) {
    val n = path.size
    for (i in 0 until n - 1) {
        val d = dirTo(path[i], path[i + 1])
        tiles[path[i]]?.let { a ->
            if (a.kind == Kind.MERGE || a.kind == Kind.SRC) tiles[path[i]] = a.copy(out = d)
            if (a.kind == Kind.SPLIT && a.inSide != d) tiles[path[i]] = a.open(d)   // drawing out of a side opens it
        }
        tiles[path[i + 1]]?.let { b ->
            if (b.kind == Kind.SPLIT) tiles[path[i + 1]] = b.copy(inSide = opp(d)).open(opp(d))
            if (b.kind == Kind.MERGE && b.out != opp(d)) tiles[path[i + 1]] = b.open(opp(d))
        }
    }
    for (i in 0 until n) {
        val ex = tiles[path[i]]
        // crossing a straight belt or a bridge at right angles, straight through, builds or reworks a bridge
        if (ex != null && i in 1 until n - 1 && crossesAt(path, i, ex)) {
            val d = dirTo(path[i], path[i + 1])
            val other = if (ex.kind == Kind.BELT) ex.out else if (d % 2 == 0) ex.inSide else ex.out
            tiles[path[i]] = bridgeOf(d, other)
            continue
        }
        if (ex != null && ex.kind != Kind.BELT) continue          // pieces are endpoints
        if (ex != null && i == n - 1 && i > 0) continue            // ending on a belt leaves that belt alone
        val out = if (i < n - 1) dirTo(path[i], path[i + 1]) else ex?.out ?: dirTo(path[i - 1], path[i])
        var from = if (i > 0) dirTo(path[i], path[i - 1]) else ex?.from ?: opp(out)
        if (from == out) from = opp(out)
        tiles[path[i]] = Tile(Kind.BELT, from = from, out = out)
    }
}

/**
 * Does [path] run straight through cell [i] at right angles to [t] there: a straight belt it would bridge, or a
 * bridge whose lane on that axis it would take over?
 */
fun crossesAt(path: List<Cell>, i: Int, t: Tile): Boolean {
    val d = dirTo(path[i], path[i + 1])
    if (dirTo(path[i - 1], path[i]) != d) return false
    return (straight(t) && t.out % 2 != d % 2) || t.kind == Kind.BRIDGE
}

/**
 * A belt path is valid when only its two ends touch pieces; in between it crosses empty squares or belts, or runs
 * straight through a bridge.
 */
fun pathValid(tiles: Map<Cell, Tile>, path: List<Cell>): Boolean {
    for (i in 1 until path.size - 1) {
        val t = tiles[path[i]] ?: continue
        if (t.kind != Kind.BELT && !(t.kind == Kind.BRIDGE && crossesAt(path, i, t))) return false
    }
    return path.size > 1
}

/** A bridge turned a quarter clockwise: its up-and-down lane becomes the across one and the other way round. */
fun rotBridge(t: Tile) = Tile(Kind.BRIDGE, out = rotDir(t.inSide), inSide = rotDir(t.out))

/** Rotate on one piece: a bridge's lanes, a belt's exit, a splitter's input side, a converger's or input's output side. */
fun turnPiece(t: Tile): Tile = when (t.kind) {
    Kind.BELT -> {
        var o = (t.out + 1) % 4
        if (o == t.from) o = (o + 1) % 4
        t.copy(out = o)
    }
    Kind.SPLIT -> t.copy(inSide = (t.inSide + 1) % 4, closed = rotMask(t.closed))
    Kind.MERGE -> t.copy(out = (t.out + 1) % 4, closed = rotMask(t.closed))
    Kind.OUT -> t.copy(inSide = (t.inSide + 1) % 4)
    Kind.SRC -> t.copy(out = (t.out + 1) % 4)
    Kind.BRIDGE -> rotBridge(t)
}

/** A splitter or converger lands on an empty square, or on a belt whose direction it takes over. */
fun placePiece(tiles: Tiles, at: Cell, kind: Kind): Boolean {
    val ex = tiles[at]
    if (ex != null && ex.kind != Kind.BELT) return false
    val dir = if (ex != null) {
        if (kind == Kind.SPLIT) ex.from else ex.out
    } else {
        inferDir(tiles, at, kind)
    }
    tiles[at] = if (kind == Kind.SPLIT) Tile(kind, inSide = dir) else Tile(kind, out = dir)
    return true
}

/**
 * Does [path] only retrace connections already there, each step in their direction? A belt step must follow a belt
 * that runs that way; a step between two pieces must cross a side joined both ways.
 */
fun isRetrace(tiles: Map<Cell, Tile>, path: List<Cell>): Boolean {
    if (path.size < 2) return false
    for (i in 0 until path.size - 1) {
        val a = tiles[path[i]] ?: return false
        val b = tiles[path[i + 1]] ?: return false
        val d = dirTo(path[i], path[i + 1])
        if (!sends(a, d) || !accepts(b, opp(d))) return false
    }
    return true
}

/**
 * Undoes a retraced [path]: its belts go (a bridge keeps its other lane as a belt), and each step from one piece
 * straight into another closes that side on both.
 */
fun eraseRetrace(tiles: Tiles, path: List<Cell>) {
    for (i in 0 until path.size - 1) {
        val a = tiles.getValue(path[i]); val b = tiles.getValue(path[i + 1])
        val d = dirTo(path[i], path[i + 1])
        if (a.kind != Kind.BELT && b.kind != Kind.BELT && a.kind != Kind.BRIDGE && b.kind != Kind.BRIDGE) {
            if (a.kind == Kind.SPLIT) tiles[path[i]] = a.close(d)
            if (b.kind == Kind.MERGE) tiles[path[i + 1]] = b.close(opp(d))
        }
    }
    for ((i, c) in path.withIndex()) {
        val t = tiles[c] ?: continue
        if (t.kind == Kind.BELT) tiles.remove(c)
        // a bridge retraced along one lane keeps the other as a belt
        if (t.kind == Kind.BRIDGE && i in 1 until path.size - 1) {
            val d = dirTo(c, path[i + 1])
            val keep = if (d % 2 == 0) t.inSide else t.out
            tiles[c] = Tile(Kind.BELT, from = opp(keep), out = keep)
        }
    }
}

/** The rectangle around the machine: every piece but the inputs, which sit outside it. */
fun machineBox(tiles: Map<Cell, Tile>): Box? = boxOf(tiles.filterValues { it.kind != Kind.SRC }.keys)

/**
 * A drag from a splitter straight out across the edge of the build, over nothing but empty cells: the side it leaves
 * by, or NONE when [path] isn't one.
 */
fun edgeSide(tiles: Map<Cell, Tile>, path: List<Cell>): Int {
    if (path.size < 2 || tiles[path[0]]?.kind != Kind.SPLIT) return NONE
    val box = machineBox(tiles) ?: return NONE
    val d = dirTo(path[0], path[1])
    for (i in 1 until path.size) {
        if (path[i] != path[i - 1].step(d) || path[i] in tiles) return NONE
    }
    return if (path.last() in box) NONE else d
}

/** Opens or closes side [d] of the splitter at [c] (never its input side). */
fun toggleSide(tiles: Tiles, c: Cell, d: Int) {
    val t = tiles.getValue(c)
    if (t.kind != Kind.SPLIT || t.inSide == d) return
    tiles[c] = if (t.isOpen(d)) t.close(d) else t.open(d)
}

/** The side an input dropped at [c] enters from: the side of the machine's box it lies beyond, or the nearest. */
fun entrySide(tiles: Map<Cell, Tile>, c: Cell): Int {
    val b = machineBox(tiles) ?: return 2
    val dist = intArrayOf(c.x - b.x1, c.y - b.y1, b.x0 - c.x, b.y0 - c.y)   // how far past each side
    return dist.indices.maxByOrNull { dist[it] }!!
}

/**
 * Moves every input to the edge: just outside the machine's box on the side it enters from ([opp] of its [Tile.out]),
 * in the same row or column, its lane. Inputs never count as part of the box, so they follow its edge as the machine
 * grows and shrinks. Leaves inputs alone while there is no machine yet.
 */
fun settleInputs(tiles: Tiles) {
    val b = machineBox(tiles) ?: return
    for ((c, t) in tiles.entries.toList()) {
        if (t.kind != Kind.SRC || t.out == NONE) continue
        val to = when (opp(t.out)) { 0 -> Cell(b.x1 + 1, c.y); 1 -> Cell(c.x, b.y1 + 1); 2 -> Cell(b.x0 - 1, c.y); else -> Cell(c.x, b.y0 - 1) }
        if (to == c) continue
        if (tiles[to]?.kind == Kind.SRC) continue   // another input already waits there; both are flagged
        tiles.remove(c); tiles[to] = t
    }
}
