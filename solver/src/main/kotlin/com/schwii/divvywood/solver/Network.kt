package com.schwii.divvywood.solver

import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.Level
import com.schwii.divvywood.engine.NONE
import com.schwii.divvywood.engine.Q
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
 * and the rest. Branches whose shares all go to one place collapse into a single line. Lines into an output, and
 * into the rest, join through converger trees: the rest leaves the build on one side, as one more [Kind.OUT] with no
 * target (id NONE).
 */
fun synthesize(level: Level, rnd: Random): Net? {
    val net = Net()
    val outs = level.out.indices.map { net.add(Kind.OUT, it) }
    val need = level.out.map { it?.q ?: Q.ZERO }.toMutableList()
    val lines = LinkedHashMap<Int, MutableList<Int>>()   // destination node → nodes each sending it one line
    var restOut = -1
    val inputOrder = level.inputs.indices.shuffled(rnd)
    // share the outputs' needs among the inputs, greedily in a random order
    val give = Array(level.inputs.size) { Array(level.out.size) { Q.ZERO } }
    for (k in inputOrder) {
        var left = level.inputs[k].q
        for (j in level.out.indices.shuffled(rnd)) {
            if (left.isZero) break
            val take = if (need[j] < left) need[j] else left
            give[k][j] = take; need[j] = need[j] - take; left = left - take
        }
    }
    if (need.any { !it.isZero }) return null
    for (k in level.inputs.indices) {
        val r = level.inputs[k].q
        val src = net.add(Kind.SRC, k)
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
                -2 -> { if (restOut < 0) restOut = net.add(Kind.OUT); lines.getOrPut(restOut) { mutableListOf() } += from }
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
            cur = cur.shuffled(rnd).chunked(3).map { c ->
                if (c.size == 1) c[0] else net.add(Kind.MERGE).also { mg -> c.forEach { net.link(it, mg) } }
            }
        }
        net.link(cur[0], d)
    }
    return net
}
