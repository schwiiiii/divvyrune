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

/** Counters a measuring window is taken from: [emittedBy] and [blockedBy] per input. */
class Snapshot(val tick: Int, val emitted: Int, val blocked: Int, val skips: Int, val sunk: IntArray, val emittedBy: IntArray = IntArray(0))

/** The tick simulation. Every step moves each marble at most one cell; a cell holds at most one marble. */
class Sim(g: Graph, val rates: List<Frac>) {
    /** A single input sending one marble every [t] ticks. */
    constructor(g: Graph, t: Int) : this(g, listOf(Frac(1, t)))
    constructor(g: Graph, level: Level) : this(g, level.inputs)

    val n = g.nodes.map { SimNode(it.kind, if (it.kind == Kind.SRC) it.inputId else it.id, it.succ, it.pred) }
    /** The input nodes, in input order; [src] is the first, or -1. */
    val srcs = n.indices.filter { n[it].kind == Kind.SRC }.sortedBy { n[it].id }
    val src = srcs.firstOrNull() ?: -1
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
    /** Per input (by input number): marbles sent, ticks held up, the last tick it was held up. */
    val emittedBy = IntArray(maxOf(rates.size, (srcs.maxOfOrNull { n[it].id } ?: -1) + 1))
    val blockedBy = IntArray(emittedBy.size)
    val lastBlockBy = IntArray(emittedBy.size) { -1_000_000_000 }
    private val want = BooleanArray(emittedBy.size)
    private var nextId = 1

    /** Input [k] is due a marble this tick when its running total, rate × ticks, passes a whole number. */
    private fun due(k: Int, tk: Int): Boolean {
        val r = rates.getOrNull(k) ?: return false
        return (tk.toLong() * r.n) / r.d > ((tk - 1).toLong() * r.n) / r.d
    }

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
        for (si in srcs) {
            val sn = n[si]
            val k = sn.id
            if (due(k, tk)) want[k] = true
            if (want[k] && sn.succ.isNotEmpty()) {
                val j = sn.succ[0]
                val m = n[j]
                when {
                    isSink(m.kind) -> { emitted++; emittedBy[k]++; want[k] = false; m.sunk++; m.pass++; gh += Ghost(nextId++, si, j, null) }
                    m.item == null -> { m.item = Item(nextId++, si, tk); m.pass++; emitted++; emittedBy[k]++; want[k] = false }
                    else -> { blocked++; lastBlock = tk; blockedBy[k]++; lastBlockBy[k] = tk }
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
        for (si in srcs) if (lastBlockBy[n[si].id] == tk) {   // an input that couldn't send waits too
            waiting[si] = true; waits[si]++
            n[si].succ.firstOrNull()?.let { if (n[it].kind == Kind.MERGE) turnedAway[it] = true }
        }
        turnedAway.forEachIndexed { j, b -> if (b) turnAways[j]++ }
    }

    /** The inputs' schedules repeat together every this many ticks. */
    private val schedule: Long = rates.fold(1L) { a, r -> lcm(a, r.d.toLong()) }

    /**
     * A 64-bit fingerprint of everything that decides the next ticks: which cells hold marbles, each splitter's and
     * converger's turn, each splitter's jam counts (capped where they stop mattering), each input's waiting marble,
     * and where the inputs' schedules stand. Marble numbers and animation don't count.
     */
    fun stateKey(): Long {
        var h = -0x340d631b7bdddcdbL
        fun mix(v: Long) { h = (h xor v) * 0x100000001b3L; h = h xor (h ushr 29) }
        mix(tick % schedule)
        n.forEach { s ->
            mix(if (s.item != null) 1 else 0)
            val k = when (s.kind) { Kind.SPLIT -> s.succ.size; Kind.MERGE -> s.pred.size; else -> 0 }
            if (k > 0) mix((s.rr % k).toLong())
            if (s.kind == Kind.SPLIT) s.jam.forEach { mix(minOf(it, JAM_TICKS).toLong()) }
        }
        want.forEach { mix(if (it) 1 else 0) }
        return h
    }

    fun snap() = Snapshot(tick, emitted, blocked, skips, IntArray(n.size) { n[it].sunk }, emittedBy.copyOf())
}

/** A build's repeating loop: after settling for [start] ticks, the whole board repeats every [length] ticks. */
data class Cycle(val start: Int, val length: Int) {
    /** Where [tick] falls in the loop, from 1 to [length]; null while the build is still settling. */
    fun step(tick: Int): Int? = if (tick < start) null else (tick - start) % length + 1
}

/**
 * Watches a [Sim] tick by tick and finds its loop: the first time a whole board state comes round again. A state is
 * everything that decides what happens next (see [Sim.stateKey]); watching stops after [maxTicks].
 */
class CycleFinder(private val sim: Sim, private val maxTicks: Int = MAX_CYCLE_TICKS) {
    private val seen = HashMap<Long, Int>()
    var cycle: Cycle? = null; private set

    /** Call after each step (and once before the first). */
    fun observe() {
        if (cycle != null || sim.tick > maxTicks) return
        val prev = seen.put(sim.stateKey(), sim.tick)
        if (prev != null) { cycle = Cycle(prev, sim.tick - prev); seen.clear() }
    }
}

/** How long cycle finding watches before giving up. */
const val MAX_CYCLE_TICKS = 20_000

/** Runs [level]'s build until its loop shows, or [maxTicks] pass. */
fun findCycle(g: Graph, level: Level, maxTicks: Int = MAX_CYCLE_TICKS): Cycle? {
    val s = Sim(g, level)
    val f = CycleFinder(s, maxTicks)
    f.observe()
    while (f.cycle == null && s.tick <= maxTicks) { s.step(); f.observe() }
    return f.cycle
}

/**
 * How freely marbles run, over one loop of the build: the share of marble-ticks spent waiting (a marble that can't
 * move on, or an input that can't send). [score] is 100 when no marble ever waits, and drops with the share; any
 * waiting at all keeps it below 100.
 */
class Flow(val waiting: Int, val marbleTicks: Int) {
    val share get() = if (marbleTicks == 0) 0.0 else waiting.toDouble() / marbleTicks
    val score get() = if (waiting == 0) 100 else minOf(99, (100 * (1 - share)).toInt())
}

/** [Flow] over one loop of [cycle], as found by [findCycle] for this build. */
fun flowOf(g: Graph, level: Level, cycle: Cycle): Flow {
    val s = Sim(g, level)
    while (s.tick < cycle.start) s.step()
    var waiting = 0; var marbles = 0
    repeat(cycle.length) {
        s.step()
        s.n.forEachIndexed { i, nd -> if (nd.item != null) marbles++; if (s.waiting[i]) waiting++ }
        marbles += s.srcs.count { s.waiting[it] }   // an input's held marble isn't on the board yet
    }
    return Flow(waiting, marbles)
}

private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
private fun lcm(a: Long, b: Long) = a / gcd(a, b) * b
