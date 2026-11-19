package com.schwii.divvywood.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class Row(val id: Int, val exact: Q, val target: Q?, val measured: Double, val ok: Boolean)

class Verdict(val solved: Boolean, val reasons: List<String>, val rows: List<Row>, val skips: Int = 0)

private fun f3(x: Double) = String.format(Locale.ROOT, "%.3f", x)

/** True when node [i] carries more than one item per tick at this level's input rate. */
fun overloaded(g: Graph, sol: Solution, level: Level, i: Int): Boolean {
    val k = g.nodes[i].kind
    if (!sol.ok || k == Kind.OUT || k == Kind.DUMP || k == Kind.SRC) return false
    return (sol.flow[i] * Q.of(1, level.t.toLong())).toDouble() > 1 + 1e-12
}

/** The Check verdict. [w0] / [w1] bound the measuring window; without them only the structure is judged. */
fun judge(g: Graph, level: Level, sol: Solution, w0: Snapshot? = null, w1: Snapshot? = null): Verdict {
    val reasons = mutableListOf<String>()
    val rows = mutableListOf<Row>()
    val n = g.nodes
    if (n.none { it.kind == Kind.SRC }) reasons += "Place the input."
    level.out.forEachIndexed { id, tg ->
        if (tg != null && n.none { it.kind == Kind.OUT && it.id == id }) reasons += "Place output ${LETTERS[id]}."
    }
    if (g.errCount > 0) reasons += "${g.errCount} ${if (g.errCount == 1) "piece needs" else "pieces need"} fixing (outlined in red). Tap one to see why."
    if (reasons.isNotEmpty()) return Verdict(false, reasons, rows)
    if (!sol.ok) return Verdict(false, listOf(sol.why), rows)
    val em = if (w1 != null && w0 != null) w1.emitted - w0.emitted else 0
    level.out.forEachIndexed { id, tg ->
        val i = n.indexOfFirst { it.kind == Kind.OUT && it.id == id }
        if (i < 0) return@forEachIndexed   // an output without a target (sandbox) may stay in the tray
        val exact = sol.flow[i]
        val target = tg?.q
        val measured = if (em > 0) (w1!!.sunk[i] - w0!!.sunk[i]).toDouble() / em else Double.NaN
        val exactOk = target == null || exact == target
        val measOk = target == null || abs(measured - target.toDouble()) <= 0.01
        rows += Row(id, exact, target, measured, exactOk && measOk)
        if (target != null && !exactOk) reasons += "Output ${LETTERS[id]} gets $exact of the input. It needs $target."
        else if (target != null && !measOk) reasons += "Output ${LETTERS[id]} measured ${f3(measured)} against $target. Something is jamming."
    }
    val over = n.indices.count { overloaded(g, sol, level, it) }
    if (over > 0) reasons += "$over ${if (over == 1) "piece carries" else "pieces carry"} more than one item per tick (shown in red)."
    if (w1 != null && w0 != null) {
        val nominal = (w1.tick - w0.tick).toDouble() / level.t
        val thr = em / nominal
        if (thr < 0.99) reasons += "The input backed up and sent only ${(thr * 100).roundToInt()}% of its items."
    }
    return Verdict(!level.sandbox && reasons.isEmpty(), reasons, rows, if (w1 != null && w0 != null) w1.skips - w0.skips else 0)
}

/** Runs the full Check: exact solve plus CHECK_TICKS of simulation measured after WARM_TICKS. */
fun check(g: Graph, level: Level): Verdict {
    val sol = solve(g)
    val pre = judge(g, level, sol)
    if (pre.reasons.isNotEmpty() && (pre.rows.isEmpty() || !sol.ok)) return pre
    val s = Sim(g, level.t)
    var w0: Snapshot? = null
    for (t in 1..CHECK_TICKS) { s.step(); if (t == WARM_TICKS) w0 = s.snap() }
    return judge(g, level, sol, w0, s.snap())
}

class Score(val parts: Int, val belts: Int, val splits: Int, val merges: Int, val w: Int, val h: Int) {
    val area get() = w * h
}

fun scoreOf(tiles: Map<Cell, Tile>): Score {
    if (tiles.isEmpty()) return Score(0, 0, 0, 0, 0, 0)
    val belts = tiles.values.count { it.kind == Kind.BELT }
    val splits = tiles.values.count { it.kind == Kind.SPLIT }
    val merges = tiles.values.count { it.kind == Kind.MERGE }
    val xs = tiles.keys.map { it.x }
    val ys = tiles.keys.map { it.y }
    return Score(belts + splits + merges, belts, splits, merges, xs.max() - xs.min() + 1, ys.max() - ys.min() + 1)
}
