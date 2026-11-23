package com.schwii.divvywood.solver

import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.NONE
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.Tiles
import com.schwii.divvywood.engine.opp
import kotlin.random.Random

/**
 * Lays [net] out on the grid, or returns null when this attempt boxes itself in.
 *
 * Pieces are placed in order from the inputs, each near the piece that first feeds it; every line is then a belt
 * routed by breadth-first search through free cells (or no belt at all, when the two pieces already touch on the
 * right sides). A piece's sides are fixed as lines arrive: a splitter's input, an output's or bypass's mouth, a
 * converger's or input's exit. [spread] is how far from its feeder a new piece may land.
 */
fun layOut(net: Net, rnd: Random, spread: Int = 3): Tiles? {
    val n = net.nodes
    val tiles: Tiles = linkedMapOf()
    val pos = arrayOfNulls<Cell>(n.size)
    val side = IntArray(n.size) { NONE }        // input side (splitter, output, bypass) or exit side (input, converger)
    val used = Array(n.size) { mutableSetOf<Int>() }
    val pieceAt = HashMap<Cell, Int>()

    fun free(c: Cell) = c !in tiles && c !in pieceAt
    fun exits(u: Int): List<Int> = when (n[u].kind) {
        Kind.SRC, Kind.MERGE -> if (side[u] != NONE) listOf(side[u]).filter { it !in used[u] } else (0..3).filter { it !in used[u] }
        Kind.SPLIT -> (0..3).filter { it != side[u] && it !in used[u] }
        else -> emptyList()
    }
    fun entries(v: Int): List<Int> = when (n[v].kind) {
        Kind.SPLIT, Kind.OUT, Kind.DUMP -> if (side[v] != NONE) listOf(side[v]).filter { it !in used[v] } else (0..3).filter { it !in used[v] }
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
                val nx = c.step(d)
                if (nx in prev || !free(nx) || kotlin.math.abs(nx.x) > box || kotlin.math.abs(nx.y) > box) continue
                prev[nx] = c; queue += nx
            }
        }
        val last = end ?: return false
        val path = generateSequence(last) { prev[it] }.toList().reversed()
        val d0 = startSide.getValue(path[0]); val e = goals.getValue(last)
        for ((i, c) in path.withIndex()) {
            val from = if (i == 0) opp(d0) else dirBetween(c, path[i - 1])
            val out = if (i == path.lastIndex) opp(e) else dirBetween(c, path[i + 1])
            tiles[c] = Tile(Kind.BELT, from = from, out = out)
        }
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
        for (c in spots.take(8)) {
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
            Kind.MERGE -> Tile(k, out = s)
            Kind.SPLIT -> Tile(k, inSide = s)
            else -> Tile(k, inSide = s, id = n[i].id)
        }
    }
    return tiles
}

private fun dirBetween(a: Cell, b: Cell): Int = when {
    b.x > a.x -> 0; b.y > a.y -> 1; b.x < a.x -> 2; else -> 3
}
