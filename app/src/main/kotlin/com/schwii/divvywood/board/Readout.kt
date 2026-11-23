package com.schwii.divvywood.board

import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.LETTERS
import com.schwii.divvywood.engine.Q
import java.util.Locale

/** What tapping a piece shows: a title, any errors, and label → value rows. */
data class ReadoutInfo(val title: String, val errors: List<String>, val rows: List<Pair<String, String>>, val note: String? = null)

private fun f3(x: Double) = String.format(Locale.ROOT, "%.3f", x)

/** [n] of [of] ticks, as a percentage. */
private fun pct(n: Int, of: Int) = if (of <= 0) "0%" else String.format(Locale.ROOT, "%.0f%% of ticks", 100.0 * n / of)

fun readoutOf(board: BoardState, cell: Cell): ReadoutInfo? {
    val i = board.graph.idx[cell] ?: return null
    val n = board.graph.nodes[i]
    val s = board.sim
    val level = board.level
    val title = when (n.kind) {
        Kind.BELT -> "Belt"; Kind.SPLIT -> "Splitter"; Kind.MERGE -> "Converger"; Kind.SRC -> "Input"
        Kind.OUT -> "Output ${LETTERS[n.id]}"; Kind.DUMP -> "Bypass"
    }
    val rows = mutableListOf<Pair<String, String>>()
    var note: String? = null
    when (n.kind) {
        Kind.SRC -> {
            rows += "sends" to "1 item every ${if (level.t == 1) "tick" else "${level.t} ticks"}"
            if (s.tick > 0) rows += "measured" to "${f3(s.emitted.toDouble() / s.tick)} / tick"
            if (s.blocked > 0) rows += "held up" to pct(s.blocked, s.tick)
        }
        Kind.OUT, Kind.DUMP -> {
            if (n.kind == Kind.OUT) level.out.getOrNull(n.id)?.let { rows += "target" to it.toString() }
            if (s.emitted > 0) rows += "measured" to f3(s.n[i].sunk.toDouble() / s.emitted)
        }
        else -> {
            if (n.errs.isEmpty() && board.sol.ok) {
                val f = board.sol.flow[i]
                rows += "share of input" to f.toString()
                rows += "load" to "${f3((f * Q.of(1, level.t.toLong())).toDouble())} / 1 per tick"
            } else if (n.errs.isEmpty()) note = board.sol.why
            if (s.emitted > 0) rows += "measured" to f3(s.n[i].pass.toDouble() / s.emitted)
            if (n.kind == Kind.SPLIT && s.n[i].skips > 0) rows += "jam skips" to s.n[i].skips.toString()
            if (s.waits[i] > 0) rows += "waits" to pct(s.waits[i], s.tick)
            if (n.kind == Kind.MERGE && s.turnAways[i] > 0) rows += "turns away" to pct(s.turnAways[i], s.tick)
        }
    }
    return ReadoutInfo(title, n.errs.toList(), rows, note)
}
