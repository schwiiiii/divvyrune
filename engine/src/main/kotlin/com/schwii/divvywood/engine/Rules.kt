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

enum class Kind { BELT, SPLIT, MERGE, SRC, OUT, DUMP }

/**
 * One placed block.
 * - belt: fed from [from], feeds [out]
 * - splitter: input on [inSide], outputs on every other side
 * - converger: output on [out], inputs on every other side
 * - input (src): sends out of [out]
 * - output / bypass (dump): takes items on [inSide] only; [id] numbers outputs A, B, C and bypasses
 */
data class Tile(
    val kind: Kind,
    val from: Int = NONE,
    val out: Int = NONE,
    val inSide: Int = NONE,
    val id: Int = NONE,
)

typealias Tiles = MutableMap<Cell, Tile>

fun tilesOf(vararg pairs: Pair<Cell, Tile>): Tiles = linkedMapOf(*pairs)

fun sends(t: Tile?, d: Int): Boolean = when (t?.kind) {
    Kind.BELT, Kind.MERGE, Kind.SRC -> t.out == d
    Kind.SPLIT -> t.inSide != d
    else -> false
}

fun accepts(t: Tile?, s: Int): Boolean = when (t?.kind) {
    Kind.BELT -> t.from == s
    Kind.SPLIT -> t.inSide == s
    Kind.MERGE -> t.out != s
    Kind.OUT, Kind.DUMP -> t.inSide == s   // outputs and bypasses take items from one side only
    else -> false
}

/** Best guess for a new piece's direction from what touches it. */
fun inferDir(tiles: Map<Cell, Tile>, at: Cell, kind: Kind): Int {
    if (kind == Kind.SPLIT || kind == Kind.OUT || kind == Kind.DUMP) {   // face whatever is sending into this cell
        for (pass in 0..1) for (d in 0..3) {
            val n = tiles[at.step(d)] ?: continue
            if (pass == 0 && n.kind == Kind.SPLIT) continue
            if (sends(n, opp(d))) return d
        }
        return 2
    }
    for (pass in 0..1) for (d in 0..3) {
        val n = tiles[at.step(d)] ?: continue
        val hit = if (pass == 0) (n.kind == Kind.BELT || n.kind == Kind.SPLIT) && accepts(n, opp(d))
        else n.kind == Kind.OUT || n.kind == Kind.DUMP
        if (hit) return d
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
            (t.kind == Kind.OUT || t.kind == Kind.DUMP) && t.inSide == NONE -> t.copy(inSide = inferDir(tiles, c, t.kind))
            else -> t
        }
        tiles[c] = fixed
    }
}

/**
 * Lays a belt along [path]. Pieces on the path are endpoints and never overwritten. A drag from piece to piece
 * sets their direction: a converger or input points out toward the drag; a splitter, output or bypass takes its
 * input from it.
 */
fun commitPath(tiles: Tiles, path: List<Cell>) {
    val n = path.size
    for (i in 0 until n - 1) {
        tiles[path[i]]?.let { a -> if (a.kind == Kind.MERGE || a.kind == Kind.SRC) tiles[path[i]] = a.copy(out = dirTo(path[i], path[i + 1])) }
        tiles[path[i + 1]]?.let { b ->
            if (b.kind == Kind.SPLIT || b.kind == Kind.OUT || b.kind == Kind.DUMP) tiles[path[i + 1]] = b.copy(inSide = dirTo(path[i + 1], path[i]))
        }
    }
    for (i in 0 until n) {
        val ex = tiles[path[i]]
        if (ex != null && ex.kind != Kind.BELT) continue          // pieces are endpoints
        if (ex != null && i == n - 1 && i > 0) continue            // ending on a belt leaves that belt alone
        val out = if (i < n - 1) dirTo(path[i], path[i + 1]) else ex?.out ?: dirTo(path[i - 1], path[i])
        var from = if (i > 0) dirTo(path[i], path[i - 1]) else ex?.from ?: opp(out)
        if (from == out) from = opp(out)
        tiles[path[i]] = Tile(Kind.BELT, from = from, out = out)
    }
}

/** A belt path is valid when only its two ends touch pieces; in between it crosses empty squares or belts. */
fun pathValid(tiles: Map<Cell, Tile>, path: List<Cell>): Boolean {
    for (i in 1 until path.size - 1) {
        val t = tiles[path[i]]
        if (t != null && t.kind != Kind.BELT) return false
    }
    return path.size > 1
}

/** Rotate on one piece: a belt's exit, a splitter's, output's or bypass's input side, a converger's or input's output side. */
fun turnPiece(t: Tile): Tile = when (t.kind) {
    Kind.BELT -> {
        var o = (t.out + 1) % 4
        if (o == t.from) o = (o + 1) % 4
        t.copy(out = o)
    }
    Kind.SPLIT, Kind.OUT, Kind.DUMP -> t.copy(inSide = (t.inSide + 1) % 4)
    Kind.MERGE, Kind.SRC -> t.copy(out = (t.out + 1) % 4)
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
