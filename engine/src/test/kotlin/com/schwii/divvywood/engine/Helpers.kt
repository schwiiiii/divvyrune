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

fun grid(build: GridBuilder.() -> Unit): Tiles = GridBuilder().apply(build).tiles.also { orientMissing(it) }

fun Map<Cell, Tile>.copyTiles(): Tiles = LinkedHashMap(this)

fun fmt(v: Verdict) = v.rows.joinToString(" ") { "${LETTERS[it.id]}=${it.exact}~${String.format(Locale.ROOT, "%.4f", it.measured)}" } +
    (if (v.reasons.isNotEmpty()) " | " + v.reasons.joinToString(" ") else "") + " skips=${v.skips}"

/** Shares measured at each output after a warm-up, plus skips and how much of its rate the input managed. */
class Measured(val share: (Int) -> Double, val skips: Int, val throughput: Double)

fun measure(tiles: Map<Cell, Tile>, t: Int, ticks: Int = 6000, warm: Int = 1000): Measured {
    val g = buildGraph(tiles, Level("m", t, listOf(null, null, null), 9, ""))
    val s = Sim(g, t)
    var w0: Snapshot? = null
    for (k in 1..ticks) { s.step(); if (k == warm) w0 = s.snap() }
    val em = s.emitted - w0!!.emitted
    return Measured(
        share = { id -> val i = g.nodes.indexOfFirst { it.kind == Kind.OUT && it.id == id }; (s.n[i].sunk - w0.sunk[i]).toDouble() / em },
        skips = s.skips - w0.skips,
        throughput = em / ((ticks - warm).toDouble() / t),
    )
}

// ---- reference layouts ----

/** Compact layouts where pieces touch with no belts between them. */
val compact: Map<Int, Tiles> by lazy {
    mapOf(
        0 to grid { put(0, 1, Kind.SRC); put(1, 1, Kind.SPLIT, inSide = 2); put(1, 0, Kind.OUT, 0); put(1, 2, Kind.OUT, 1) },
        1 to grid {
            put(0, 0, Kind.SRC); put(1, 0, Kind.SPLIT, inSide = 2); put(2, 0, Kind.SPLIT, inSide = 2); put(2, -1, Kind.OUT, 0)
            put(1, 1, Kind.BELT, from = 3, out = 0); put(2, 1, Kind.MERGE, out = 1); put(2, 2, Kind.OUT, 1)
        },
    )
}

val gridRefs: Map<Int, Tiles> by lazy {
    mapOf(
        0 to grid {
            put(0, 0, Kind.SRC); put(2, 0, Kind.SPLIT); put(2, -2, Kind.OUT, 0); put(2, 2, Kind.OUT, 1)
            path(0 to 0, 2 to 0); path(2 to 0, 2 to -2); path(2 to 0, 2 to 2)
        },
        6 to grid {
            put(0, 0, Kind.SRC); put(2, 0, Kind.MERGE); put(4, 0, Kind.SPLIT); put(6, 0, Kind.SPLIT); put(8, 0, Kind.OUT, 0); put(4, -2, Kind.OUT, 1)
            path(0 to 0, 2 to 0); path(2 to 0, 4 to 0); path(4 to 0, 4 to -2); path(4 to 0, 6 to 0); path(6 to 0, 8 to 0)
            path(6 to 0, 6 to 2, 2 to 2, 2 to 0)
        },
        7 to grid {
            put(0, 0, Kind.SRC); put(2, 0, Kind.MERGE); put(4, 0, Kind.SPLIT); put(6, 0, Kind.SPLIT); put(8, 0, Kind.SPLIT)
            put(10, 0, Kind.OUT, 0); put(4, -2, Kind.OUT, 1)
            path(0 to 0, 2 to 0); path(2 to 0, 4 to 0); path(4 to 0, 4 to -2); path(4 to 0, 6 to 0); path(6 to 0, 8 to 0); path(8 to 0, 10 to 0)
            path(6 to 0, 6 to 2, 2 to 2, 2 to 0); path(8 to 0, 8 to -4, 2 to -4, 2 to 0)
        },
    )
}

/** Abstract-network solutions, by level index. */
val netRefs: Map<Int, () -> Graph> = mapOf(
    1 to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val m = add(Kind.MERGE); val b = add(Kind.SPLIT); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1)
        link(s, a); link(a, b); link(a, m); link(b, A); link(b, m); link(m, B); g } },
    2 to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1); val C = add(Kind.OUT, 2)
        link(s, a); link(a, A); link(a, B); link(a, C); g } },
    3 to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val t = add(Kind.SPLIT); val m = add(Kind.MERGE); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1)
        link(s, a); link(a, t); link(a, m); link(t, A); link(t, m); link(t, m); link(m, B); g } },
    4 to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val b = add(Kind.SPLIT); val c = add(Kind.SPLIT); val mA = add(Kind.MERGE); val mB = add(Kind.MERGE)
        val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1)
        link(s, a); link(a, b); link(a, mB); link(b, mA); link(b, c); link(c, mA); link(c, mB); link(mA, A); link(mB, B); g } },
    5 to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val t = add(Kind.SPLIT); val m = add(Kind.MERGE); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1); val C = add(Kind.OUT, 2)
        link(s, a); link(a, C); link(a, t); link(t, A); link(t, m); link(t, m); link(m, B); g } },
    8 to { Net().run { val s = add(Kind.SRC); val m = add(Kind.MERGE); val a = add(Kind.SPLIT); val b = add(Kind.SPLIT); val c = add(Kind.SPLIT); val D = add(Kind.DUMP, 0); val A = add(Kind.OUT, 0)
        link(s, m); link(m, a); link(a, D); link(a, b); link(b, D); link(b, c); link(c, A); link(c, m); g } },
    9 to { Net().run { val s = add(Kind.SRC); val a = add(Kind.SPLIT); val b = add(Kind.SPLIT); val mA = add(Kind.MERGE); val mB = add(Kind.MERGE); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1)
        link(s, a); link(a, b); link(a, mB); link(a, mB); link(b, mA); link(b, mA); link(b, mB); link(mA, A); link(mB, B); g } },
    10 to { Net().run { val s = add(Kind.SRC); val m = add(Kind.MERGE); val h = add(Kind.SPLIT); val t1 = add(Kind.SPLIT); val t2 = add(Kind.SPLIT)
        val D = add(Kind.DUMP, 0); val D2 = add(Kind.DUMP, 1); val A = add(Kind.OUT, 0)
        link(s, m); link(m, h); link(h, D); link(h, t1); link(t1, D); link(t1, D2); link(t1, t2); link(t2, A); link(t2, m); link(t2, D2); g } },
)

const val SKETCH_1_30 = "OR SR IL ..\nOR ST CB BL\nOR CL ST BT\n.. RT ST LT\n.. .. FT .."
