package com.schwii.divvywood.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs

private val HALVES = "IR LR SL5"   // a belt into a splitter fed from the left, its top and bottom open: one way out each
private val HALF = Level("halves", 2, listOf(Frac(1, 2), Frac(1, 2)))

class LevelsTest {
    @TestFactory
    fun `every level has a reference solution that passes Check with no jams`(): List<DynamicTest> =
        LEVELS.withIndex().flatMap { (li, level) ->
            val tests = mutableListOf<DynamicTest>()
            textRefs[level.name]?.let { code ->
                tests += DynamicTest.dynamicTest("L${li + 1} ${level.name} (grid)") {
                    val g = buildGraph(tiles(code), level)
                    assertEquals(0, g.errCount, g.nodes.filter { it.errs.isNotEmpty() }.joinToString { "${it.cell}: ${it.errs}" })
                    val v = check(g, level)
                    assertTrue(v.solved, fmt(v)); assertEquals(0, v.skips, fmt(v))
                }
            }
            netRefs[level.name]?.let { make ->
                tests += DynamicTest.dynamicTest("L${li + 1} ${level.name} (network)") {
                    val v = check(make(), level)
                    assertTrue(v.solved, fmt(v)); assertEquals(0, v.skips, fmt(v))
                }
            }
            if (tests.isEmpty()) tests += DynamicTest.dynamicTest("L${li + 1} has a reference") { throw AssertionError("no reference solution") }
            tests
        }

    @Test fun `every level fits on the four sides of a build`() {
        for (l in LEVELS) assertTrue(l.ports <= MAX_PORTS, "${l.name}: ${l.ports}")
        assertEquals(3, levelNamed("Seventh").ports, "input, A and the rest")
        assertEquals(3, HALF.ports, "nothing left over")
    }

    @Test fun `no level can be met by a single piece`() {
        for (l in LEVELS) assertFalse(l.onePiece, l.name)
        assertTrue(HALF.onePiece)
        assertTrue(Level("thirds", 2, listOf(Frac(1, 3), Frac(1, 3))).onePiece, "two thirds and the rest: a 3-way splitter")
        assertFalse(Level("thirds", 2, listOf(Frac(1, 3), Frac(1, 3)), no3 = true).onePiece)
        assertTrue(Level("join", listOf(Frac(1, 4), Frac(1, 2)), listOf(Frac(3, 4))).onePiece)
        assertTrue(Level("cross", listOf(Frac(1, 4), Frac(1, 2)), listOf(Frac(1, 2), Frac(1, 4))).onePiece, "a bridge")
        assertFalse(Level("swap", listOf(Frac(1, 3), Frac(2, 3)), listOf(Frac(1, 2), Frac(1, 2))).onePiece)
    }

    @Test fun `a level's ratios read what goes in and what comes out`() {
        assertEquals("1 → 1/4 · 3/4", levelNamed("Quarter back").ratios)
        assertEquals("1 + 1/2 → 3/4 · 3/4", levelNamed("Two streams").ratios)
        assertEquals("1 → 1/7 · 6/7", levelNamed("Seventh").ratios)
    }

    @Test fun `a single piece doesn't pass Check`() {
        val v = check(buildGraph(tiles("IR SL5"), HALF), HALF)
        assertFalse(v.solved); assertTrue("A machine needs at least two pieces." in v.reasons)
    }
}

class PortTest {
    private val halves = HALF

    @Test fun `a splitter at the edge sends straight out of its open sides`() {
        val g = buildGraph(tiles(HALVES), halves)
        assertEquals(0, g.errCount)
        assertEquals(2, g.exits.size)
        assertEquals(setOf(1, 3), g.ports.filter { !it.isInput }.map { it.side }.toSet())
        assertTrue(check(g, halves).solved)
    }

    @Test fun `every open side facing the edge is a way out`() {
        val g = buildGraph(tiles("IR SL7"), halves)
        assertEquals(3, g.exits.size)
        val v = check(g, halves)
        assertFalse(v.solved)
        assertTrue(v.reasons.any { "gets 1/6" in it }, v.reasons.toString())
    }

    @Test fun `a belt pointing at the edge is a way out`() {
        val level = Level("one", 2, listOf(Frac(1, 1)))
        assertTrue(check(buildGraph(tiles("IR LR LR"), level), level).solved)
    }

