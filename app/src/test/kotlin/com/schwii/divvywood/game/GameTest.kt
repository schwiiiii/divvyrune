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
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.engine.metricsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Levels, Check, saves and Build as text, driven without a screen. */
class GameTest {
    /** Level 1 (Quarter back: a half-belt input into 1/8 and 3/8), solved. */
    private val quarter = ".. .. IB ..\nRL BL TB ..\n.. CT3 ST6 ..\n.. RT ST6 ..\n.. .. TR LR"
    /** Level 1 again, its ports lined up across its middle (in left, out right on the same row, out below): a machine. */
    private val straight = "IR SL6 LR CR3\n.. ST3 LR LT\n.. TB .. .."
    /** An even split: a belt into a splitter sending up and down. */
    private val halves = "IR LR SL5"

    private fun check(g: Game): Sheet.Result {
        g.startCheck()
        g.board.finishCheck()
        return g.sheet as Sheet.Result
    }

    @Test fun `a solved Check records the best score and offers the next level`() {
        val store = MemoryStore()
        val g = Game(Saves(store))
        assertNull(g.buildFromText(quarter))
        val r = check(g)
        assertTrue(r.verdict.reasons.toString(), r.verdict.solved)
        assertEquals(listOf("1/8", "3/8"), r.verdict.rows.sortedBy { it.id }.map { it.exact.toString() })
        val m = metricsOf(decodeGrid(quarter).tiles!!)
        assertEquals(Best(m.pieces, m.area, r.flow!!.score), r.best!!.copy(piecesBuild = null, areaBuild = null, flowBuild = null))
        assertTrue(r.next)
        assertEquals(r.best, Saves(store).best("Quarter back"))

        g.loadLevel(1)
        assertEquals(1, g.levelIndex)
        assertNull(g.sheet)
        assertTrue(g.board.tiles.isEmpty())
    }

    @Test fun `best keeps each metric's best, even from different builds`() {
        val g = Game(Saves(MemoryStore()))
        val a = Best(5, 30, 80); val b = Best(9, 12, 100)
        val m = com.schwii.divvywood.engine.Metrics(7, 7, 0, 0, 2, 10)
        assertEquals(Best(5, 20, 100), a.with(m, 100))
        assertEquals(Best(7, 12, 100), b.with(m, 90))
        assertNotNull(g)
    }

    @Test fun `a build with no input is judged at once without running`() {
        val g = Game(Saves(MemoryStore()))
        g.startCheck()
        assertFalse(g.board.checking)
        val r = g.sheet as Sheet.Result
        assertFalse(r.verdict.solved)
        assertEquals(listOf("Place the input."), r.verdict.reasons)
        assertNull(r.best)
        assertFalse(r.next)
    }

