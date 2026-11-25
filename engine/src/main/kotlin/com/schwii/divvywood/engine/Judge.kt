package com.schwii.divvywood.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class Row(val id: Int, val exact: Q, val target: Q?, val measured: Double, val ok: Boolean)

class Verdict(val solved: Boolean, val reasons: List<String>, val rows: List<Row>, val skips: Int = 0)

private fun f3(x: Double) = String.format(Locale.ROOT, "%.3f", x)

/** True when node [i] carries more than one marble per tick. */
@Suppress("UNUSED_PARAMETER")
fun overloaded(g: Graph, sol: Solution, level: Level, i: Int): Boolean {
    val k = g.nodes[i].kind
    if (!sol.ok || k == Kind.OUT || k == Kind.SRC) return false
    return sol.flow[i] > Q.ONE
}

/**
 * Which way out each final output takes: a node index per output, -1 where none fits. Outputs ask for rates, not
 * sides, so the ways out are matched to them: as many exact rates as possible, then as many outputs served as
 * possible, then the nearest rates; ties go to the ways out in board order. Without an exact solve they're taken in
 * order. Ways out left over carry the rest.
 */
fun assignOutputs(g: Graph, level: Level, sol: Solution): IntArray {
    val exits = g.exits
    val ids = level.out.indices.toList()
    val best = IntArray(level.out.size) { -1 }
    if (exits.isEmpty()) return best
    if (!sol.ok || exits.size > 6) { ids.zip(exits).forEach { (id, e) -> best[id] = e }; return best }
    var bestKey: Triple<Int, Int, Double>? = null
    val cur = IntArray(level.out.size) { -1 }
    fun score(): Triple<Int, Int, Double> {
        var exact = 0; var served = 0; var off = 0.0
        ids.forEach { id ->
            val e = cur[id]; if (e < 0) return@forEach
            served++
            val tg = level.out[id]
            if (tg == null || sol.flow[e] == tg.q) exact++ else off += kotlin.math.abs(sol.flow[e].toDouble() - tg.q.toDouble())
        }
        return Triple(exact, served, -off)
    }
    fun better(a: Triple<Int, Int, Double>, b: Triple<Int, Int, Double>?) = b == null ||
        a.first > b.first || (a.first == b.first && (a.second > b.second || (a.second == b.second && a.third > b.third + 1e-12)))
    fun search(k: Int, used: Set<Int>) {
        if (k == ids.size) { val sc = score(); if (better(sc, bestKey)) { bestKey = sc; cur.copyInto(best) }; return }
        for (e in exits) if (e !in used) { cur[k] = e; search(k + 1, used + e) }
        cur[k] = -1; search(k + 1, used)
    }
    search(0, emptySet())
    return best
}

/** The Check verdict. [w0] / [w1] bound the measuring window; without them only the structure is judged. */
fun judge(g: Graph, level: Level, sol: Solution, w0: Snapshot? = null, w1: Snapshot? = null): Verdict {
    val reasons = mutableListOf<String>()
    val rows = mutableListOf<Row>()
    val n = g.nodes
    level.inputs.indices.forEach { k ->
        if (n.none { it.kind == Kind.SRC && it.inputId == k }) reasons += if (level.inputs.size == 1) "Place the input." else "Place input ${k + 1}."
    }
    if (g.errCount > 0) reasons += "${g.errCount} ${if (g.errCount == 1) "piece needs" else "pieces need"} fixing (outlined in red). Tap one to see why."
    if (reasons.isNotEmpty()) return Verdict(false, reasons, rows)
    if (!sol.ok) return Verdict(false, listOf(sol.why), rows)
    val window = if (w1 != null && w0 != null) w1.tick - w0.tick else 0
    // measured shares may stray this far from their targets: a hundredth of what the inputs send
    val tolerance = 0.01 * level.totalInput.toDouble()
    val assigned = assignOutputs(g, level, sol)
    level.out.forEachIndexed { id, tg ->
        val i = assigned[id]
        if (i < 0) {
            if (tg != null) reasons += "Output ${LETTERS[id]} has no belt leaving the build. Lead one out to a free side."
            return@forEachIndexed
        }
        val exact = sol.flow[i]
        val target = tg?.q
        val measured = if (window > 0) (w1!!.sunk[i] - w0!!.sunk[i]).toDouble() / window else Double.NaN
        val exactOk = target == null || exact == target
        val measOk = target == null || abs(measured - target.toDouble()) <= tolerance
        rows += Row(id, exact, target, measured, exactOk && measOk)
        if (target != null && !exactOk) reasons += "Output ${LETTERS[id]} gets ${level.shown(exact)}. It needs ${level.shown(target)}."
        else if (target != null && !measOk) reasons += "Output ${LETTERS[id]} measured ${f3(level.shown(measured))} against ${level.shown(target)}. Something is jamming."
    }
    val rest = g.exits.count { it !in assigned }
    if (rest > 1) reasons += "$rest belts leave the build with what no output needs. Join them with a converger: the rest leaves on one side."
    if (n.filter { it.kind != Kind.SRC && it.kind != Kind.OUT }.map { it.cell }.distinct().size == 1) reasons += "A machine needs at least two pieces."
    val over = n.indices.count { overloaded(g, sol, level, it) }
    if (over > 0) reasons += "$over ${if (over == 1) "piece carries" else "pieces carry"} more than a full belt (shown in red)."
    if (w1 != null && w0 != null) level.inputs.forEachIndexed { k, r ->
        val sent = (w1.emittedBy.getOrElse(k) { 0 } - w0.emittedBy.getOrElse(k) { 0 }).toDouble()
        val thr = sent / (window * r.q.toDouble())
        val who = if (level.inputs.size == 1) "The input" else "Input ${k + 1}"
        if (thr < 0.99) reasons += "$who backed up and sent only ${(thr * 100).roundToInt()}% of its marbles."
    }
    return Verdict(reasons.isEmpty(), reasons, rows, if (w1 != null && w0 != null) w1.skips - w0.skips else 0)
}

/** Runs the full Check: exact solve plus CHECK_TICKS of simulation measured after WARM_TICKS. */
fun check(g: Graph, level: Level): Verdict {
    val sol = solve(g, level)
    val pre = judge(g, level, sol)
    if (pre.reasons.isNotEmpty() && (pre.rows.isEmpty() || !sol.ok)) return pre
    val s = Sim(g, level)
    var w0: Snapshot? = null
    for (t in 1..CHECK_TICKS) { s.step(); if (t == WARM_TICKS) w0 = s.snap() }
    return judge(g, level, sol, w0, s.snap())
}
