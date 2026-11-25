package com.schwii.divvyrune.board

import com.schwii.divvyrune.engine.Cell
import com.schwii.divvyrune.engine.Kind
import java.util.Locale

/** What tapping a piece shows: a title, any errors, and label → value rows. */
data class ReadoutInfo(val title: String, val errors: List<String>, val rows: List<Pair<String, String>>, val note: String? = null)

private fun f3(x: Double) = String.format(Locale.ROOT, "%.3f", x)

/** [n] of [of] ticks, as a percentage. */
private fun pct(n: Int, of: Int) = if (of <= 0) "0%" else String.format(Locale.ROOT, "%.0f%% of ticks", 100.0 * n / of)

fun readoutOf(board: BoardState, cell: Cell): ReadoutInfo? {
    val i = board.graph.idx[cell] ?: return null
    val n = board.graph.nodes[i]
    n.machine?.let { m -> return machineReadout(board, cell, m) }
    val s = board.sim
    val level = board.level
    val title = when (n.kind) {
        Kind.BELT -> if (n.bridge != null) "Bridge" else "Belt"; Kind.SPLIT -> "Splitter"; Kind.MERGE -> "Converger"
        Kind.BRIDGE -> "Bridge"; Kind.MACHINE -> "Machine"
        Kind.SRC -> if (level.inputs.size == 1) "Input" else "Input ${n.inputId + 1}"
        Kind.OUT -> "Way out"
    }
    val rows = mutableListOf<Pair<String, String>>()
    var note: String? = null
    when (n.kind) {
        Kind.SRC -> {
            val k = n.inputId
            level.inputs.getOrNull(k)?.let { rows += "sends" to "$it of a belt" }
            board.inputRate(k)?.let { rows += "measured" to "${f3(it)} / tick" }
            val held = s.blockedBy.getOrElse(k) { 0 }
            if (held > 0) rows += "held up" to pct(held, s.tick)
        }
        Kind.OUT -> if (s.tick > 0) rows += "measured" to "${f3(s.n[i].sunk.toDouble() / s.tick)} / tick"
        else -> {
            // a bridge shows both its lanes
            val lanes = if (n.bridge != null) listOf(i, i + 1) else listOf(i)
            val errs = lanes.flatMap { board.graph.nodes[it].errs }
            if (errs.isEmpty() && board.sol.ok) {
                rows += "carries" to lanes.joinToString(" and ") { "${board.sol.flow[it]}" } + " of a belt"
                // a way out it leads to
                lanes.flatMap { board.graph.nodes[it].succ }.filter { board.graph.nodes[it].kind == Kind.OUT }.forEach { rows += "leaves as" to board.exitName(it) }
            } else if (errs.isEmpty()) note = board.sol.why
            if (s.tick > 0) rows += "measured" to "${f3(s.n[i].pass.toDouble() / s.tick)} / tick"
            if (n.kind == Kind.SPLIT && s.n[i].skips > 0) rows += "jam skips" to s.n[i].skips.toString()
            if (s.waits[i] > 0) rows += "waits" to pct(s.waits[i], s.tick)
            if (n.kind == Kind.MERGE && s.turnAways[i] > 0) rows += "turns away" to pct(s.turnAways[i], s.tick)
        }
    }
    val errs = if (n.bridge != null) (n.errs + board.graph.nodes[i + 1].errs).distinct() else n.errs.toList()
    return ReadoutInfo(title, errs, rows, note)
}

/** A machine: where it's from, how many pieces it holds, whether it's outdated, and anything wrong with it here. */
private fun machineReadout(board: BoardState, cell: Cell, m: com.schwii.divvyrune.engine.Tile): ReadoutInfo {
    val g = board.graph
    val errs = g.nodes.filter { it.cell == cell && it.inside }.flatMap { it.errs }.distinct()
    val rows = mutableListOf<Pair<String, String>>()
    val h = m.ref
    if (h != null) {
        rows += "machine" to "#$h"
        rows += "pieces" to com.schwii.divvyrune.engine.machinePieces(h, board.lib).toString()
        board.levelLabel(h)?.let { rows += "solves" to it }
        board.levelOf(h)?.let { rows += "in → out" to it.ratios }
    }
    val note = if (h != null && board.outdated(h)) "Its level has a better build now. Check moves this one to it, if its sides still match." else null
    return ReadoutInfo("Machine", errs, rows, note)
}
