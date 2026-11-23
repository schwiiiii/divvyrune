package com.schwii.divvywood.game

import com.schwii.divvywood.board.TrayItem
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.Frac
import com.schwii.divvywood.engine.Q
import com.schwii.divvywood.engine.toFrac
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.RANDOM_TARGETS
import com.schwii.divvywood.engine.levelNamed
import com.schwii.divvywood.engine.encodeGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Levels, Check, saves and Build as text, driven without a screen. */
class GameTest {
    private val halves = "IR SL FL\n.. FT2 .."

    private fun check(g: Game): Sheet.Result {
        g.startCheck()
        g.board.finishCheck()
        return g.sheet as Sheet.Result
    }

    @Test fun `a solved Check records the best score and offers the next level`() {
        val store = MemoryStore()
        val g = Game(Saves(store))
        assertNull(g.buildFromText(halves))
        val r = check(g)
        assertTrue(r.verdict.reasons.toString(), r.verdict.solved)
        assertEquals("rates: half of a half-belt input each", listOf("1/4", "1/4"), r.verdict.rows.map { it.exact.toString() })
        assertEquals(Best(pieces = 1, area = 1, io = 93, flow = 100), r.best)
        assertTrue(r.next)
        assertEquals(Best(1, 1, 93, 100), Saves(store).best("Halves"))

        g.loadLevel(1)
        assertEquals(1, g.levelIndex)
        assertNull(g.sheet)
        assertTrue(g.board.tiles.isEmpty())
    }

    @Test fun `best keeps each metric's best, even from different builds`() {
        val g = Game(Saves(MemoryStore()))
        g.buildFromText(halves); check(g)                         // 1 piece, 1×1, I/O 93
        g.buildFromText("IR SL FL\n.. TR FL2"); val r = check(g)  // a belt more, 2×2, both outputs side by side facing away: I/O 100
        assertEquals(Best(pieces = 1, area = 1, io = 100, flow = 100), r.best)
    }

    @Test fun `a build with no input is judged at once without running`() {
        val g = Game(Saves(MemoryStore()))
        g.startCheck()
        assertFalse(g.board.checking)
        val r = g.sheet as Sheet.Result
        assertFalse(r.verdict.solved)
        assertEquals(listOf("Place the input.", "Place output A.", "Place output B."), r.verdict.reasons)
        assertNull(r.best)
        assertFalse(r.next)
    }

    @Test fun `a wrong split runs and says what each output gets`() {
        val g = Game(Saves(MemoryStore()))
        g.loadLevel(1)   // 1/4 and 3/4
        g.buildFromText(halves)
        val r = check(g)
        assertFalse(r.verdict.solved)
        assertEquals("Output A gets 1/4 of a belt. It needs 1/8.", r.verdict.reasons.first())
        assertEquals(0.25, r.verdict.rows[0].measured, 0.005)
    }

    @Test fun `builds and the current level survive a restart`() {
        val store = MemoryStore()
        Game(Saves(store)).apply { loadLevel(4); buildFromText(halves) }
        val g = Game(Saves(store))
        assertEquals(4, g.levelIndex)
        assertEquals(halves, encodeGrid(g.board.tiles))
        assertFalse(g.editor.canUndo)
    }

    @Test fun `every edit is saved`() {
        val store = MemoryStore()
        val g = Game(Saves(store))
        g.board.apply { ox = 0f; oy = 0f; z = 10f }
        assertTrue(g.editor.chipDrop(TrayItem(Kind.SRC), 5f, 5f))
        assertEquals("IR", Saves(store).data.levels["Halves"]?.build)
        g.editor.undo()
        assertEquals("", Saves(store).data.levels["Halves"]?.build)
    }

    @Test fun `the build is locked while a Check runs`() {
        val g = Game(Saves(MemoryStore()))
        g.buildFromText(halves)
        g.board.apply { ox = 0f; oy = 0f; z = 10f }
        g.startCheck()
        assertTrue(g.board.checking)
        assertFalse(g.editor.chipDrop(TrayItem(Kind.SPLIT), 5f, 25f))
        g.editor.undo()
        assertEquals(halves, encodeGrid(g.board.tiles))
        g.showLevels()
        assertNull(g.sheet)
        g.board.finishCheck()
        assertNotNull(g.sheet)
    }