    @Test fun `two ways out on one side are flagged on both`() {
        val g = buildGraph(tiles(".. BR LR\nIR SL5 ..\n.. TR LR"), halves)
        val flagged = g.nodes.filter { n -> n.errs.any { "right side" in it } }.map { it.cell }.toSet()
        assertEquals(setOf(Cell(2, 0), Cell(2, 2)), flagged)
    }

    @Test fun `a belt whose way to the edge is blocked is flagged`() {
        val g = buildGraph(tiles("IR LR .. SB5\n.. .. .. .."), halves)
        assertTrue(g.nodes[g.idx.getValue(Cell(1, 0))].errs.any { "blocked" in it })
    }

    @Test fun `an input sits just outside the machine and feeds the first piece in its row`() {
        val t = tiles("IR .. .. LR SL5")
        assertEquals(Kind.SRC, t[Cell(2, 0)]?.kind, "it moves up to the machine's edge")
        assertEquals(HALVES, encodeGrid(t))
        assertTrue(check(buildGraph(t, halves), halves).solved)
    }

    @Test fun `an input follows the edge as the machine shrinks and grows`() {
        val t = tiles(HALVES)
        t.remove(Cell(1, 0)); settleInputs(t)
        assertEquals(Kind.SRC, t[Cell(1, 0)]?.kind)
        t[Cell(-2, 5)] = Tile(Kind.BELT, from = 2, out = 0); settleInputs(t)
        assertEquals(Kind.SRC, t[Cell(-3, 0)]?.kind)
    }

    @Test fun `an input with nothing in its row is flagged`() {
        val g = buildGraph(tiles("IR ..\n.. SL5"), halves)
        assertTrue(g.nodes.first { it.kind == Kind.SRC }.errs.any { "nothing in its row" in it })
    }

    @Test fun `outputs are matched to ways out by rate`() {
        val level = levelNamed("Quarter back")
        val g = buildGraph(tiles(textRefs.getValue("Quarter back")), level)
        val v = check(g, level)
        assertTrue(v.solved, fmt(v))
        assertEquals(listOf(Q.of(1, 4), Q.of(3, 4)), v.rows.sortedBy { it.id }.map { it.exact })
    }

    @Test fun `an output with no way out is named`() {
        val v = check(buildGraph(tiles("IR LR"), halves), halves)
        assertTrue(v.reasons.any { "Output B has no belt leaving" in it }, v.reasons.toString())
    }

    @Test fun `what no output needs leaves on one side`() {
        val level = Level("sixth", 2, listOf(Frac(1, 3)))
        val v = check(buildGraph(tiles("IR SL7"), level), level)
        assertTrue(v.reasons.any { "2 belts leave the build with what no output needs" in it }, v.reasons.toString())
    }
}

class ClosedSideTest {
    @Test fun `retracing a join closes it and drawing it again opens it`() {
        val t = tiles("IR SL7 CB")
        val path = listOf(Cell(1, 0), Cell(2, 0))
        assertTrue(isRetrace(t, path))
        eraseRetrace(t, path)
        assertFalse(t.getValue(Cell(1, 0)).isOpen(0)); assertFalse(t.getValue(Cell(2, 0)).isOpen(2))
        val g = buildGraph(t, HALF)
        assertTrue(g.nodes[g.idx.getValue(Cell(1, 0))].succ.none { g.nodes[it].kind == Kind.MERGE })
        assertFalse(isRetrace(t, path))
        commitPath(t, path)
        assertTrue(t.getValue(Cell(1, 0)).isOpen(0)); assertTrue(t.getValue(Cell(2, 0)).isOpen(2))
    }

    @Test fun `retracing a belt removes it`() {
        val t = tiles("IR LR LR SL5")
        eraseRetrace(t, listOf(Cell(1, 0), Cell(2, 0), Cell(3, 0)).also { assertTrue(isRetrace(t, it)) })
        assertNull(t[Cell(1, 0)]); assertNull(t[Cell(2, 0)]); assertEquals(Kind.SPLIT, t[Cell(3, 0)]?.kind)
    }

