package com.schwii.divvywood.engine

import java.util.Locale
import kotlin.math.sign

/** An abstract network: pieces joined by belt chains of length [len], with no grid positions. */
class Net(private val len: Int = 2) {
    val nodes = mutableListOf<Node>()
    val g = Graph(nodes, emptyMap())
    fun add(kind: Kind, id: Int = NONE): Int {
        nodes += Node(Cell(nodes.size, 1_000_000), Tile(kind, id = id))
        return nodes.size - 1
    }
    fun link(a: Int, b: Int, n: Int = len) {
        var p = a
        repeat(n) { val bl = add(Kind.BELT); g.link(p, bl); p = bl }
        g.link(p, b)
    }
}

/** A real grid layout, drawn with the same path tool the app uses. */
class GridBuilder {
    val tiles: Tiles = linkedMapOf()
    /** A belt through the waypoints, in straight runs. */
    fun path(vararg pts: Pair<Int, Int>) {
        val cells = mutableListOf<Cell>()
        for (i in 0 until pts.size - 1) {
            var (x, y) = pts[i]
            val (x1, y1) = pts[i + 1]
            if (i == 0) cells += Cell(x, y)
            while (x != x1 || y != y1) {
                x += (x1 - x).sign; y += (y1 - y).sign
                cells += Cell(x, y)
            }
        }
        commitPath(tiles, cells)
    }
    fun put(x: Int, y: Int, kind: Kind, id: Int = NONE, inSide: Int = NONE, out: Int = NONE, from: Int = NONE) {
        tiles[Cell(x, y)] = Tile(kind, from = from, out = out, inSide = inSide, id = id)
    }
}

fun grid(build: GridBuilder.() -> Unit): Tiles = GridBuilder().apply(build).tiles.also { orientMissing(it); settleInputs(it) }

fun Map<Cell, Tile>.copyTiles(): Tiles = LinkedHashMap(this)

fun tiles(code: String): Tiles = decodeGrid(code).let { it.tiles ?: error(it.error!!) }

fun fmt(v: Verdict) = v.rows.joinToString(" ") { "${LETTERS[it.id]}=${it.exact}~${String.format(Locale.ROOT, "%.4f", it.measured)}" } +
    (if (v.reasons.isNotEmpty()) " | " + v.reasons.joinToString(" ") else "") + " skips=${v.skips}"

/** Shares measured at each way out (in board order) after a warm-up, plus skips and how much of its rate the input managed. */
class Measured(val share: (Int) -> Double, val skips: Int, val throughput: Double)

fun measure(tiles: Map<Cell, Tile>, t: Int, ticks: Int = 6000, warm: Int = 1000): Measured {
    val g = buildGraph(tiles, Level("m", t, listOf(null, null, null)))
    val s = Sim(g, t)
    var w0: Snapshot? = null
    for (k in 1..ticks) { s.step(); if (k == warm) w0 = s.snap() }
    val em = s.emitted - w0!!.emitted
    return Measured(
        share = { k -> val i = g.exits[k]; (s.n[i].sunk - w0.sunk[i]).toDouble() / em },
        skips = s.skips - w0.skips,
        throughput = em / ((ticks - warm).toDouble() / t),
    )
}

// ---- reference solutions ----

/** Abstract-network solutions, by level name: OUT nodes with an id are outputs, the one without carries the rest. */
val netRefs: Map<String, () -> Graph> = mapOf(
    "Quarter back" to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val m = add(Kind.MERGE); val b = add(Kind.SPLIT); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1)
        link(s, a); link(a, b); link(a, m); link(b, A); link(b, m); link(m, B); g } },
    "Sixth" to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val t = add(Kind.SPLIT); val m = add(Kind.MERGE); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1)
        link(s, a); link(a, t); link(a, m); link(t, A); link(t, m); link(t, m); link(m, B); g } },
    "Eighths" to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val b = add(Kind.SPLIT); val c = add(Kind.SPLIT); val mA = add(Kind.MERGE); val mB = add(Kind.MERGE)
        val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1)
        link(s, a); link(a, b); link(a, mB); link(b, mA); link(b, c); link(c, mA); link(c, mB); link(mA, A); link(mB, B); g } },
    "One two three" to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val t = add(Kind.SPLIT); val m = add(Kind.MERGE); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1); val C = add(Kind.OUT, 2)
        link(s, a); link(a, C); link(a, t); link(t, A); link(t, m); link(t, m); link(m, B); g } },
    "Seventh" to { Net().run { val s = add(Kind.SRC); val m = add(Kind.MERGE); val a = add(Kind.SPLIT); val b = add(Kind.SPLIT); val c = add(Kind.SPLIT); val r = add(Kind.MERGE); val R = add(Kind.OUT); val A = add(Kind.OUT, 0)
        link(s, m); link(m, a); link(a, r); link(a, b); link(b, r); link(b, c); link(c, A); link(c, m); link(r, R); g } },
    "Full belt" to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val b = add(Kind.SPLIT); val mA = add(Kind.MERGE); val mB = add(Kind.MERGE); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1)
        link(s, a); link(a, b); link(a, mB); link(a, mB); link(b, mA); link(b, mA); link(b, mB); link(mA, A); link(mB, B); g } },
    "Seventeenth" to { Net().run { val s = add(Kind.SRC); val m = add(Kind.MERGE); val h = add(Kind.SPLIT); val t1 = add(Kind.SPLIT); val t2 = add(Kind.SPLIT)
        val r = add(Kind.MERGE); val r2 = add(Kind.MERGE); val R = add(Kind.OUT); val A = add(Kind.OUT, 0)
        link(s, m); link(m, h); link(h, r); link(h, t1); link(t1, r); link(t1, r2); link(t1, t2); link(t2, A); link(t2, m); link(t2, r2); link(r, r2); link(r2, R); g } },
)