    @Test fun `build from text reports bad codes and pieces the level lacks`() {
        val g = Game(Saves(MemoryStore()))
        assertEquals("Row 1, cell 2: \"XX\" isn't a cell code.", g.buildFromText("IR XX"))
        g.loadLevel(LEVELS.indexOf(levelNamed("Seventh")))   // one output, one bypass
        assertEquals("This level has 1 final output; the text uses 2.", g.buildFromText(halves))
        assertTrue(g.board.tiles.isEmpty())
        assertNull("bypasses are unlimited", g.buildFromText("IR SL FL\n.. OT .."))
        assertEquals("Built from text", g.editor.notice)
        g.editor.undo()
        assertTrue(g.board.tiles.isEmpty())
    }

    @Test fun `saves keyed by level index move to level names`() {
        // as 0.1.x wrote them: Fifth (index 7) open, Eighths (index 4) solved
        val v1 = """{"cur":7,"levels":{"4":{"build":"IR SL","best":{"parts":5,"small":9,"large":12}},"7":{"build":"IR"}}}"""
        val store = MemoryStore(v1)
        val g = Game(Saves(store))
        assertEquals("Fifth", g.level.name)
        assertEquals("IR", encodeGrid(g.board.tiles))
        assertEquals("an old best gets metrics from the build", Best(1, 1, 70), g.best(LEVELS.indexOf(levelNamed("Eighths"))))
        g.editor.undo()   // any write stores the new form
        g.loadLevel(0)
        assertEquals(setOf("Eighths", "Fifth"), Saves(store).data.levels.keys)
        assertEquals("Halves", Saves(store).cur)
    }

    @Test fun `a Check records the score, and a solve records the solving build`() {
        val store = MemoryStore()
        val g = Game(Saves(store))
        g.loadLevel(1)   // 1/4 and 3/4
        g.buildFromText(halves); check(g)
        assertEquals(Checked(1, 1, 93, solved = false, flow = 100), g.saved(1).checked)
        assertNull(g.saved(1).solution)
        g.loadLevel(0)
        g.buildFromText(halves); check(g)
        assertEquals(halves, g.saved(0).solution)
        g.buildFromText("IR LR SL FL\n.. .. FT2 .."); g.editor.dismissNotice()
        assertEquals("the solution stays until another solve", halves, Saves(store).level("Halves").solution)
        assertEquals(Checked(1, 1, 93, solved = true, flow = 100), Saves(store).level("Halves").checked)
    }

    @Test fun `a builder design can only be saved once its build passes Check`() {
        val store = MemoryStore()
        val g = Game(Saves(store))
        g.openBuilder()
        assertTrue(g.inBuilder)
        g.updateDraft(LevelDef(listOf("1/2"), listOf("1/4", "1/4")))
        assertNull("nothing to save before a pass", g.saveCustom())
        g.buildFromText("IR SL FL\n.. FT2 ..")
        val r = check(g)
        assertTrue(r.verdict.reasons.toString(), r.save)
        val c = g.saveCustom()!!
        assertEquals(LevelDef(listOf("1/2"), listOf("1/4", "1/4")), c.def)
        assertEquals("IR SL FL\n.. FT2 ..", c.solution)
        assertEquals("Saved as C1", g.editor.notice)
        assertEquals(LEVELS.size + 1, g.levelCount)
        assertTrue("the builder is still open", g.inBuilder)
        assertEquals("C1", g.label(LEVELS.size))
        // the custom level plays like any other, from an empty board, and survives a restart
        val again = Game(Saves(store))
        again.loadLevel(LEVELS.size)
        assertTrue(again.board.tiles.isEmpty())
        assertEquals(listOf(Frac(1, 4), Frac(1, 4)), again.level.out)
    }

