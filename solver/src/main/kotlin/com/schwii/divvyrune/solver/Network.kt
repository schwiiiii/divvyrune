package com.schwii.divvyrune.solver

import com.schwii.divvyrune.engine.Kind
import com.schwii.divvyrune.engine.Level
import com.schwii.divvyrune.engine.NONE
import com.schwii.divvyrune.engine.Q
import kotlin.random.Random

/** An abstract machine: pieces and the lines between them, with no positions yet. */
class Net {
    class Node(val kind: Kind, val id: Int = NONE) {
        val outs = mutableListOf<Int>()
        val ins = mutableListOf<Int>()
    }
    val nodes = mutableListOf<Node>()
    fun add(kind: Kind, id: Int = NONE): Int { nodes += Node(kind, id); return nodes.size - 1 }
    fun link(a: Int, b: Int) { nodes[a].outs += b; nodes[b].ins += a }
}

/** Every 2^a·3^b (only 2^a without 3-way splits) from [lo] to [hi]. */
internal fun smooth(lo: Long, hi: Long, threes: Boolean): List<Long> {
    val out = mutableListOf<Long>()
    var p3 = 1L
    while (p3 <= hi) {
        var p = p3
        while (p <= hi) { if (p >= lo) out += p; p *= 2 }
        if (!threes) break
        p3 *= 3
    }
    return out.sorted()
}

private fun lcm(a: java.math.BigInteger, b: java.math.BigInteger) = a / a.gcd(b) * b

/**
 * A random machine that meets [level]'s targets exactly, or null when this attempt finds none.
 *
 * Each input's marbles are first shared out between the outputs (whatever no output needs goes to bypasses). An input
 * then splits into N equal shares, N made of 2s and 3s and at least the shares' common denominator q: N − q shares loop
 * back to a converger at the input, so each remaining share is exactly 1/q of the input, and those go to the outputs
 * and the rest. An input may first split into P equal lines (P = 2, 3 or 4), each shared out as an input of its own:
 * with a full belt in, a loop's converger only fits on one belt this way. Branches whose shares all go to one place
 * collapse into a single line. Lines into an output join
 * through converger trees. Each leftover line ends in a [Kind.OUT] with no target (id NONE); the layout joins them
 * into one belt leaving on one side (see [openPorts]).
 */
fun synthesize(level: Level, rnd: Random): Net? =
    // a few tries, keeping the smallest: everything left over must join into one line, and fewer pieces lay out easier
    (1..6).mapNotNull { synthesizeOnce(level, rnd) }.minByOrNull { it.nodes.size }

private fun synthesizeOnce(level: Level, rnd: Random): Net? {
    val net = Net()
    val outs = level.out.indices.map { net.add(Kind.OUT, it) }
    val need = level.out.map { it?.q ?: Q.ZERO }.toMutableList()
    val lines = LinkedHashMap<Int, MutableList<Int>>()   // destination node → nodes each sending it one line
    // each input, or the P equal lines it splits into first: a line's root node and its rate
    val roots = mutableListOf<Pair<Int, Q>>()
    for (k in level.inputs.indices) {
        val ps = if (level.no3) listOf(1, 1, 2, 2, 4) else listOf(1, 1, 2, 2, 3, 4)
        val p = ps[rnd.nextInt(ps.size)]
        var line = listOf(net.add(Kind.SRC, k))
        for (f in if (p == 4) listOf(2, 2) else if (p == 1) emptyList() else listOf(p)) {
            line = line.flatMap { from -> val s = net.add(Kind.SPLIT); net.link(from, s); List(f) { s } }
        }
        line.forEach { roots += it to level.inputs[k].q / Q.of(p.toLong()) }
    }
    // share the outputs' needs among the lines, greedily in a random order
    val give = Array(roots.size) { Array(level.out.size) { Q.ZERO } }
    for (k in roots.indices.shuffled(rnd)) {
        var left = roots[k].second
        for (j in level.out.indices.shuffled(rnd)) {
            if (left.isZero) break
            val take = if (need[j] < left) need[j] else left
            give[k][j] = take; need[j] = need[j] - take; left = left - take
        }
    }
    if (need.any { !it.isZero }) return null
    for (k in roots.indices) {
        val (src, r) = roots[k]
        val parts = level.out.indices.map { give[k][it] / r }
        val rest = Q.ONE - parts.fold(Q.ZERO) { a, p -> a + p }
        val q = (parts + rest).filter { !it.isZero }.fold(java.math.BigInteger.ONE) { a, p -> lcm(a, p.d) }.toLong()
        if (q > 100_000) return null
        // N from q up; with a loop the converger at the input carries r·N/q, which must fit on one belt
        val maxN = minOf(2 * q, (Q.of(q) / r).let { it.n.toLong() / it.d.toLong() })
        val ns = smooth(q, maxOf(q, maxN), !level.no3).filter { it == q || Q.of(it) * r <= Q.of(q) }
        val n = (if (ns.isEmpty()) return null else ns[rnd.nextInt(minOf(ns.size, 3))]).toInt()
        val factors = mutableListOf<Int>()
        var m = n
        while (m % 3 == 0) { factors += 3; m /= 3 }
        while (m % 2 == 0) { factors += 2; m /= 2 }
        factors.shuffle(rnd)
        // the N shares' destinations, kept together so whole branches collapse
        val loop = if (n > q) net.add(Kind.MERGE) else -1
        val dest = mutableListOf<Int>()
        val blocks = mutableListOf<Pair<Int, Int>>()   // destination, count
        level.out.indices.forEach { j -> val c = (parts[j] * Q.of(q)).n.toInt(); if (c > 0) blocks += outs[j] to c }
        val dumps = (rest * Q.of(q)).n.toInt()
        blocks.shuffle(rnd)
        blocks.forEach { (d, c) -> repeat(c) { dest += d } }
        repeat(dumps) { dest += -2 }            // -2: out with the rest
        repeat(n - q.toInt()) { dest += -3 }    // -3: back round the loop
        if (rnd.nextBoolean()) dest.reverse()
        val root = if (loop >= 0) { net.link(src, loop); loop } else src
        fun send(from: Int, d: Int) {
            when (d) {
                -2 -> net.link(from, net.add(Kind.OUT))
                -3 -> lines.getOrPut(loop) { mutableListOf() } += from
                else -> lines.getOrPut(d) { mutableListOf() } += from
            }
        }
        fun build(from: Int, shares: List<Int>, fs: List<Int>) {
            if (shares.distinct().size == 1) { send(from, shares[0]); return }
            if (fs.isEmpty()) { send(from, shares[0]); return }
            val s = net.add(Kind.SPLIT)
            net.link(from, s)
            val size = shares.size / fs[0]
            for (g in 0 until fs[0]) build(s, shares.subList(g * size, (g + 1) * size), fs.drop(1))
        }
        build(root, dest, factors)
    }
    // join each destination's lines into one, through converger trees of up to three
    for ((d, ls) in lines.entries.toList()) {
        var cur: List<Int> = ls
        while (cur.size > 1) {
            // neighbouring lines join first, so a tree stays near the splitters that feed it
            cur = (if (rnd.nextInt(4) == 0) cur.shuffled(rnd) else cur).chunked(if (rnd.nextBoolean()) 2 else 3).map { c ->
                if (c.size == 1) c[0] else net.add(Kind.MERGE).also { mg -> c.forEach { net.link(it, mg) } }
            }
        }
        net.link(cur[0], d)
    }
    return net
}
