package com.schwii.divvywood.solver

import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.NONE
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.Tiles
import com.schwii.divvywood.engine.opp
import com.schwii.divvywood.engine.settleInputs
import com.schwii.divvywood.engine.bridgeOf
import kotlin.random.Random

/**
 * Lays [net] out on the grid, or returns null when this attempt boxes itself in.
 *
 * Pieces are placed in order from the inputs, each near the piece that first feeds it; every line is then a belt
 * routed by breadth-first search through free cells (or no belt at all, when the two pieces already touch on the
 * right sides). A piece's sides are fixed as lines arrive: a splitter's input, a way out's mouth, a converger's or
 * input's exit. [spread] is how far from its feeder a new piece may land. Ways out and inputs are then led to sides of
 * their own (see [openPorts]).
 */
fun layOut(net: Net, rnd: Random, spread: Int = 3): Tiles? {
    val n = net.nodes
    val tiles: Tiles = linkedMapOf()
    val pos = arrayOfNulls<Cell>(n.size)
    val side = IntArray(n.size) { NONE }        // input side (splitter, way out) or exit side (input, converger)
    val used = Array(n.size) { mutableSetOf<Int>() }
    val pieceAt = HashMap<Cell, Int>()

    fun free(c: Cell) = c !in tiles && c !in pieceAt
    fun exits(u: Int): List<Int> = when (n[u].kind) {
        Kind.SRC, Kind.MERGE -> if (side[u] != NONE) listOf(side[u]).filter { it !in used[u] } else (0..3).filter { it !in used[u] }
        Kind.SPLIT -> (0..3).filter { it != side[u] && it !in used[u] }
        else -> emptyList()
    }
    fun entries(v: Int): List<Int> = when (n[v].kind) {
        Kind.SPLIT, Kind.OUT -> if (side[v] != NONE) listOf(side[v]).filter { it !in used[v] } else (0..3).filter { it !in used[v] }
        // a converger keeps a side for its exit while that is still open
        Kind.MERGE -> (0..3).filter { it != side[v] && it !in used[v] && (side[v] != NONE || used[v].size < 3) }
        else -> emptyList()
    }
    fun claim(u: Int, d: Int, v: Int, e: Int) {
        used[u] += d; used[v] += e
        if (n[u].kind == Kind.SRC || n[u].kind == Kind.MERGE) side[u] = d
        if (n[v].kind != Kind.MERGE) side[v] = e
    }

    /** A belt from [u] to [v], both placed; false when no free route exists. */
    fun route(u: Int, v: Int): Boolean {
        val pu = pos[u]!!; val pv = pos[v]!!
        val ex = exits(u).shuffled(rnd); val en = entries(v)
        for (d in ex) if (pu.step(d) == pv && opp(d) in en) { claim(u, d, v, opp(d)); return true }
        val goals = HashMap<Cell, Int>()   // cell beside v → v's side it enters
        for (e in en) pv.step(e).takeIf { free(it) }?.let { goals[it] = e }
        val prev = HashMap<Cell, Cell?>()
        val startSide = HashMap<Cell, Int>()
        val queue = ArrayDeque<Cell>()
        for (d in ex) pu.step(d).takeIf { free(it) && it !in prev }?.let { prev[it] = null; startSide[it] = d; queue += it }
        var end: Cell? = null
        val box = 12 + n.size   // routes stay near the build
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c in goals) { end = c; break }
            for (d in (0..3).shuffled(rnd)) {
                // a straight belt across the way is bridged: the route hops over it
                val nx = c.step(d).let { if (bridgeable(tiles, it, d)) it.step(d) else it }
                if (nx in prev || !free(nx) || kotlin.math.abs(nx.x) > box || kotlin.math.abs(nx.y) > box) continue
                prev[nx] = c; queue += nx
            }
        }
        val last = end ?: return false
        val path = generateSequence(last) { prev[it] }.toList().reversed()
        val d0 = startSide.getValue(path[0]); val e = goals.getValue(last)
        layPath(tiles, path, opp(d0), opp(e))
        claim(u, d0, v, e)
        return true
    }

    /** Puts [v] somewhere free near [u] and routes [u] → [v]. */
    fun place(u: Int, v: Int): Boolean {
        val pu = pos[u]!!
        val spots = (-spread..spread).flatMap { dx -> (-spread..spread).map { dy -> Cell(pu.x + dx, pu.y + dy) } }
            .filter { free(it) && it != pu }
            .map { it to kotlin.math.abs(it.x - pu.x) + kotlin.math.abs(it.y - pu.y) + rnd.nextInt(3) }   // nearer first, loosely
            .sortedBy { it.second }.map { it.first }
        for (c in spots.take(24)) {
            pos[v] = c; pieceAt[c] = v
            val snapshot = Triple(LinkedHashMap(tiles), side.copyOf(), used.map { it.toSet() })
            if (route(u, v)) return true
            tiles.clear(); tiles.putAll(snapshot.first); snapshot.second.copyInto(side); used.forEachIndexed { i, s -> s.clear(); s += snapshot.third[i] }
            pieceAt.remove(c); pos[v] = null
        }
        return false
    }

    // inputs first, a little apart, then everything in the order lines reach it
    val queue = ArrayDeque<Int>()
    n.indices.filter { n[it].kind == Kind.SRC }.forEachIndexed { k, s -> pos[s] = Cell(0, k * 3); pieceAt[pos[s]!!] = s; queue += s }
    while (queue.isNotEmpty()) {
        val u = queue.removeFirst()
        // every line, even a second one to the same piece (two branches of a splitter into one converger)
        for (v in n[u].outs) {
            if (pos[v] == null) { if (!place(u, v)) return null; queue += v } else if (!route(u, v)) return null
        }
    }
    if (n.indices.any { pos[it] == null }) return null
    for (i in n.indices) {
        val k = n[i].kind
        val s = side[i]
        tiles[pos[i]!!] = when (k) {
            Kind.SRC -> Tile(k, out = s, id = n[i].id)
            // sides no line uses are closed, so nothing joins or leaks through them
            Kind.MERGE -> Tile(k, out = s, closed = (0..3).filter { it != s && it !in used[i] }.sumOf { 1 shl it })
            Kind.SPLIT -> Tile(k, inSide = s, closed = (0..3).filter { it != s && it !in used[i] }.sumOf { 1 shl it })
            else -> Tile(k, inSide = s, id = n[i].id)
        }
    }
    // ports are tried on several sides and in several orders before the layout is given up
    repeat(24) { openPorts(LinkedHashMap(tiles), rnd)?.let { return it } }
    return null
}