    @Test fun `dragging a splitter's side across the edge opens or closes it`() {
        val t = tiles("IR SL7")
        val up = listOf(Cell(1, 0), Cell(1, -1))
        assertEquals(3, edgeSide(t, up))
        assertEquals(NONE, edgeSide(t, up + Cell(1, -2)), "a longer drag draws belts")
        toggleSide(t, Cell(1, 0), 3)
        assertEquals(2, buildGraph(t, HALF).exits.size)
        toggleSide(t, Cell(1, 0), 3)
        assertEquals(3, buildGraph(t, HALF).exits.size)
        assertEquals(NONE, edgeSide(t, listOf(Cell(1, 0), Cell(2, 0), Cell(2, 1))), "only straight out")
    }

    @Test fun `codes list open sides clockwise from the marked side`() {
        val t = tiles("ST3")
        val s = t.getValue(Cell(0, 0))
        assertTrue(s.isOpen(0)); assertTrue(s.isOpen(1)); assertFalse(s.isOpen(2))
        assertEquals("ST3", encodeGrid(t))
        assertEquals("ST7", encodeGrid(tiles("ST")), "a plain code has every side open")
        assertEquals("CR5", encodeGrid(tiles("CR5")))
    }

    @Test fun `turning a piece turns its closed sides with it`() {
        val s = Tile(Kind.SPLIT, inSide = 3, closed = 1 shl 2)
        val r = turnPiece(s)
        assertEquals(0, r.inSide); assertFalse(r.isOpen(3)); assertTrue(r.isOpen(2))
    }
}

class BridgeTest {
    private val cross = Level("cross", listOf(Frac(1, 2), Frac(1, 4)), listOf(Frac(1, 2), Frac(1, 4)))

    @Test fun `two lines cross on a bridge without meeting`() {
        val t = tiles(".. IB2 ..\nIR X LR\n.. TB ..")
        val b = t.getValue(Cell(1, 1))
        assertEquals(Kind.BRIDGE, b.kind); assertEquals(0, b.out); assertEquals(1, b.inSide)
        val g = buildGraph(t, cross)
        assertEquals(0, g.errCount)
        val v = check(g, cross)
        assertTrue(v.solved, fmt(v))
        assertEquals(".. IB2 ..\nIR X LR\n.. TB ..", encodeGrid(t))
    }

    @Test fun `drawing straight across a belt builds a bridge`() {
        val t = tiles(".. IB2\n.. TB\n.. TB")
        commitPath(t, listOf(Cell(0, 1), Cell(1, 1), Cell(2, 1)))
        assertEquals(Tile(Kind.BRIDGE, out = 0, inSide = 1), t[Cell(1, 1)])
        assertTrue(pathValid(t, listOf(Cell(2, 1), Cell(1, 1), Cell(0, 1))), "a bridge lets a straight line through")
        commitPath(t, listOf(Cell(2, 1), Cell(1, 1), Cell(0, 1)))
        assertEquals(Tile(Kind.BRIDGE, out = 2, inSide = 1), t[Cell(1, 1)], "drawing the other way turns that lane")
        assertFalse(pathValid(t, listOf(Cell(1, 0), Cell(1, 1), Cell(2, 1))), "a turn on a bridge isn't")
    }

    @Test fun `a bridge counts as one piece and turns with a group`() {
        val t = tiles(".. IB2 ..\nIR X LR\n.. TB ..")
        assertEquals(3, metricsOf(t).pieces); assertEquals(1, metricsOf(t).bridges)
        val b = Tile(Kind.BRIDGE, out = 0, inSide = 3)
        assertEquals(b, turnPiece(turnPiece(turnPiece(turnPiece(b)))))
        assertEquals(Tile(Kind.BRIDGE, out = 2, inSide = 1), rotBridge(Tile(Kind.BRIDGE, out = 0, inSide = 1)))
    }

    @Test fun `retracing one lane of a bridge leaves the other as a belt`() {
        val t = tiles(".. IB2 ..\nIR X LR\n.. TB ..")
        val across = listOf(Cell(0, 1), Cell(1, 1), Cell(2, 1))
        assertTrue(isRetrace(t, across))
        eraseRetrace(t, across)
        assertEquals(Tile(Kind.BELT, from = 3, out = 1), t[Cell(1, 1)])
    }
}

