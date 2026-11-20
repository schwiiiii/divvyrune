package com.schwii.divvywood.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs

class LevelsTest {
    @TestFactory
    fun `every level has a reference solution that passes Check with no jams`(): List<DynamicTest> =
        LEVELS.withIndex().filter { !it.value.sandbox }.flatMap { (li, level) ->
            val tests = mutableListOf<DynamicTest>()
            val name = level.name
            for ((label, tiles) in listOfNotNull(
                gridRefs[name]?.let { "grid" to it }, compact[name]?.let { "compact" to it },
                textRefs[name]?.let { "text" to decodeGrid(it).tiles!! },
            )) {
                tests += DynamicTest.dynamicTest("L${li + 1} ${level.name} ($label)") {
                    val g = buildGraph(tiles, level)
                    assertEquals(0, g.errCount, g.nodes.filter { it.errs.isNotEmpty() }.joinToString { "${it.cell}: ${it.errs}" })
                    val v = check(g, level)
                    assertTrue(v.solved, fmt(v)); assertEquals(0, v.skips, fmt(v))
                }
            }
            netRefs[name]?.let { make ->
                tests += DynamicTest.dynamicTest("L${li + 1} ${level.name} (network)") {
                    val v = check(make(), level)
                    assertTrue(v.solved, fmt(v)); assertEquals(0, v.skips, fmt(v))
                }
            }
            if (tests.isEmpty()) tests += DynamicTest.dynamicTest("L${li + 1} has a reference") { throw AssertionError("no reference solution") }
            tests
        }
}

class BrokenLayoutTest {
    @Test fun `dangling belt is flagged`() {
        val t = grid {
            put(0, 0, Kind.SRC); put(2, 0, Kind.SPLIT); put(2, -2, Kind.OUT, 0); put(2, 2, Kind.OUT, 1)
            path(0 to 0, 2 to 0); path(2 to 0, 2 to -2); path(2 to 0, 3 to 0)
        }
        val g = buildGraph(t, levelNamed("Halves"))
        val v = judge(g, levelNamed("Halves"), solve(g))
        assertTrue(!v.solved && v.reasons.any { "fixing" in it }, v.reasons.toString())
    }

    @Test fun `over-capacity loop is flagged`() {
        val g = Net().run { val s = add(Kind.SRC); val m = add(Kind.MERGE); val a = add(Kind.SPLIT); val A = add(Kind.OUT, 0); val B = add(Kind.OUT, 1)
            link(s, m); link(m, a); link(a, A); link(a, B); link(a, m); g }
        val v = check(g, levelNamed("Full belt"))
        assertTrue(!v.solved && v.reasons.any { "more than one item" in it }, v.reasons.toString())
    }