    @Test fun `changing the design drops the pass and outputs it no longer has`() {
        val g = Game(Saves(MemoryStore()))
        g.openBuilder()
        g.updateDraft(LevelDef(listOf("1/2"), listOf("1/4", "1/4")))
        g.buildFromText("IR SL FL\n.. FT2 .."); check(g)
        g.updateDraft(LevelDef(listOf("1/2"), listOf("1/4")))
        assertNull(g.saveCustom())
        assertEquals("output B left the board with its target", 3, g.board.tiles.size)
        assertEquals("The targets add up to 5/8 of a belt, more than the inputs send (1/2).", LevelDef(listOf("1/2"), listOf("1/4", "3/8")).problem())
        assertNull(LevelDef(listOf("1/2", "1/4"), listOf("3/4")).problem())
    }

    @Test fun `deleting a custom level removes it and its saves`() {
        val store = MemoryStore()
        val g = Game(Saves(store))
        g.openBuilder(); g.updateDraft(LevelDef(listOf("1/2"), listOf("1/4", "1/4")))
        g.buildFromText("IR SL FL\n.. FT2 .."); check(g); g.saveCustom()
        g.loadLevel(LEVELS.size); g.buildFromText("IR")
        g.deleteCustom(LEVELS.size)
        assertEquals(LEVELS.size, g.levelCount)
        assertEquals(0, g.levelIndex)
        assertTrue(Saves(store).custom.isEmpty())
        assertNull(Saves(store).data.levels["custom-1"])
    }

    @Test fun `surprise me picks a target the builder can always meet`() {
        val g = Game(Saves(MemoryStore()))
        g.openBuilder(); g.surpriseMe()
        assertEquals(1, g.draft.out.size)
        assertEquals(listOf(Frac(1, 2)), g.draft.rates)
        assertTrue("the target is half of a proved one", (g.draft.targets.single().q * Q.of(2)).toFrac() in RANDOM_TARGETS)
        assertNull(g.draft.problem())
    }

    @Test fun `builder designs saved before inputs had rates turn into rates`() {
        val old = """{"draft":{"t":3,"out":["1/3","2/3"]},"custom":[{"id":1,"def":{"t":2,"out":["1/2","1/2"]},"solution":"IR SL FL\\n.. FT2 .."}],"nextCustom":2}"""
        val saves = Saves(MemoryStore(old))
        assertEquals(LevelDef(listOf("1/3"), listOf("1/9", "2/9")), saves.draft)
        assertEquals(LevelDef(listOf("1/2"), listOf("1/4", "1/4")), saves.custom.single().def)
    }

    @Test fun `a two-input design saves and plays with both inputs in the tray`() {
        val g = Game(Saves(MemoryStore()))
        g.openBuilder(); g.updateDraft(LevelDef(listOf("1/2", "1/4"), listOf("3/8", "3/8")))
        assertEquals(2, g.editor.trayItems().count { it.kind == Kind.SRC })
        g.buildFromText(".. .. FB\nIR CR SL\n.. IT2 FT2")
        assertTrue(check(g).save)
        g.updateDraft(LevelDef(listOf("1/2"), listOf("3/8", "3/8")))
        assertEquals("input 2 left the board", 1, g.board.tiles.values.count { it.kind == Kind.SRC })
    }

    @Test fun `level names are unique`() {
        assertEquals(LEVELS.size, LEVELS.map { it.name }.toSet().size)
    }

    @Test fun `a corrupt save starts fresh`() {
        val g = Game(Saves(MemoryStore("{not json")))
        assertEquals(0, g.levelIndex)
        assertTrue(g.board.tiles.isEmpty())
    }

    @Test fun `a save from when the footprint was a square gets its area from the solving build`() {
        // a splitter and a belt below it: a 1×2 machine
        val old = """{"level":"Halves","levels":{"Halves":{"build":"","solution":"IR SL FL\n.. TR FL2",""" +
            """"best":{"pieces":2,"side":2,"io":50,"cycle":4},"checked":{"pieces":2,"side":2,"io":50,"solved":true,"cycle":4}}}}"""
        val l = Saves(MemoryStore(old)).level("Halves")
        assertEquals(2, l.best!!.area); assertEquals(2, l.best!!.pieces); assertEquals(-1, l.best!!.flow)
        assertNull("an older last Check is dropped", l.checked)
    }
}