class BrokenLayoutTest {
    @Test fun `over-capacity loop is flagged`() {
        val g = Net().run { val s = add(Kind.SRC); val m = add(Kind.MERGE); val a = add(Kind.SPLIT); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1)
            link(s, m); link(m, a); link(a, A); link(a, B); link(a, m); g }
        val v = check(g, levelNamed("Full belt"))
        assertTrue(!v.solved && v.reasons.any { "more than a full belt" in it }, v.reasons.toString())
    }

    @Test fun `closed loop is flagged`() {
        val g = Net().run { val s = add(Kind.SRC); val m = add(Kind.MERGE); val a = add(Kind.SPLIT); add(Kind.OUT, 0); add(Kind.OUT, 1)
            link(s, m); link(m, a); link(a, m); link(a, m); g }
        val sol = solve(g, HALF)
        assertTrue(!sol.ok && "no way out" in sol.why, sol.why)
    }

    private fun deadEnd(): Pair<Graph, Int> = Net(3).run {
        val s = add(Kind.SRC); val a = add(Kind.SPLIT); val A = add(Kind.OUT, 0); val stuck = add(Kind.MERGE)
        link(s, a); link(a, A); link(a, stuck)   // the converger has no output, so its line backs up
        g to A
    }

    @Test fun `a jammed output is skipped and the open one carries everything`() {
        val (g, iA) = deadEnd()
        val s = Sim(g, 2)
        repeat(400) { s.step() }
        val w0 = s.snap()
        repeat(400) { s.step() }
        val share = (s.n[iA].sunk - w0.sunk[iA]).toDouble() / (s.emitted - w0.emitted)
        assertTrue(abs(share - 1) < 0.01 && s.skips > 0, "share $share skips ${s.skips}")
    }

    @Test fun `a dead end doesn't hold the input up once it counts as jammed`() {
        val (g, _) = deadEnd()
        val s = Sim(g, 2)
        repeat(400) { s.step() }
        val w0 = s.snap()
        repeat(2000) { s.step() }
        val thr = (s.emitted - w0.emitted) / 1000.0
        assertTrue(thr > 0.95, "throughput $thr")
    }
}

class DirectionTest {
    @Test fun `drawing out of a converger and into a splitter sets their sides`() {
        val t = tilesOf(Cell(0, 0) to Tile(Kind.MERGE, out = 0), Cell(0, 4) to Tile(Kind.SPLIT, inSide = 2))
        commitPath(t, listOf(Cell(0, 0), Cell(0, -1), Cell(0, -2)))
        assertEquals(3, t.getValue(Cell(0, 0)).out)
        commitPath(t, listOf(Cell(2, 4), Cell(1, 4), Cell(0, 4)))
        assertEquals(0, t.getValue(Cell(0, 4)).inSide)
    }

    @Test fun `before there is a machine, an input faces the start cell from the side it's dropped on`() {
        val t = tilesOf()
        assertEquals(3, entrySide(t, Cell(0, -2)), "dropped above: it enters from the top")
        assertEquals(Cell(0, -1), edgeSpot(t, Cell(0, -2), 3))
        t[Cell(0, -1)] = Tile(Kind.SRC, out = 1)
        settleInputs(t)
        assertEquals(Kind.SRC, t[Cell(0, -1)]?.kind)
        placePiece(t, START, Kind.SPLIT)
        assertEquals(3, t.getValue(START).inSide, "a splitter on the start cell takes the input's marbles")
    }

    @Test fun `an input dropped beside the machine enters from that side`() {
        val t = tiles("SL5")
        assertEquals(2, entrySide(t, Cell(-3, 0)))
        assertEquals(3, entrySide(t, Cell(0, -2)))
        assertEquals(0, entrySide(t, Cell(4, 1)))
    }
}

class GroupTest {
    private val thirds get() = tiles(textRefs.getValue("Thirds by halves"))

    @Test fun `a build moved as a whole group still solves`() {
        val t = thirds
        assertNotNull(moveGroup(t, t.keys.toList(), 5, -3))
        val g = buildGraph(t, levelNamed("Thirds by halves"))
        assertEquals(0, g.errCount); assertTrue(check(g, levelNamed("Thirds by halves")).solved)
    }

    @Test fun `move onto another piece is refused and changes nothing`() {
        val t = thirds
        val c = t.keys.first { t.getValue(it).kind == Kind.SPLIT }
        t[Cell(100, 100)] = Tile(Kind.SPLIT, inSide = 2)
        val before = t.copyTiles()
        assertNull(moveGroup(t, listOf(c), 100 - c.x, 100 - c.y))
        assertEquals(before, t)
        assertTrue(moveBlockedAt(t, listOf(c), Cell(100, 100)))
    }

