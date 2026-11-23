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
    if (!sol.ok || k == Kind.OUT || k == Kind.DUMP || k == Kind.SRC) return false
    return sol.flow[i] > Q.ONE
}

/** The Check verdict. [w0] / [w1] bound the measuring window; without them only the structure is judged. */
fun judge(g: Graph, level: Level, sol: Solution, w0: Snapshot? = null, w1: Snapshot? = null): Verdict {
    val reasons = mutableListOf<String>()
    val rows = mutableListOf<Row>()
    val n = g.nodes
    level.inputs.indices.forEach { k ->
        if (n.none { it.kind == Kind.SRC && it.inputId == k }) reasons += if (level.inputs.size == 1) "Place the input." else "Place input ${k + 1}."
    }
    level.out.forEachIndexed { id, tg ->
        if (tg != null && n.none { it.kind == Kind.OUT && it.id == id }) reasons += "Place output ${LETTERS[id]}."
    }
    if (g.errCount > 0) reasons += "${g.errCount} ${if (g.errCount == 1) "piece needs" else "pieces need"} fixing (outlined in red). Tap one to see why."
    if (reasons.isNotEmpty()) return Verdict(false, reasons, rows)
    if (!sol.ok) return Verdict(false, listOf(sol.why), rows)
    val window = if (w1 != null && w0 != null) w1.tick - w0.tick else 0
    // measured shares may stray this far from their targets: a hundredth of what the inputs send
    val tolerance = 0.01 * level.totalInput.toDouble()
    level.out.forEachIndexed { id, tg ->
        val i = n.indexOfFirst { it.kind == Kind.OUT && it.id == id }
        if (i < 0) return@forEachIndexed   // an output without a target may stay in the tray
        val exact = sol.flow[i]
        val target = tg?.q
        val measured = if (window > 0) (w1!!.sunk[i] - w0!!.sunk[i]).toDouble() / window else Double.NaN
        val exactOk = target == null || exact == target
        val measOk = target == null || abs(measured - target.toDouble()) <= tolerance
        rows += Row(id, exact, target, measured, exactOk && measOk)
        if (target != null && !exactOk) reasons += "Output ${LETTERS[id]} gets $exact of a belt. It needs $target."
        else if (target != null && !measOk) reasons += "Output ${LETTERS[id]} measured ${f3(measured)} against $target. Something is jamming."
    }
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
