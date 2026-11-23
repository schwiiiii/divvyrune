package com.schwii.divvywood.engine

/** A splitter skips an output only after it has stayed blocked this many ticks in a row. */
const val JAM_TICKS = 4
const val CHECK_TICKS = 3000
const val WARM_TICKS = 600

/**
 * A marble. [pf] is the node it was in at the start of this tick, [mt] the tick it last moved.
 * [curIn] / [prevIn] are the sides it entered its current and previous cell from (NONE for the centre), kept for
 * drawing it along the curved channels.
 */
class Item(val id: Int, var pf: Int, var mt: Int) {
    var curIn = NONE
    var prevIn = NONE
    var at = NONE
}

class SimNode(val kind: Kind, val id: Int, val succ: List<Int>, val pred: List<Int>) {
    var item: Item? = null
    var rr = 0
    var pass = 0
    var skips = 0
    var sunk = 0
    val jam = IntArray(succ.size)
}

/** A marble dropping into an output or bypass this tick, for the fade-out animation. */
class Ghost(val id: Int, val from: Int, val to: Int, val item: Item?)

class Snapshot(val tick: Int, val emitted: Int, val blocked: Int, val skips: Int, val sunk: IntArray)

/** The tick simulation. Every step moves each marble at most one cell; a cell holds at most one marble. */
class Sim(g: Graph, val t: Int) {
    val n = g.nodes.map { SimNode(it.kind, it.id, it.succ, it.pred) }
    val src = n.indexOfFirst { it.kind == Kind.SRC }
    /** Outputs first, then upstream: a cell empties before the one behind it tries to move in. */
    val order: List<Int>
    var tick = 0
    var emitted = 0
    var blocked = 0
    var skips = 0
    var lastBlock = -1_000_000_000
    var ghosts = listOf<Ghost>()
    /** This tick: nodes whose marble wanted to move on but couldn't, and the input if it couldn't send. */
    val waiting = BooleanArray(n.size)
    /** This tick: convergers that turned away a marble headed their way (busy with another input, or full). */
    val turnedAway = BooleanArray(n.size)
    /** Ticks each node spent waiting, and each converger spent turning marbles away, since the start. */
    val waits = IntArray(n.size)
    val turnAways = IntArray(n.size)
    private var want = 0
    private var nextId = 1

    init {
        val seen = HashSet<Int>()
        val q = ArrayList<Int>()
        n.indices.filter { n[it].kind == Kind.OUT || n[it].kind == Kind.DUMP }.forEach { seen += it; q += it }
        val out = ArrayList<Int>()
        var h = 0
        while (h < q.size) {
            val i = q[h++]
            out += i
            for (p in n[i].pred) if (seen.add(p)) q += p
        }
        n.indices.forEach { if (it !in seen) out += it }
        order = out
    }

    private fun isSink(k: Kind) = k == Kind.OUT || k == Kind.DUMP

    fun step() {
        val tk = ++tick
        val gh = ArrayList<Ghost>()
        n.forEachIndexed { i, s -> s.item?.pf = i }
        fun move(i: Int, j: Int) {
            val it = n[i].item!!
            n[i].item = null
            it.mt = tk
            n[j].item = it
            n[j].pass++
        }
        fun sink(i: Int, j: Int) {
            val it = n[i].item!!
            gh += Ghost(it.id, i, j, it)
            n[i].item = null
            n[j].sunk++
            n[j].pass++
        }
        for (i in order) {
            val s = n[i]
            val it = s.item
            if ((s.kind == Kind.BELT || s.kind == Kind.MERGE) && it != null && it.mt != tk && s.succ.isNotEmpty()) {
                val j = s.succ[0]
                val m = n[j]
                if (isSink(m.kind)) sink(i, j)
                else if (m.kind != Kind.MERGE && m.item == null) move(i, j)   // a converger pulls from its inputs itself
            } else if (s.kind == Kind.SPLIT && it != null && it.mt != tk && s.succ.isNotEmpty()) {
                // round robin with patience: an output counts as jammed only after JAM_TICKS blocked ticks in a row
                val k = s.succ.size
                val s0 = s.rr % k
                val j0 = s.succ[s0]
                val m0 = n[j0]
                when {
                    isSink(m0.kind) -> { sink(i, j0); s.jam[s0] = 0; s.rr = s0 + 1 }
                    m0.item == null && m0.kind != Kind.MERGE -> { move(i, j0); s.jam[s0] = 0; s.rr = s0 + 1 }
                    m0.item == null -> Unit   // a converger with room pulls this marble on its own turn
                    ++s.jam[s0] >= JAM_TICKS -> {
                        for (o in 1 until k) {
                            val sk = (s0 + o) % k
                            val j = s.succ[sk]
                            val m = n[j]
                            if (isSink(m.kind)) sink(i, j) else if (m.item == null) move(i, j) else continue
                            s.jam[sk] = 0; s.skips++; skips++; s.rr = sk + 1
                            break
                        }
                    }
                }
            }
            if (s.kind == Kind.MERGE && s.item == null && s.pred.isNotEmpty()) {
                // fair turns across every input headed here, splitters included
                val k = s.pred.size
                for (o in 0 until k) {
                    val sk = (s.rr + o) % k
                    val pi = s.pred[sk]
                    val p = n[pi]
                    val pit = p.item ?: continue
                    if (pit.mt == tk) continue
                    if (p.kind == Kind.BELT || p.kind == Kind.MERGE) { move(pi, i); s.rr = sk + 1; break }
                    if (p.kind == Kind.SPLIT) {
                        val ps = p.rr % p.succ.size
                        if (p.succ[ps] == i) { move(pi, i); p.jam[ps] = 0; p.rr = ps + 1; s.rr = sk + 1; break }
                    }
                }
            }
        }
        if (src >= 0) {
            val sn = n[src]
            if (tk % t == 0) want = 1
            if (want == 1 && sn.succ.isNotEmpty()) {
                val j = sn.succ[0]
                val m = n[j]
                when {
                    isSink(m.kind) -> { emitted++; want = 0; m.sunk++; m.pass++; gh += Ghost(nextId++, src, j, null) }
                    m.item == null -> { m.item = Item(nextId++, src, tk); m.pass++; emitted++; want = 0 }
                    else -> { blocked++; lastBlock = tk }
                }
            }
        }
        ghosts = gh
        noteWaits(tk)
    }

    private fun noteWaits(tk: Int) {
        waiting.fill(false); turnedAway.fill(false)
        n.forEachIndexed { i, s ->
            val it = s.item ?: return@forEachIndexed
            if (it.mt == tk || it.pf != i || s.succ.isEmpty()) return@forEachIndexed
            waiting[i] = true; waits[i]++
            val to = if (s.kind == Kind.SPLIT) s.succ[s.rr % s.succ.size] else s.succ[0]
            if (n[to].kind == Kind.MERGE) turnedAway[to] = true
        }
        if (src >= 0 && lastBlock == tk) {   // the input couldn't send: it waits too
            waiting[src] = true; waits[src]++
            n[src].succ.firstOrNull()?.let { if (n[it].kind == Kind.MERGE) turnedAway[it] = true }
        }
        turnedAway.forEachIndexed { j, b -> if (b) turnAways[j]++ }
    }

    fun snap() = Snapshot(tick, emitted, blocked, skips, IntArray(n.size) { n[it].sunk })
}