    @Test fun `a piece moved onto a belt replaces it`() {
        val t = tiles("IR LR LR SL5")
        val splitter = t.getValue(Cell(3, 0))
        assertFalse(moveBlockedAt(t, listOf(Cell(3, 0)), Cell(1, 0)))
        assertEquals(listOf(Cell(1, 0)), moveGroup(t, listOf(Cell(3, 0)), -2, 0))
        assertEquals(splitter, t.getValue(Cell(1, 0)))
        assertNull(t[Cell(3, 0)])
    }

    @Test fun `rotating a build keeps it solved and four turns restore it`() {
        val t0 = tiles(textRefs.getValue("Fifth"))
        val t = t0.copyTiles()
        var cells = rotateGroup(t, t.keys.toList())!!
        val g = buildGraph(t, levelNamed("Fifth"))
        assertEquals(0, g.errCount); assertTrue(check(g, levelNamed("Fifth")).solved)
        repeat(3) { cells = rotateGroup(t, cells)!! }
        assertEquals(t0, t)
    }

    @Test fun `rotation blocked by an outside tile is refused and changes nothing`() {
        val t0 = tiles(".. IB\n.. TB\n.. ST5")
        val t = t0.copyTiles().apply { this[Cell(0, 1)] = Tile(Kind.BELT, from = 2, out = 0) }
        val before = t.copyTiles()
        assertNull(rotateGroup(t, t0.keys.toList()))
        assertEquals(before, t)
    }
}

class TextCodeTest {
    @TestFactory
    fun `reference solutions round-trip through text`(): List<DynamicTest> = textRefs.map { (name, code) ->
        DynamicTest.dynamicTest(name) {
            val t = tiles(code)
            assertEquals(code, encodeGrid(t))
            assertEquals(code, encodeGrid(tiles(encodeLine(t))))
        }
    }

    @Test fun `old codes read their outputs and bypasses as empty`() {
        val t = tiles("IR SL FL\n.. FT2 ..\n.. OT ..")
        assertTrue(t.values.none { it.kind == Kind.OUT })
        assertEquals(2, t.size)
    }

    @Test fun `bad text is reported with its row and cell`() {
        assertTrue("isn't a cell code" in decodeGrid("IR XX").error!!)
        assertTrue("same side" in decodeGrid("RR").error!!)
        assertTrue("same number" in decodeGrid("IR2 IL2").error!!)
        assertTrue("Row 2, cell 3" in decodeGrid("IR,SL/..,..,ZZ").error!!)
        assertTrue("isn't a cell code" in decodeGrid("IR5").error!!)
    }

    @Test fun `export is single-spaced and the one-line code builds the same layout`() {
        val t = tiles("..  ..  IB\nRL  CL  ST\n..  RT  TL")
        assertEquals(".. .. IB\nRL CL7 ST7\n.. RT TL", encodeGrid(t))
        assertEquals("..,..,IB/RL,CL7,ST7/..,RT,TL", encodeLine(t))
    }
}

class FairnessTest {
    private val twoThirds = ".. .. IB\nRL CL ST\n.. RT TL"   // A (left) gets the converger's 2/3, B (right) the splitter's 1/3

    @Test fun `a splitter feeding a converger directly gets its full share`() {
        val t = tiles(twoThirds)
        for (rate in listOf(2, 1)) {
            val m = measure(t, rate)
            assertTrue(abs(m.share(0) - 2.0 / 3) < 0.01 && abs(m.share(1) - 1.0 / 3) < 0.01 && m.skips == 0,
                "rate $rate: left ${m.share(0)} right ${m.share(1)} skips ${m.skips}")
        }
        val level = Level("2/3", 2, listOf(Frac(1, 3), Frac(2, 3)))
        assertTrue(check(buildGraph(t, level), level).solved)
    }

    @Test fun `a splitter straight into a converger keeps full speed`() {
        val m = measure(tiles("IR SL6 CR"), 1)
        assertTrue(m.throughput > 0.99, "throughput ${m.throughput}")
    }
}