/**
 * Leads every port to a side of the build's box of its own: each way out (a placeholder [Kind.OUT] tile) becomes a
 * belt running on past the box's edge, and each input moves out past the edge with a belt back to where it stood.
 * Ports go in a random order, each to a random free side; null when one can't be routed.
 */
internal fun openPorts(tiles: Tiles, rnd: Random): Tiles? {
    // the leftover lines: one leads out, and the others join its belt afterwards
    val rests = tiles.filterValues { it.kind == Kind.OUT && it.id == NONE }.keys.toList()
    val main = rests.randomOrNull(rnd)
    // the leftover line leads out first, and the others join it while there is still room round the build
    val ports = listOfNotNull(main) + tiles.filter { (c, t) -> t.kind == Kind.SRC || (t.kind == Kind.OUT && t.id != NONE) }.keys.shuffled(rnd)
    if (ports.size > 4) return null
    val trunk = HashSet<Cell>()
    // the others step aside until then, keeping their cells free
    val waiting = rests.filter { it != main }.associateWith { tiles.remove(it)!! }.toMutableMap()
    val sides = (0..3).shuffled(rnd)
    val rays = HashSet<Cell>()   // the clear lines out of ports already led out, which later routes must keep clear
    for ((k, c) in ports.withIndex()) {
        val s = sides[k]
        val t = tiles.remove(c)!!
        val others = tiles.keys
        val x0 = others.minOf { it.x }; val x1 = others.maxOf { it.x }; val y0 = others.minOf { it.y }; val y1 = others.maxOf { it.y }
        fun beyond(p: Cell) = when (s) { 0 -> p.x > x1; 1 -> p.y > y1; 2 -> p.x < x0; else -> p.y < y0 }
        // breadth-first from the port's cell to the first cell past the edge on its side: inside the box if it can,
        // else round the outside of it, a cell or two out
        fun route(m: Int): List<Cell>? {
            fun inBounds(p: Cell) = p.x in (x0 - m - if (s == 2) 1 else 0)..(x1 + m + if (s == 0) 1 else 0) &&
                p.y in (y0 - m - if (s == 3) 1 else 0)..(y1 + m + if (s == 1) 1 else 0)
            val prev = HashMap<Cell, Cell?>().apply { put(c, null) }
            val queue = ArrayDeque(listOf(c))
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                if (beyond(p) && p != c) return generateSequence(p) { prev[it] }.toList().reversed()   // c … past the edge
                if (beyond(p)) continue
                for (d in (0..3).shuffled(rnd)) {
                    val nx = p.step(d).let { if (bridgeable(tiles, it, d) && it !in rays) it.step(d) else it }
                    if (nx in prev || nx in tiles || nx in rays || nx in waiting || !inBounds(nx)) continue
                    prev[nx] = p; queue += nx
                }
            }
            return null
        }
        val path = route(0) ?: route(1) ?: route(2) ?: return null
        var r = path.last().step(s)
        repeat(4) { rays += r; r = r.step(s) }
        if (path.size < 2) return null
        if (t.kind == Kind.OUT) {
            if (dirBetween(c, path[1]) == t.inSide) return null
            layPath(tiles, path, t.inSide, s)
            if (c == main) {
                trunk += path
                // each joins where it can; one boxed in now may reach a belt laid by another
                while (waiting.isNotEmpty()) {
                    val joined = waiting.keys.toList().filter { w -> joinRest(tiles, w, waiting.getValue(w), waiting.keys - w, trunk, rays, rnd).also { if (it) waiting.remove(w) } }
                    if (joined.isEmpty()) return null
                }
            }
        } else {
            // the input moves to the far end, its back to the edge; belts carry its marbles back to where it stood
            if (dirBetween(c, path[1]) == t.out) return null
            val rev = path.reversed()
            layPath(tiles, rev, s, t.out)
            tiles[rev[0]] = Tile(Kind.SRC, out = dirBetween(rev[0], rev[1]), id = t.id)
        }
    }
    settleInputs(tiles)
    return tiles
}

