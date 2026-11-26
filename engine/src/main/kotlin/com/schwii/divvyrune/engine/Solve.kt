package com.schwii.divvyrune.engine

/** The exact flow through every node, in marbles per tick (a share of one full belt); or why there isn't one. */
class Solution(val ok: Boolean, val why: String = "", val flow: List<Q> = emptyList())

/**
 * Exact solve: each splitter divides evenly, each converger sums. Belt chains are collapsed so only pieces become
 * unknowns, then the linear system is solved by Gaussian elimination over exact fractions.
 */
fun solve(g: Graph, level: Level): Solution {
    val n = g.nodes
    val srcs = n.indices.filter { n[it].kind == Kind.SRC }
    if (srcs.isEmpty()) return Solution(false, if (level.inputs.size == 1) "Place the input." else "Place the inputs.")
    if (g.errCount > 0) return Solution(false, "Fix the outlined pieces first.")
    val reach = linkedSetOf<Int>().apply { addAll(srcs) }
    val stack = ArrayDeque(srcs)
    while (stack.isNotEmpty()) {
        val i = stack.removeLast()
        for (j in n[i].succ) if (reach.add(j)) stack += j
    }
    val keyIds = reach.filter { n[it].kind != Kind.BELT }
    val col = keyIds.withIndex().associate { (c, i) -> i to c }
    val m = keyIds.size
    val a = Array(m) { r -> Array(m) { c -> if (r == c) Q.ONE else Q.ZERO } }
    val b = Array(m) { Q.ZERO }
    for (s in srcs) b[col.getValue(s)] = level.inputs.getOrNull(n[s].inputId)?.q ?: Q.ZERO
    data class Chain(val from: Int, val share: Q, val belts: List<Int>)
    val chains = mutableListOf<Chain>()
    for (i in keyIds) {
        val share = if (n[i].kind == Kind.SPLIT) Q.of(1, n[i].succ.size.toLong()) else Q.ONE
        for (s in n[i].succ) {
            val belts = mutableListOf<Int>()
            var j = s
            var guard = 0
            while (n[j].kind == Kind.BELT) {
                belts += j
                j = n[j].succ.firstOrNull() ?: return Solution(false, "A belt loop has no pieces on it.")
                if (++guard > 100_000) return Solution(false, "A belt loop has no pieces on it.")
            }
            chains += Chain(i, share, belts)
            val r = col.getValue(j)
            val c = col.getValue(i)
            a[r][c] = a[r][c] - share
        }
    }
    for (c in 0 until m) {
        var p = c
        while (p < m && a[p][c].isZero) p++
        if (p == m) return Solution(false, "A loop has no way out, so items would circle forever.")
        a[c] = a[p].also { a[p] = a[c] }
        b[c] = b[p].also { b[p] = b[c] }
        for (r in 0 until m) {
            if (r == c || a[r][c].isZero) continue
            val f = a[r][c] / a[c][c]
            for (k in c until m) if (!a[c][k].isZero) a[r][k] = a[r][k] - f * a[c][k]
            b[r] = b[r] - f * b[c]
        }
    }
    val flow = MutableList(n.size) { Q.ZERO }
    keyIds.forEachIndexed { c, i -> flow[i] = b[c] / a[c][c] }
    for (ch in chains) {
        val f = flow[ch.from] * ch.share
        for (bl in ch.belts) flow[bl] = flow[bl] + f
    }
    return Solution(true, flow = flow)
}
