package com.schwii.divvywood.game

import com.schwii.divvywood.board.TrayItem
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.Frac
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.RANDOM
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
        assertEquals(listOf("1/2", "1/2"), r.verdict.rows.map { it.exact.toString() })
        assertEquals(Best(1, 6, 6), r.best)
        assertTrue(r.next)
        assertEquals(Best(1, 6, 6), Saves(store).best("Halves"))

        g.loadLevel(1)
        assertEquals(1, g.levelIndex)
        assertNull(g.sheet)
        assertTrue(g.board.tiles.isEmpty())
    }

    @Test fun `best keeps the fewest parts and the smallest and largest area`() {
        val g = Game(Saves(MemoryStore()))
        g.buildFromText(halves); check(g)
        g.buildFromText("IR LR SL FL\n.. .. FT2 .."); val r = check(g)   // one belt more, 4×2
        assertEquals(Best(1, 6, 8), r.best)
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
        assertEquals("Output A gets 1/2 of the input. It needs 1/4.", r.verdict.reasons.first())
        assertEquals(0.5, r.verdict.rows[0].measured, 0.01)
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
        assertNull(g.buildFromText("IR SL FL\n.. OT .."))
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
        assertEquals(Best(5, 9, 12), g.best(LEVELS.indexOf(levelNamed("Eighths"))))
        g.editor.undo()   // any write stores the new form
        g.loadLevel(0)
        assertEquals(setOf("Eighths", "Fifth"), Saves(store).data.levels.keys)
        assertEquals("Halves", Saves(store).cur)
    }

    @Test fun `the random level keeps its target and build across restarts and picks a new target on request`() {
        val store = MemoryStore()
        val g = Game(Saves(store), dev = true)
        g.loadLevel(g.randomIndex)
        val first = g.level.out[0]!!
        assertTrue(first in RANDOM_TARGETS)
        assertNull(g.buildFromText("IR SL FL\n.. OT .."))
        val again = Game(Saves(store), dev = true)
        assertEquals(RANDOM, again.level.name)
        assertEquals(first, again.level.out[0])
        assertEquals("IR SL FL\n.. OT ..", encodeGrid(again.board.tiles))
        again.newTarget()
        assertTrue(again.level.out[0] != first)
        assertEquals("IR SL FL\n.. OT ..", encodeGrid(again.board.tiles))
        assertEquals(again.level.out[0], Saves(store).randomTarget)
    }

    @Test fun `solving the random level offers a new target and its best resets with the target`() {
        val store = MemoryStore()
        Saves(store).randomTarget = Frac(1, 2)   // as if picked: the halves build solves it
        val g = Game(Saves(store), dev = true)
        g.loadLevel(g.randomIndex)
        g.buildFromText("IR SL FL\n.. OT ..")
        val r = check(g)
        assertTrue(r.verdict.reasons.toString(), r.verdict.solved)
        assertFalse(r.next)
        assertEquals(Best(1, 6, 6), g.best(g.randomIndex))
        g.newTarget()
        assertNull(g.best(g.randomIndex))
    }

    @Test fun `a Check records the score, and a solve records the solving build`() {
        val store = MemoryStore()
        val g = Game(Saves(store))
        g.loadLevel(1)   // 1/4 and 3/4
        g.buildFromText(halves); check(g)
        assertEquals(Checked(1, 3, 2, solved = false), g.saved(1).checked)
        assertNull(g.saved(1).solution)
        g.loadLevel(0)
        g.buildFromText(halves); check(g)
        assertEquals(halves, g.saved(0).solution)
        g.buildFromText("IR LR SL FL\n.. .. FT2 .."); g.editor.dismissNotice()
        assertEquals("the solution stays until another solve", halves, Saves(store).level("Halves").solution)
        assertEquals(Checked(1, 3, 2, solved = true), Saves(store).level("Halves").checked)
    }

    @Test fun `a new random target forgets the old target's results`() {
        val store = MemoryStore()
        Saves(store).randomTarget = Frac(1, 2)
        val g = Game(Saves(store), dev = true)
        g.loadLevel(g.randomIndex); g.buildFromText("IR SL FL\n.. OT .."); check(g)
        assertNotNull(g.saved(g.randomIndex).solution)
        g.newTarget()
        assertEquals(LevelSave(build = "IR SL FL\n.. OT .."), g.saved(g.randomIndex))
    }

    @Test fun `players get the puzzle levels, and the sandbox and random level are dev tools`() {
        val store = MemoryStore("""{"level":"Sandbox"}""")
        val g = Game(Saves(store))
        assertEquals(LEVELS.size, g.levelCount)
        assertEquals(-1, g.randomIndex)
        assertEquals("a saved dev level opens the first level instead", 0, g.levelIndex)
        assertTrue((0 until g.levelCount).none { g.levelAt(it).sandbox })
        val dev = Game(Saves(MemoryStore()), dev = true)
        assertEquals(LEVELS.size + 2, dev.levelCount)
        assertTrue(dev.levelAt(LEVELS.size).sandbox)
        assertEquals(RANDOM, dev.levelAt(dev.randomIndex).name)
    }

    @Test fun `level names are unique`() {
        assertEquals(LEVELS.size, LEVELS.map { it.name }.toSet().size)
    }

    @Test fun `a corrupt save starts fresh`() {
        val g = Game(Saves(MemoryStore("{not json")))
        assertEquals(0, g.levelIndex)
        assertTrue(g.board.tiles.isEmpty())
    }
}