class RandomLevelTest {
    /** Joins [lines] into one with converger trees (three inputs each). */
    private fun Net.join(lines: List<Int>): Int {
        var ls = lines
        while (ls.size > 1) ls = ls.chunked(3).map { c -> if (c.size == 1) c[0] else add(Kind.MERGE).also { m -> c.forEach { link(it, m) } } }
        return ls[0]
    }

    /** The construction from [RANDOM_TARGETS]' doc: N = 2^a·3^b ≥ q shares, N − q sent back, p to A, the rest out one side. */
    private fun solution(f: Frac): Graph = Net().run {
        val q = f.d
        val ways = mutableListOf<Int>()
        var best = Int.MAX_VALUE; var bestA = 0; var bestB = 0
        var p3 = 1; var b = 0
        while (p3 < 4 * q) { var p2 = 1; var a = 0; while (p2 * p3 < 4 * q) { if (p2 * p3 >= q && p2 * p3 < best) { best = p2 * p3; bestA = a; bestB = b }; p2 *= 2; a++ }; p3 *= 3; b++ }
        repeat(bestB) { ways += 3 }; repeat(bestA) { ways += 2 }
        val n = best
        val s = add(Kind.SRC)
        val m = if (n > q) add(Kind.MERGE) else -1
        val top = if (m >= 0) { link(s, m); m } else s
        var leaves = listOf(top)
        for (w in ways) leaves = leaves.flatMap { from -> val sp = add(Kind.SPLIT); link(from, sp); List(w) { sp } }
        val shares = leaves.map { sp -> add(Kind.BELT).also { link(sp, it, 0) } }
        val back = shares.take(n - q); val toA = shares.drop(n - q).take(f.n); val rest = shares.drop(n - q + f.n)
        if (back.isNotEmpty()) link(join(back), m)
        link(join(toA), add(Kind.OUT, 0))
        if (rest.isNotEmpty()) link(join(rest), add(Kind.OUT))
        check(n == leaves.size)
        g
    }

    @TestFactory
    fun `every random target has a solution that passes Check with no jams`(): List<DynamicTest> = RANDOM_TARGETS.map { f ->
        DynamicTest.dynamicTest("$f") {
            val v = check(solution(f), Level("random", 2, listOf(f)))
            assertTrue(v.solved, fmt(v)); assertEquals(0, v.skips, fmt(v))
        }
    }

    @Test fun `targets are spread over denominators and never repeat the last`() {
        val rnd = kotlin.random.Random(1)
        var last = randomTarget(rnd)
        val ds = mutableSetOf<Int>()
        repeat(500) { val f = randomTarget(rnd, last); assertTrue(f != last); assertTrue(f in RANDOM_TARGETS); ds += f.d; last = f }
        assertEquals((3..RANDOM_MAX_D).toSet(), ds)
    }
}

class WaitTest {
    @Test fun `a clean split never waits`() {
        val s = Sim(buildGraph(tiles(HALVES), HALF), 2)
        repeat(500) { s.step() }
        assertEquals(0, s.waits.sum()); assertEquals(0, s.turnAways.sum())
    }

    @Test fun `a loop into a full-belt input holds marbles up at the converger`() {
        val g = buildGraph(tiles("IR CR SL6 SL6\n.. RT TL .."), levelNamed("Full belt"))
        val s = Sim(g, 1)
        repeat(500) { s.step() }
        val merge = g.idx.getValue(Cell(1, 0))
        assertTrue(s.turnAways[merge] > 0)
        assertTrue(s.blocked > 0)
        assertEquals(s.blocked, s.waits[g.nodes.indexOfFirst { it.kind == Kind.SRC }], "the input waits whenever it's held up")
    }
}

class MultiInputTest {
    @Test fun `two inputs solve as rates and each is measured`() {
        val level = levelNamed("Two streams")
        val g = buildGraph(tiles(textRefs.getValue("Two streams")), level)
        val s = Sim(g, level)
        repeat(1200) { s.step() }
        assertEquals(1200, s.emittedBy[0])
        assertTrue(s.emittedBy[1] in 599..600, "the slower input can be a marble behind at a converger")
    }

    @Test fun `a missing input is named, and inputs past the level's count don't fit`() {
        val level = levelNamed("Two streams")
        val g = buildGraph(tiles(HALVES), level)
        assertTrue("Place input 2." in judge(g, level, solve(g, level)).reasons)
        assertEquals("This level has 1 input; the text uses 2.", fitsLevel(tiles(".. IB2 ..\nIR X LR\n.. TB .."), HALF))
    }