    @Test fun `closed loop is flagged`() {
        val g = Net().run { val s = add(Kind.SRC); val m = add(Kind.MERGE); val a = add(Kind.SPLIT); add(Kind.OUT, 0); add(Kind.OUT, 1)
            link(s, m); link(m, a); link(a, m); link(a, m); g }
        val sol = solve(g)
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
    @Test fun `the input's direction decides what it feeds`() {
        val t = compact.getValue("Halves").copyTiles()
        t[Cell(0, 1)] = Tile(Kind.SRC, out = 3)
        val g = buildGraph(t, levelNamed("Halves"))
        assertTrue(g.nodes.first { it.kind == Kind.SRC }.errs.any { "faces nothing" in it })
        t[Cell(0, 1)] = Tile(Kind.SRC, out = 0)
        val g2 = buildGraph(t, levelNamed("Halves"))
        assertEquals(0, g2.errCount); assertTrue(check(g2, levelNamed("Halves")).solved)
    }

    private fun eighths(f: String) = "..  BR  CB  RL  BL\n..  BT  $f IB  BT\nRB  SR  SR  ST  LT\nTB  RB  ST  ..  ..\nTR  CB  TL  ..  ..\n..  FT  ..  ..  .."

    @Test fun `outputs take items from one side only`() {
        val g = buildGraph(decodeGrid(eighths("FT2")).tiles!!, levelNamed("Eighths"))
        val v = check(g, levelNamed("Eighths"))
        val b = g.nodes.first { it.kind == Kind.OUT && it.id == 1 }
        assertTrue(v.solved, fmt(v))
        assertEquals(listOf(Kind.MERGE), b.pred.map { g.nodes[it].kind })
        val down = buildGraph(decodeGrid(eighths("FB2")).tiles!!, levelNamed("Eighths"))
        assertEquals(1, down.errCount)
        assertTrue(down.nodes.any { n -> n.errs.any { "closed side of an output" in it } })
    }

    @Test fun `a splitter only feeds outputs and bypasses facing it`() {
        val t = tilesOf(Cell(0, 0) to Tile(Kind.SRC, out = 0), Cell(1, 0) to Tile(Kind.SPLIT, inSide = 2), Cell(2, 0) to Tile(Kind.OUT, inSide = 2, id = 0),
            Cell(1, 1) to Tile(Kind.OUT, inSide = 0, id = 1), Cell(1, -1) to Tile(Kind.DUMP, inSide = 1, id = 0))
        val g = buildGraph(t, Level("t", 2, listOf(null, null), 1, ""))
        assertEquals(2, g.nodes.first { it.kind == Kind.SPLIT }.succ.size)
        assertEquals(0, g.nodes[g.idx.getValue(Cell(1, 1))].pred.size)
    }

    @Test fun `saved outputs from older versions face what feeds them`() {
        val t = tilesOf(Cell(0, 0) to Tile(Kind.SRC, out = 0), Cell(1, 0) to Tile(Kind.OUT, id = 0))
        orientMissing(t)
        assertEquals(2, t.getValue(Cell(1, 0)).inSide)
    }

    @Test fun `drawing a belt into an output turns it to face the belt`() {
        val t = tilesOf(Cell(0, 0) to Tile(Kind.OUT, id = 0, inSide = 2))
        commitPath(t, listOf(Cell(0, 3), Cell(0, 2), Cell(0, 1), Cell(0, 0)))
        assertEquals(1, t.getValue(Cell(0, 0)).inSide)
    }

    @Test fun `drawing out of a converger and into a splitter sets their sides`() {
        val t = tilesOf(Cell(0, 0) to Tile(Kind.MERGE, out = 0), Cell(0, 4) to Tile(Kind.SPLIT, inSide = 2))
        commitPath(t, listOf(Cell(0, 0), Cell(0, -1), Cell(0, -2)))
        assertEquals(3, t.getValue(Cell(0, 0)).out)
        commitPath(t, listOf(Cell(2, 4), Cell(1, 4), Cell(0, 4)))
        assertEquals(0, t.getValue(Cell(0, 4)).inSide)
    }
}

class GroupTest {
    @Test fun `level 7 moved as a whole group still solves`() {
        val t = gridRefs.getValue("Thirds by halves").copyTiles()
        assertNotNull(moveGroup(t, t.keys.toList(), 5, -3))
        val g = buildGraph(t, levelNamed("Thirds by halves"))
        assertEquals(0, g.errCount); assertTrue(check(g, levelNamed("Thirds by halves")).solved)
    }

    @Test fun `move onto an occupied square is refused and changes nothing`() {
        val t = gridRefs.getValue("Thirds by halves").copyTiles()
        t[Cell(100, 100)] = Tile(Kind.BELT, from = 2, out = 0)
        val before = t.copyTiles()
        assertNull(moveGroup(t, listOf(Cell(0, 0)), 100, 100))
        assertEquals(before, t)
    }

    @Test fun `rotating level 1 keeps it solved and four turns restore it`() {
        val t0 = gridRefs.getValue("Halves")
        val t = t0.copyTiles()
        var cells = rotateGroup(t, t.keys.toList())!!
        val g = buildGraph(t, levelNamed("Halves"))
        assertEquals(0, g.errCount); assertTrue(check(g, levelNamed("Halves")).solved)
        repeat(3) { cells = rotateGroup(t, cells)!! }
        assertEquals(t0, t)
    }

