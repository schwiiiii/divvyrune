package com.schwii.divvyrune.engine

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
    "Full belt" to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val b = add(Kind.SPLIT); val mA = add(Kind.MERGE); val mB = add(Kind.MERGE); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1)
        link(s, a); link(a, b); link(a, mB); link(a, mB); link(b, mA); link(b, mA); link(b, mB); link(mA, A); link(mB, B); g } },
)

/** Grid solutions as text codes, by level name (found by the solver, fewest pieces). */
val textRefs: Map<String, String> = mapOf(
    "Quarter back" to ".. BT .. .. ..\n.. RT SB5 LB ..\nIR LR SL5 TB ..\n.. .. CB6 TL ..\n.. .. TR LR LR",
    "Sixth" to "IR LR LB BR LR\n.. RB ST7 SL5 ..\n.. TR CB7 TL ..\n.. RB TL .. ..\n.. TB .. .. ..",
    "Eighths" to ".. BT .. ..\n.. RT CL6 BL\n.. .. SB6 SL5\nIR LR SL3 CB3\n.. .. RB TL\n.. .. TB ..",
    "Two streams" to ".. IB .. .. ..\nRL X RL BL ..\n.. TB BR CT6 ..\n.. TB SR5 SB3 ..\n.. TR X LT ..\n.. .. TB .. ..\n.. BR X LR LR\n.. RT CL3 BL ..\n.. .. .. IT2 ..",
    "Ninths" to ".. BT .. .. .. ..\n.. RT BL .. .. ..\n.. BR CT6 BR LR LR\n.. RT SB7 SL7 LB ..\nRL BL BT CB6 TL ..\n.. RT X TL .. ..\n.. .. IT .. .. ..",
    "One two three" to ".. .. .. BT ..\n.. BR CR3 LT ..\n.. SR5 SR7 BL ..\n.. TB TR X LR\nIR X LR LT ..",
    "Twelfths" to ".. BT .. .. ..\n.. RT BL .. ..\n.. BR CT7 BL ..\n.. SR5 SB7 LT ..\nRB CL3 SR6 RL IL\nTB .. .. .. ..",
    "Swap" to "IB2 .. .. .. .. .. .. ..\nX BL .. RB RL RL BL ..\nTB CT3 RL ST5 CR3 LR X LR\nTB RT RL SB5 LT .. BT ..\nTR LR LR LT .. .. BT ..\n.. .. .. .. .. .. IT ..",
    "Thirds by halves" to ".. BT .. .. ..\nBR X LB .. ..\nBT BT TB .. ..\nSR5 SB3 TB .. ..\nTR CT6 CR5 LB ..\n.. RT SB3 TB ..\n.. .. RT X IL\n.. .. .. TB ..",
    "Fifth" to ".. .. .. .. RB RL X IL\n.. .. .. RB X BL BT ..\n.. BR LR CB3 TR SL5 BT ..\nRL X RL ST7 CR3 CB3 BT ..\n.. RT RL ST5 LT TR LT ..",
    "Seventh" to "CL6 RL RL RL RL BL .. ..\nCT3 SB5 LB .. BR X LR LR\nRT SR6 X SB5 SL3 CT6 .. ..\n.. .. TR CT6 .. RT BL ..\n.. .. .. RT BL .. BT ..\n.. .. .. RB SR6 BL BT ..\n.. .. .. TR LR X LT ..\n.. .. .. .. .. IT .. ..",
    "One two four" to ".. BT .. .. .. .. ..\nIR X LR LR LB BR LB\n.. BT .. .. ST3 CT6 TB\n.. RT SR3 CL6 TL BT TB\n.. .. ST3 SL3 LR LT TB\n.. .. TR LR LR LR X\n.. .. .. .. .. .. TB",
    "Tenth" to "BT .. .. RB RL RL IL\nBT .. BR X CR3 LB ..\nBT .. BT TR SL5 TB ..\nRT CL6 SR7 CL6 TL TB ..\n.. RT ST5 LT .. TB ..\n.. .. .. .. .. TB ..",
    "Full belt" to ".. BT .. .. .. .. ..\n.. BT RB BL RB RL IL\n.. RT CL3 SR7 ST7 LB ..\n.. .. .. TR CB7 TL ..\nRL RL RL RL TL .. ..",
    "Fifths by halves" to ".. .. .. .. .. .. IB\n.. BR LR LB .. .. TB\n.. CT3 BL TR LB .. TB\n.. SR5 SR6 SR3 CL3 RL ST6\nRL X RL TL RB CL6 TL\n.. TR LR LR X LT ..\n.. .. .. .. TB .. ..",
    "Seventeenth" to ".. .. .. .. .. .. IB ..\n.. BR LR LR LR LB TB ..\n.. BT RB RL BL TB TB ..\n.. CT3 CL3 BL BT TB TB ..\nBR CT5 SB5 SL7 LT CR5 X LB\nRT RL SR7 CL5 RB SR7 TL TB\n.. RB ST6 RT X TL .. TB\n.. TB TR LR CR6 LR LR CR6\n.. TB .. .. .. .. .. ..",
    "Thirtieth" to ".. .. .. .. .. BT .. ..\n.. .. RB CL6 CL6 X BL ..\nRB RL CL3 SB7 SL3 LT BT ..\nTB .. RB SB3 BR CR3 LT ..\nTB RB ST7 CT5 SB7 LT .. ..\nCL3 CL3 TL .. RT RL RL IL",
)