    @Test fun `a rate of two thirds sends two marbles every three ticks`() {
        val g = buildGraph(tiles("IR LR"), Level("r", listOf(Frac(2, 3)), listOf(null)))
        val s = Sim(g, listOf(Frac(2, 3)))
        repeat(300) { s.step() }
        assertEquals(200, s.emittedBy[0])
    }
}

class MetricsTest {
    @Test fun `pieces and the footprint count every cell but the inputs`() {
        val x = metricsOf(tiles("IR LR SL5"))
        assertEquals(2, x.pieces); assertEquals(1, x.belts); assertEquals(1, x.splits)
        assertEquals(2, x.area); assertEquals(2, x.w); assertEquals(1, x.h)
        assertEquals(1, metricsOf(tiles("IR SL5")).area)
    }
}

class CycleTest {
    private fun cycleOf(level: Level, text: String) = findCycle(buildGraph(tiles(text), level), level)

    @Test fun `halves repeats every four ticks`() {
        // a marble every other tick, the splitter alternating: two marbles, four ticks, then all again
        assertEquals(4, cycleOf(HALF, HALVES)!!.length)
    }

    @Test fun `every level's reference solution settles into a loop`() {
        for ((name, text) in textRefs) {
            val c = cycleOf(levelNamed(name), text)
            assertNotNull(c, name)
            assertTrue(c!!.length in 1..MAX_CYCLE_TICKS, "$name: $c")
        }
    }

    @Test fun `steps count from the start of the loop`() {
        val c = Cycle(start = 10, length = 4)
        assertNull(c.step(9)); assertEquals(1, c.step(10)); assertEquals(4, c.step(13)); assertEquals(1, c.step(14))
    }

    @Test fun `the loop's output counts are exact`() {
        val level = levelNamed("One two four")
        val g = buildGraph(tiles(textRefs.getValue("One two four")), level)
        val c = findCycle(g, level)!!
        val s = Sim(g, level)
        repeat(c.start) { s.step() }
        val before = s.snap()
        repeat(c.length) { s.step() }
        val after = s.snap()
        val outs = assignOutputs(g, level, solve(g, level))
        val rates = outs.map { Q.of((after.sunk[it] - before.sunk[it]).toLong(), c.length.toLong()) }
        assertEquals(level.out.map { it!!.q }, rates)
    }

    @Test fun `a build gives the same result however its pieces were placed`() {
        val level = levelNamed("Full belt")
        val t = tiles(textRefs.getValue("Full belt"))
        val first = check(buildGraph(t, level), level).reasons
        val rnd = kotlin.random.Random(5)
        repeat(20) {
            val shuffled = LinkedHashMap<Cell, Tile>().apply { t.entries.shuffled(rnd).forEach { (c, x) -> put(c, x) } }
            assertEquals(first, check(buildGraph(shuffled, level), level).reasons)
        }
    }

    @Test fun `flow is 100 when no marble waits`() {
        val level = HALF
        val g = buildGraph(tiles(HALVES), level)
        assertEquals(100, flowOf(g, level, findCycle(g, level)!!).score)
    }

    @Test fun `flow drops with how much marbles wait`() {
        // two full belts into one converger: every other tick one of them waits for its turn
        val level = Level("jam", listOf(Frac(1, 1), Frac(1, 1)), listOf(Frac(1, 1)), false)
        val g = buildGraph(tiles("IR CB IL2"), level)
        val f = flowOf(g, level, findCycle(g, level)!!)
        assertTrue(f.waiting > 0); assertTrue(f.score in 1..99, "${f.score}")
    }
}

class MachineTest {
    /** The even split as a machine: in from the left, out of the top and bottom. */
    private val halves = tiles("IR LR SL5")
    private val h = machineOf(halves).first
    private val lib = MachineLib(mapOf(h to MachineDef(machineOf(halves).second, level = "halves")))
    private val half = Level("split", 2, listOf(Frac(1, 2), Frac(1, 2)))

