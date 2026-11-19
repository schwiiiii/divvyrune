package com.schwii.divvywood.engine

class Node(val cell: Cell, val tile: Tile) {
    val kind get() = tile.kind
    val id get() = tile.id
    val succ = mutableListOf<Int>()
    val pred = mutableListOf<Int>()
    val errs = mutableListOf<String>()
}

class Graph(val nodes: List<Node>, val idx: Map<Cell, Int>) {
    val errCount get() = nodes.count { it.errs.isNotEmpty() }

    /** Links [a] → [b] (used directly by tests that build abstract networks). */
    fun link(a: Int, b: Int) { nodes[a].succ += b; nodes[b].pred += a }
}

fun buildGraph(tiles: Map<Cell, Tile>, level: Level): Graph {
    val cells = tiles.keys.toList()
    val idx = cells.withIndex().associate { (i, c) -> c to i }
    val nodes = cells.map { Node(it, tiles.getValue(it)) }
    val g = Graph(nodes, idx)
    nodes.forEachIndexed { i, n ->
        for (d in 0..3) {
            if (!sends(n.tile, d)) continue
            val j = idx[n.cell.step(d)]
            if (j != null && accepts(nodes[j].tile, opp(d))) { g.link(i, j); continue }
            // a belt or converger has one way out, so a dead end there is an error
            if (n.kind != Kind.BELT && n.kind != Kind.MERGE) continue
            val what = if (n.kind == Kind.BELT) "This belt" else "This converger"
            val m = j?.let { nodes[it] }
            n.errs += when (m?.kind) {
                null -> "$what leads nowhere."
                Kind.BELT -> "$what runs into the side of a belt. Join them with a converger."
                Kind.SPLIT -> "$what runs into a splitter away from its input side. Rotate the splitter to face this way."
                Kind.MERGE -> "$what runs into the output side of a converger. Rotate the converger to face away."
                Kind.SRC -> "$what runs into the input."
                Kind.OUT -> "$what runs into the closed side of an output. Rotate it to face this way."
                Kind.DUMP -> "$what runs into the closed side of a bypass. Rotate it to face this way."
            }
        }
    }
    for (n in nodes) {
        val p = n.pred.size
        val s = n.succ.size
        when (n.kind) {
            Kind.BELT -> if (p == 0) n.errs += "Nothing feeds this belt."
            Kind.SPLIT -> {
                if (p == 0) n.errs += "Nothing feeds this splitter."
                if (p > 1) n.errs += "A splitter takes exactly one input. Join the belts with a converger first."
                if (s == 0) n.errs += "This splitter feeds nothing. Put pieces or belts against its other sides."
                if (s == 3 && level.no3) n.errs += "3-way splits are off in this level."
            }
            Kind.MERGE -> if (p == 0) n.errs += "Nothing feeds this converger."
            Kind.SRC -> if (s == 0) n.errs += "The input faces nothing. Rotate it, or put a belt or piece against its arrow side."
            Kind.OUT -> if (p == 0) n.errs += "Nothing reaches this output."
            Kind.DUMP -> Unit
        }
    }
    return g
}