/** Grid solutions as text codes, by level name (found by the solver, fewest pieces). */
val textRefs: Map<String, String> = mapOf(
    "Quarter back" to ".. .. IB ..\nRL BL TB ..\n.. CT3 ST6 ..\n.. RT ST6 ..\n.. .. TR LR",
    "Sixth" to ".. .. .. BT\nRL RL BL BT\n.. BR CT6 BT\n.. CT3 SB7 LT\n.. RT SR6 BL\n.. .. .. IT",
    "Eighths" to "BT .. .. .. .. ..\nBT .. .. BR LR LR\nBT RB SB5 CT6 .. ..\nRT CL3 SR6 SR6 BL ..\n.. .. .. .. IT ..",
    "Two streams" to ".. .. .. .. IB ..\n.. RB SR3 SR3 TL ..\n.. TB TR CR6 LR LR\n.. TR LR LB .. ..\nRL RL RL CL3 BL ..\n.. .. .. .. IT2 ..",
    "Ninths" to ".. .. .. BT ..\nRL BL .. BT ..\n.. CT3 BL BT ..\n.. RT SB7 LT ..\n.. RB SR7 RL IL\n.. TR CR6 LB ..\n.. .. .. TB ..",
    "One two three" to ".. .. .. BT ..\nIR LR SL6 LT ..\n.. RB ST7 LR LR\n.. TR CR6 LB ..\n.. .. .. TB ..",
    "Twelfths" to "BT .. .. .. ..\nBT .. BR LR LR\nBT BR CT7 BL ..\nBT SR5 SB7 LT ..\nRT CL3 SR6 BL ..\n.. .. .. IT ..",
    "Swap" to ".. .. BT .. .. ..\n.. .. RT CL6 BL ..\nIR LR LR LT BT ..\n.. .. RB SB5 LT ..\n.. RB CL3 SB3 .. ..\n.. TB .. RT RL IL2\n.. TB .. .. .. ..",
    "Thirds by halves" to "RL BL .. ..\n.. SB6 LB ..\n.. SR5 CL5 ..\n.. TB RT IL\n.. TB .. ..",
    "Fifth" to "RL RL RL CL6 BL ..\n.. RB SR3 SB7 LT ..\n.. TB TR CT5 RL IL\n.. TB .. .. .. ..",
    "Seventh" to "BT .. .. .. .. ..\nBT .. BR CR3 LR CR3\nBT BR CT6 SB6 SL6 LT\nBT SR5 SR6 SR6 CL5 ..\nRT TL .. .. BT ..\n.. .. .. .. IT ..",
    "One two four" to ".. .. .. BT ..\nRL BL .. BT ..\n.. SR5 SB5 LT ..\n.. CR5 SL5 .. ..\n.. BT TR LR LR\n.. IT .. .. ..",
    "Tenth" to ".. .. .. BT .. ..\nRL BL .. BT .. ..\n.. CT3 SB5 CT6 .. ..\n.. RT SR7 SB3 .. ..\n.. .. TR CT5 RL BL\n.. .. .. .. .. IT",
    "Full belt" to ".. .. .. .. IB .. ..\n.. .. RB BL TB .. ..\nRB RL CL3 SR7 ST7 LB ..\nTB .. .. TR CB7 TL ..\nTB .. .. .. TR LR LR\nTB .. .. .. .. .. ..",
    "Fifths by halves" to ".. .. .. .. IB\n.. BR LR LB TB\n.. SR5 SB5 CB3 TB\n.. TB SR5 CL3 TL\n.. CB6 ST5 LB ..\nRL TL .. TB ..\n.. .. .. TB ..",
    "Seventeenth" to "RL RL CL6 CL6 CL6 CL6 CL6 BL ..\n.. BR CT6 SR5 SB7 SL3 SL7 LT ..\n.. BT RT ST7 CT5 BL TR LR LR\n.. RT RL TL .. BT .. .. ..\n.. .. .. .. .. IT .. .. ..",
    "Thirtieth" to ".. .. .. .. IB ..\n.. BR CB5 RL TL ..\nRB SR6 ST7 SL6 LB ..\nTB .. CB6 TL TB ..\nTR CB5 CL3 SR3 TL ..\n.. CB6 RL ST7 LR LR\n.. CB6 RL TL .. ..",
)