    @Test fun `a machine is named by its build and placed as one piece`() {
        assertEquals(6, h.length)
        assertEquals(h, machineOf(tiles("IR .. LR SL5")).first, "the same build, however it was typed")
        val t = tiles("IR LR #$h.0")
        val g = buildGraph(t, half, lib)
        assertEquals(0, g.errCount, g.nodes.flatMap { it.errs }.toString())
        val v = check(g, half)
        assertTrue(v.solved, fmt(v))
        assertEquals(2, g.exits.size, "its ways out are the parent's")
        val m = metricsOf(t, lib)
        assertEquals(3, m.pieces, "a belt and the machine's two pieces"); assertEquals(1, m.machines)
        assertEquals(2, m.area, "a machine is one cell on the board")
    }

    @Test fun `a machine turns with its sides`() {
        val g = buildGraph(tiles("IR LR #$h.1"), half, lib)   // its input now on top
        assertTrue(g.nodes.any { n -> n.errs.any { "no input" in it } }, g.nodes.flatMap { it.errs }.toString())
        val ok = buildGraph(tiles(".. IB\n.. TB\n.. #$h.1"), half, lib)
        assertEquals(0, ok.errCount, ok.nodes.flatMap { it.errs }.toString())
        assertEquals(setOf(0, 2), ok.ports.filter { !it.isInput }.map { it.side }.toSet(), "out to the left and right")
    }

    @Test fun `a level can't be solved with its own machine, and unknown machines are named`() {
        val own = Level("halves", 2, listOf(Frac(1, 2), Frac(1, 2)))
        assertTrue(buildGraph(tiles("IR LR #$h.0"), own, lib).nodes.any { n -> n.errs.any { "its own machine" in it } })
        assertTrue(buildGraph(tiles("IR LR #abcdef.0"), half, lib).nodes.any { n -> n.errs.any { "#abcdef isn't one of yours" in it } })
    }

    @Test fun `a lone machine is one piece and doesn't pass`() {
        val v = check(buildGraph(tiles("IR #$h.0"), half, lib), half)
        assertTrue("A machine needs at least two pieces." in v.reasons, v.reasons.toString())
    }

    @Test fun `a machine's ports must line up across its middle`() {
        assertTrue(portsAligned(halves))
        val skew = tiles(".. .. IB ..\nRL BL TB ..\n.. CT3 ST6 ..\n.. RT ST6 ..\n.. .. TR LR")   // left and right ways out on different rows
        assertFalse(portsAligned(skew))
        val (hs, code) = machineOf(skew)
        val l2 = lib.plus(mapOf(hs to MachineDef(code)))
        assertTrue(buildGraph(tiles(".. IB\n.. TB\n.. #$hs.0"), half, l2).nodes.any { n -> n.errs.any { "don't line up" in it } })
        val straight = tiles("IR SL6 LR CR3\n.. ST3 LR LT\n.. TB .. ..")   // in left, out right on the same row, out below
        assertTrue(portsAligned(straight))
    }

    @Test fun `machines nest`() {
        val outer = tiles("IR LR #$h.0")
        val (h2, code2) = machineOf(outer)
        val lib2 = lib.plus(mapOf(h2 to MachineDef(code2, "split")))
        val t = tiles("IR LR #$h2.0")
        val g = buildGraph(t, Level("other", 2, listOf(Frac(1, 2), Frac(1, 2))), lib2)
        assertEquals(0, g.errCount, g.nodes.flatMap { it.errs }.toString())
        assertTrue(check(g, Level("other", 2, listOf(Frac(1, 2), Frac(1, 2)))).solved)
        assertEquals(4, metricsOf(t, lib2).pieces)
        assertEquals(setOf(h2, h), machinesUsed(t, lib2))
    }

    @Test fun `a shared code carries its machines`() {
        val t = tiles("IR LR #$h.0")
        val text = encodeShare(t, lib)
        assertEquals("IR LR #$h.0\n#$h = ${lib[h]!!.code}", text)
        val d = decodeGrid(text)
        assertNull(d.error)
        assertEquals(mapOf(h to lib[h]!!.code), d.machines)
        assertEquals(t, d.tiles)
        val line = decodeGrid(encodeShareLine(t, lib))
        assertEquals(t, line.tiles); assertEquals(d.machines, line.machines)
        assertTrue("doesn't match" in decodeGrid("IR LR #$h.0\n#$h = IR,LR,SL7").error!!)
    }
}