    @Test fun `rotation blocked by an outside tile is refused and changes nothing`() {
        val t0 = gridRefs.getValue("Halves")
        val t = t0.copyTiles().apply { this[Cell(3, 1)] = Tile(Kind.BELT, from = 2, out = 0) }
        val before = t.copyTiles()
        assertNull(rotateGroup(t, t0.keys.toList()))
        assertEquals(before, t)
    }
}

class TextCodeTest {
    @Test fun `the whiteboard 1-30 decodes, solves, and encodes back to the same text`() {
        val d = decodeGrid(SKETCH_1_30)
        assertNull(d.error)
        val level = Level("1/30", 2, listOf(Frac(1, 30)), 3, "")
        val g = buildGraph(d.tiles!!, level)
        assertEquals(0, g.errCount)
        val v = check(g, level)
        assertTrue(v.solved, fmt(v)); assertEquals(0, v.skips)
        assertEquals(SKETCH_1_30, encodeGrid(d.tiles!!))
        assertTrue(fitsLevel(d.tiles!!, levelNamed("Seventeenth"))!!.contains("3"))
    }

    @TestFactory
    fun `reference layouts round-trip through text`(): List<DynamicTest> = listOf(
        Triple("Halves grid", gridRefs.getValue("Halves"), "Halves"), Triple("Thirds by halves grid", gridRefs.getValue("Thirds by halves"), "Thirds by halves"),
        Triple("Fifth grid", gridRefs.getValue("Fifth"), "Fifth"),
        Triple("Halves compact", compact.getValue("Halves"), "Halves"), Triple("Quarter back compact", compact.getValue("Quarter back"), "Quarter back"),
    ).map { (name, t, ln) ->
        DynamicTest.dynamicTest(name) {
            val text = encodeGrid(t)
            val d = decodeGrid(text)
            assertNull(d.error)
            assertEquals(text, encodeGrid(d.tiles!!))
            assertTrue(check(buildGraph(d.tiles!!, levelNamed(ln)), levelNamed(ln)).solved)
        }
    }

    @Test fun `numbered and unnumbered F cells get distinct outputs`() {
        val d = decodeGrid("IR SL FL2\n.. FT ..")
        assertEquals(listOf(0, 1), d.tiles!!.values.filter { it.kind == Kind.OUT }.map { it.id }.sorted())
    }

    @Test fun `bad text is reported with its row and cell`() {
        assertTrue("isn't a cell code" in decodeGrid("IR XX").error!!)
        assertTrue("same side" in decodeGrid("RR").error!!)
        assertTrue("already a main input" in decodeGrid("IR IL").error!!)
        assertTrue("Row 2, cell 3" in decodeGrid("IR,SL/..,..,ZZ").error!!)
    }

    @Test fun `export is single-spaced and the one-line code builds the same layout`() {
        val t = decodeGrid("..  ..  IB  ..\nFR2 CL  ST  FL\n..  RT  TL  ..").tiles!!
        assertEquals(".. .. IB ..\nFR2 CL ST FL\n.. RT TL ..", encodeGrid(t))
        assertEquals("..,..,IB,../FR2,CL,ST,FL/..,RT,TL,..", encodeLine(t))
        assertEquals(encodeGrid(t), encodeGrid(decodeGrid(encodeLine(t)).tiles!!))
    }
}

class FairnessTest {
    private val twoThirds = "..  ..  IB  ..\nFR2 CL  ST  FL\n..  RT  TL  .."

    @Test fun `a splitter feeding a converger directly gets its full share`() {
        val t = decodeGrid(twoThirds).tiles!!
        for (rate in listOf(2, 1)) {
            val m = measure(t, rate)
            assertTrue(abs(m.share(1) - 2.0 / 3) < 0.01 && abs(m.share(0) - 1.0 / 3) < 0.01 && m.skips == 0,
                "rate $rate: B ${m.share(1)} A ${m.share(0)} skips ${m.skips}")
        }
        val level = Level("2/3", 2, listOf(Frac(1, 3), Frac(2, 3)), 0, "")
        assertTrue(check(buildGraph(t, level), level).solved)
    }

    @Test fun `a splitter straight into a converger keeps full speed`() {
        val m = measure(decodeGrid("IR SL CR FL\n.. FT .. ..").tiles!!, 1)
        assertTrue(m.throughput > 0.99, "throughput ${m.throughput}")
    }

    @Test fun `the 1-30 build measures one thirtieth with no skips`() {
        val m = measure(decodeGrid(SKETCH_1_30).tiles!!, 2)
        assertTrue(abs(m.share(0) - 1.0 / 30) < 0.005 && m.skips == 0, "share ${m.share(0)} skips ${m.skips}")
    }
}