/**
 * Leads the leftover line ending at placeholder [w] (tile [t], already lifted off the board) into a belt of [trunk] (the leftover line that leads out), where a
 * converger put on that belt joins them. False when no free route reaches it.
 */
private fun joinRest(tiles: Tiles, w: Cell, t: Tile, others: Set<Cell>, trunk: MutableSet<Cell>, rays: Set<Cell>, rnd: Random): Boolean {
    if (tiles.isEmpty()) return false
    val x0 = tiles.keys.minOf { it.x } - 1; val x1 = tiles.keys.maxOf { it.x } + 1
    val y0 = tiles.keys.minOf { it.y } - 1; val y1 = tiles.keys.maxOf { it.y } + 1
    fun joinable(b: Cell, e: Int) = b in trunk && tiles[b]?.let { it.kind == Kind.BELT && e != it.from && e != it.out } == true
    val prev = HashMap<Cell, Cell?>().apply { put(w, null) }
    val queue = ArrayDeque(listOf(w))
    while (queue.isNotEmpty()) {
        val c = queue.removeFirst()
        val into = (0..3).shuffled(rnd).firstOrNull { (c != w || it != t.inSide) && joinable(c.step(it), opp(it)) }
        if (into != null) {
            val path = generateSequence(c) { prev[it] }.toList().reversed()
            for ((i, p) in path.withIndex()) {
                val from = if (i == 0) t.inSide else dirBetween(p, path[i - 1])
                val out = if (i == path.lastIndex) into else dirBetween(p, path[i + 1])
                tiles[p] = Tile(Kind.BELT, from = from, out = out)
            }
            val b = c.step(into); val bt = tiles.getValue(b); val e = opp(into)
            tiles[b] = Tile(Kind.MERGE, out = bt.out, closed = (0..3).filter { it != bt.out && it != bt.from && it != e }.sumOf { 1 shl it })
            trunk += path
            return true
        }
        for (d in (0..3).shuffled(rnd)) {
            if (c == w && d == t.inSide) continue
            val nx = c.step(d)
            if (nx in prev || nx in tiles || nx in rays || nx in others || nx.x !in x0..x1 || nx.y !in y0..y1) continue
            prev[nx] = c; queue += nx
        }
    }
    return false
}

/** Can a route heading [d] hop over [c]: a straight belt across its way? */
internal fun bridgeable(tiles: Map<Cell, Tile>, c: Cell, d: Int): Boolean {
    val t = tiles[c] ?: return false
    return t.kind == Kind.BELT && t.from == opp(t.out) && t.out % 2 != d % 2
}

/**
 * Lays belts along [path], entering its first cell from side [from] and leaving its last by [out]; where two cells
 * of the path are two apart, the belt between them becomes a bridge.
 */
internal fun layPath(tiles: Tiles, path: List<Cell>, from: Int, out: Int) {
    for ((i, c) in path.withIndex()) {
        val f = if (i == 0) from else dirBetween(c, path[i - 1])
        val o = if (i == path.lastIndex) out else dirBetween(c, path[i + 1])
        tiles[c] = Tile(Kind.BELT, from = f, out = o)
        if (i > 0 && kotlin.math.abs(c.x - path[i - 1].x) + kotlin.math.abs(c.y - path[i - 1].y) == 2) {
            val mid = Cell((c.x + path[i - 1].x) / 2, (c.y + path[i - 1].y) / 2)
            tiles[mid] = bridgeOf(dirBetween(path[i - 1], c), tiles.getValue(mid).out)
        }
    }
}

private fun dirBetween(a: Cell, b: Cell): Int = when {
    b.x > a.x -> 0; b.y > a.y -> 1; b.x < a.x -> 2; else -> 3
}