    @Test fun `a wrong split runs and says what each output gets`() {
        val g = Game(Saves(MemoryStore()))
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
        assertTrue(g.editor.chipDrop(TrayItem(Kind.SRC), -15f, 5f))   // left of the start cell
        assertEquals("IR", Saves(store).data.levels["Quarter back"]?.build)
        g.editor.undo()
        assertEquals("", Saves(store).data.levels["Quarter back"]?.build)
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

    @Test fun `build from text reports bad codes and inputs the level lacks`() {
        val g = Game(Saves(MemoryStore()))
        assertEquals("Row 1, cell 2: \"XX\" isn't a cell code.", g.buildFromText("IR XX"))
        assertEquals("This level has 1 input; the text uses 2.", g.buildFromText(".. IB2 ..\nIR X LR\n.. TB .."))
        assertTrue(g.board.tiles.isEmpty())
        assertNull("old codes still build, their outputs left out", g.buildFromText("IR SL FL\n.. OT .."))
        assertEquals("Built from text", g.editor.notice)
        assertEquals("IR SL7", encodeGrid(g.board.tiles))
        g.editor.undo()
        assertTrue(g.board.tiles.isEmpty())
    }

    @Test fun `a Check records the score, and a solve records the solving build`() {
        val store = MemoryStore()
        val g = Game(Saves(store))
        g.buildFromText(halves); val r = check(g)
        val m = metricsOf(decodeGrid(halves).tiles!!)
        assertEquals(Checked(m.pieces, m.area, solved = false, flow = r.flow!!.score), g.saved(0).checked)
        assertNull(g.saved(0).solution)
        g.buildFromText(quarter); check(g)
        assertEquals(quarter, g.saved(0).solution)
        g.buildFromText(halves); g.editor.dismissNotice()
        assertEquals("the solution stays until another solve", quarter, Saves(store).level("Quarter back").solution)
        assertTrue(Saves(store).level("Quarter back").checked!!.solved)
    }

    @Test fun `a builder design can only be saved once its build passes Check`() {
        val store = MemoryStore()
        val g = Game(Saves(store))
        g.openBuilder()
        assertTrue(g.inBuilder)
        g.updateDraft(LevelDef(listOf("1/2"), listOf("1/8", "3/8")))
        assertNull("nothing to save before a pass", g.saveCustom())
        g.buildFromText(quarter)
        val r = check(g)
        assertTrue(r.verdict.reasons.toString(), r.save)
        val c = g.saveCustom()!!
        assertEquals(LevelDef(listOf("1/2"), listOf("1/8", "3/8")), c.def)
        assertEquals(quarter, c.solution)
        assertEquals("Saved as C1", g.editor.notice)
        assertEquals(LEVELS.size + 1, g.levelCount)
        assertTrue("the builder is still open", g.inBuilder)
        assertEquals("C1", g.label(LEVELS.size))
        val again = Game(Saves(store))
        again.loadLevel(LEVELS.size)
        assertTrue(again.board.tiles.isEmpty())
        assertEquals(listOf(Frac(1, 8), Frac(3, 8)), again.level.out)
    }

    @Test fun `changing the design drops the pass, and designs that can't be levels say why`() {
        val g = Game(Saves(MemoryStore()))
        g.openBuilder()
        g.updateDraft(LevelDef(listOf("1/2"), listOf("1/8", "3/8")))
        g.buildFromText(quarter); check(g)
        g.updateDraft(LevelDef(listOf("1/2"), listOf("1/8")))
        assertNull(g.saveCustom())
        assertEquals("The targets add up to 5/8 of a belt, more than the inputs send (1/2).", LevelDef(listOf("1/2"), listOf("1/4", "3/8")).problem())
        assertEquals("A single piece meets these targets. Make them harder.", LevelDef(listOf("1/2"), listOf("1/4", "1/4")).problem())
        assertEquals("A single piece meets these targets. Make them harder.", LevelDef(listOf("1/2", "1/4"), listOf("3/4")).problem())
        assertEquals("A build has 4 sides, one per input or output, and one more for what the outputs leave over. This design needs 6.",
            LevelDef(listOf("1/2", "1/2"), listOf("1/8", "1/8", "1/8")).problem())
        assertEquals("The outputs leave 3/2 of a belt over, more than one belt can carry out.", LevelDef(listOf("1", "1"), listOf("1/2")).let { LevelDef(listOf("1/1", "1/1"), listOf("1/2")) }.problem())
        assertNull(LevelDef(listOf("1/2", "1/4"), listOf("3/8", "3/8")).problem())
    }

    @Test fun `deleting a custom level removes it and its saves`() {
        val store = MemoryStore()
        val g = Game(Saves(store))
        g.openBuilder(); g.updateDraft(LevelDef(listOf("1/2"), listOf("1/8", "3/8")))
        g.buildFromText(quarter); check(g); g.saveCustom()
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

    @Test fun `a two-input design saves and plays with both inputs in the tray`() {
        val g = Game(Saves(MemoryStore()))
        g.openBuilder(); g.updateDraft(LevelDef(listOf("1/2", "1/4"), listOf("3/8", "3/8")))
        assertEquals(2, g.editor.trayItems().count { it.kind == Kind.SRC })
        g.buildFromText(".. .. .. .. IB ..\n.. RB SR3 SR3 TL ..\n.. TB TR CR6 LR LR\n.. TR LR LB .. ..\nRL RL RL CL3 BL ..\n.. .. .. .. IT2 ..")
        assertTrue(check(g).save)
        g.updateDraft(LevelDef(listOf("1/2"), listOf("3/8", "3/8")))
        assertEquals("input 2 left the board", 1, g.board.tiles.values.count { it.kind == Kind.SRC })
    }

    @Test fun `the tray holds splitters, convergers, bridges and the inputs, no outputs`() {
        val g = Game(Saves(MemoryStore()))
        assertEquals(listOf(Kind.SPLIT, Kind.MERGE, Kind.BRIDGE, Kind.MACHINE, Kind.SRC), g.editor.trayItems().map { it.kind })
    }

    @Test fun `a solve becomes a machine another level can place and solve with`() {
        val store = MemoryStore()
        val g = Game(Saves(store))
        g.buildFromText(straight); check(g)
        val h = com.schwii.divvywood.engine.machineOf(decodeGrid(straight).tiles!!).first
        assertEquals(h, g.best(0)!!.piecesBuild)
        assertTrue("not offered on its own level", g.machineChoices().none { it.hash == h })
        // the builder, set to the same rates, may use it
        g.openBuilder(); g.updateDraft(LevelDef(listOf("1/2"), listOf("1/8", "3/8")))
        val ch = g.machineChoices().single()
        assertEquals(h, ch.hash); assertEquals(0, ch.level)
        assertEquals(listOf("fewest pieces", "smallest area", "best flow"), ch.best)
        g.pickMachine(h)
        assertEquals(TrayItem(Kind.MACHINE, ref = h), g.editor.trayItems().last { it.kind == Kind.MACHINE })
        assertNull(g.buildFromText(".. IB\n.. TB\n.. #$h.1"))   // turned so its input faces up
        val r = check(g)
        assertTrue(r.verdict.reasons.toString(), r.verdict.solved)
        assertEquals("a belt and every piece inside", 1 + metricsOf(decodeGrid(straight).tiles!!).pieces, r.metrics.pieces)
        assertEquals(2, r.metrics.area)
        // the code carries the machine, and reads back in a fresh game
        val code = com.schwii.divvywood.engine.encodeShare(g.board.tiles, g.board.lib)
        assertTrue(code, code.endsWith("#$h = " + com.schwii.divvywood.engine.encodeLine(decodeGrid(straight).tiles!!)))
        val fresh = Game(Saves(MemoryStore()))
        fresh.openBuilder(); fresh.updateDraft(LevelDef(listOf("1/2"), listOf("1/8", "3/8")))
        assertNull(fresh.buildFromText(code))
        assertTrue(check(fresh).verdict.solved)
        assertEquals("The text uses machine #abcdef without its build.", fresh.buildFromText("IR LR #abcdef.0"))
    }

    @Test fun `an outdated machine moves to its level's newer build at Check`() {
        val store = MemoryStore()
        Saves(store).apply {
            // a worse solve of level 1 first, best at everything; then a better one
            putBuild("Quarter back", decodeGrid("IR SL6 LR LR CR3\n.. ST3 LR LR LT\n.. TB .. .. ..").tiles!!)
            recordCheck("Quarter back", com.schwii.divvywood.engine.Metrics(11, 7, 2, 1, 5, 4), 50, solved = true)
        }
        val old = Saves(store).best("Quarter back")!!.piecesBuild!!
        Saves(store).apply {
            putBuild("Quarter back", decodeGrid(straight).tiles!!)
            recordCheck("Quarter back", com.schwii.divvywood.engine.Metrics(9, 6, 2, 1, 4, 4), 95, solved = true)
        }
        val g = Game(Saves(store))
        g.openBuilder(); g.updateDraft(LevelDef(listOf("1/2"), listOf("1/8", "3/8")))
        assertNull(g.buildFromText(".. IB\n.. TB\n.. #$old.1"))
        assertTrue(g.outdated(old))
        assertTrue(check(g).verdict.solved)
        val now = g.board.tiles.values.single { it.kind == Kind.MACHINE }.ref
        assertEquals(com.schwii.divvywood.engine.machineOf(decodeGrid(straight).tiles!!).first, now)
        assertFalse(g.outdated(now!!))
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
