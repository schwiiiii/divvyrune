package com.schwii.divvyrune.board

import com.schwii.divvyrune.engine.Cell
import com.schwii.divvyrune.engine.Kind
import com.schwii.divvyrune.engine.LEVELS
import com.schwii.divvyrune.engine.Tile
import com.schwii.divvyrune.engine.Tiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Drives the building controls the way a finger would, on a camera with 10-pixel cells at the origin. */
class EditorTest {
    private lateinit var e: Editor
    private val haptics = mutableListOf<Haptic>()
    private val tiles get() = e.board.tiles
    private fun at(x: Int, y: Int) = tiles[Cell(x, y)]
    private fun cx(x: Int) = x * 10f + 5f

    @Before fun setUp() {
        val board = BoardState(LEVELS.last(), linkedMapOf<Cell, Tile>() as Tiles).apply { ox = 0f; oy = 0f; z = 10f }
        e = Editor(board) { haptics += it }
    }

    private fun tap(x: Int, y: Int) { e.down(cx(x), cx(y)); e.up(cx(x), cx(y)) }
    /** A finger drag through cell centres, a few steps per cell. */
    private fun drag(fx: Int, fy: Int, tx: Int, ty: Int) {
        e.down(cx(fx), cx(fy))
        for (s in 1..8) e.drag(cx(fx) + (cx(tx) - cx(fx)) * s / 8, cx(fy) + (cx(ty) - cx(fy)) * s / 8)
        e.up(cx(tx), cx(ty))
    }
    private fun drop(kind: Kind, x: Int, y: Int, id: Int = -1) = e.chipDrop(TrayItem(kind, id), cx(x), cx(y))

    @Test fun `tray chips drop blocks that face what feeds them, an input where it's dropped`() {
        assertTrue(drop(Kind.SPLIT, 0, 0))
        assertEquals(Kind.SPLIT, at(0, 0)?.kind)
        assertTrue(drop(Kind.SRC, -3, 0))
        assertEquals("it stays put, facing the build from the side it was dropped beyond", Tile(Kind.SRC, out = 0, id = 0), at(-3, 0))
        assertTrue(e.board.graph.nodes.first { it.kind == Kind.SRC }.errs.single().contains("doesn't touch the build"))
        assertTrue(Haptic.DROP in haptics)
        assertTrue(e.isPlaced(TrayItem(Kind.SRC, 0)))
        assertFalse("each input is placed once", drop(Kind.SRC, 3, 3, 0))
    }

    @Test fun `on an empty board an input faces the start cell, so a splitter there takes its marbles`() {
        assertTrue(drop(Kind.SRC, 0, -1))
        assertEquals(Tile(Kind.SRC, out = 1, id = 0), at(0, -1))
        assertTrue(drop(Kind.SPLIT, 0, 0))
        assertEquals(3, at(0, 0)!!.inSide)
        assertEquals(Kind.SRC, at(0, -1)?.kind)
    }

    @Test fun `a chip dropped on a belt replaces it and takes its direction`() {
        drop(Kind.SPLIT, 0, 0)
        drag(0, 0, 0, 3)   // belts down from the splitter
        assertTrue(drop(Kind.MERGE, 0, 1))
        assertEquals(Tile(Kind.MERGE, out = 1), at(0, 1))
        assertTrue(drop(Kind.BRIDGE, 0, 2))
        assertEquals("a bridge keeps the belt's way as one lane", Tile(Kind.BRIDGE, out = 0, inSide = 1), at(0, 2))
    }

    @Test fun `tap selects a piece and shows its numbers, tapping the table deselects`() {
        drop(Kind.SPLIT, 0, 0); drop(Kind.MERGE, 2, 2)
        tap(0, 0)
        assertEquals(setOf(Cell(0, 0)), e.selection)
        assertEquals("Splitter", readoutOf(e.board, Cell(0, 0))?.title)
        tap(2, 2)
        assertEquals("tapping another piece selects it instead", setOf(Cell(2, 2)), e.selection)
        tap(4, 3)
        assertTrue(e.selection.isEmpty())
    }

    @Test fun `Rotate turns the selected piece and Delete returns an input to the tray`() {
        drop(Kind.SPLIT, 0, 0); drop(Kind.SRC, -1, 0, 0)
        val in0 = at(0, 0)!!.inSide
        tap(0, 0); e.rotateSelection()
        assertEquals((in0 + 1) % 4, at(0, 0)!!.inSide)
        assertEquals("the piece stays selected for another turn", setOf(Cell(0, 0)), e.selection)
        tap(-1, 0); e.deleteSelection()
        assertNull(at(-1, 0))
        assertFalse(e.isPlaced(TrayItem(Kind.SRC, 0)))
        assertTrue(e.selection.isEmpty())
    }

    @Test fun `dragging a selected piece moves it, dragging it unselected draws a belt`() {
        drop(Kind.SPLIT, 0, 0)
        drag(0, 0, 3, 0)
        assertEquals("unselected: a belt", Kind.BELT, at(3, 0)?.kind)
        assertEquals(Kind.SPLIT, at(0, 0)?.kind)
        e.undo()
        tap(0, 0)
        drag(0, 0, 0, -2)
        assertNull(at(0, 0)); assertEquals(Kind.SPLIT, at(0, -2)?.kind)
        assertEquals("it stays selected where it lands", setOf(Cell(0, -2)), e.selection)
        assertNull(e.move)
        assertTrue(Haptic.PICK in haptics)
    }

    @Test fun `a piece dragged over a belt replaces it, onto another piece goes back`() {
        drop(Kind.SPLIT, 0, 0); drag(0, 0, 4, 0)   // belts at 1..4
        drop(Kind.MERGE, 0, 3); drop(Kind.SPLIT, 2, 3)
        tap(0, 3)
        e.down(cx(0), cx(3)); e.drag(cx(1), cx(3)); e.drag(cx(2), cx(3))
        assertTrue("a splitter blocks", e.blockedAt(Cell(2, 3)))
        e.up(cx(2), cx(3))
        assertEquals(Kind.MERGE, at(0, 3)?.kind); assertEquals("No room here", e.notice)
        drag(0, 3, 2, 0)
        assertEquals(Kind.MERGE, at(2, 0)?.kind)
        assertNull(at(0, 3))
        assertEquals(Kind.BELT, at(1, 0)?.kind); assertEquals(Kind.BELT, at(3, 0)?.kind)
    }

    @Test fun `dragging the table pans`() {
        drop(Kind.SPLIT, 2, 2)
        val ox = e.board.ox; val oy = e.board.oy
        drag(0, 0, 3, 1)
        assertEquals(ox + 30f, e.board.ox, 0.01f); assertEquals(oy + 10f, e.board.oy, 0.01f)
        assertEquals("no belt from the table", 1, tiles.size)
    }

    @Test fun `a belt is drawn from a piece only when it's valid`() {
        drop(Kind.SPLIT, 2, 0); drop(Kind.SRC, 1, 0)
        assertEquals(Kind.SRC, at(1, 0)?.kind)
        drag(1, 0, 4, 0)   // straight through the splitter: not allowed
        assertNull(at(4, 0))
        drag(2, 0, 2, 3)
        assertEquals(Kind.BELT, at(2, 2)?.kind)
        assertEquals(1, at(2, 2)!!.out)
        drag(2, 3, 4, 3)   // on from the belt's end
        assertEquals(Kind.BELT, at(4, 3)?.kind)
    }

    @Test fun `drawing out of a converger and into a splitter sets their sides`() {
        drop(Kind.SPLIT, 0, 4); drop(Kind.MERGE, 2, 4)
        drag(2, 4, 0, 4)
        assertEquals(2, at(2, 4)!!.out)
        assertEquals(0, at(0, 4)!!.inSide)
    }

    @Test fun `drawing straight across a belt bridges it`() {
        drop(Kind.SPLIT, 0, 0); drag(0, 0, 0, 4)   // belts down at 1..4
        drop(Kind.MERGE, -2, 2)
        drag(-2, 2, 2, 2)
        assertEquals(Tile(Kind.BRIDGE, out = 0, inSide = 1), at(0, 2))
        assertEquals(Kind.BELT, at(2, 2)?.kind)
    }

    @Test fun `retracing a connection undoes it, and drawing it again brings it back`() {
        drop(Kind.SPLIT, 0, 0); drop(Kind.MERGE, 1, 0)
        assertTrue(at(0, 0)!!.isOpen(0))
        drag(0, 0, 1, 0)
        assertFalse("the join closes on both", at(0, 0)!!.isOpen(0)); assertFalse(at(1, 0)!!.isOpen(2))
        drag(0, 0, 1, 0)
        assertTrue(at(0, 0)!!.isOpen(0)); assertTrue(at(1, 0)!!.isOpen(2))
        drag(1, 0, 1, 3)
        assertEquals(Kind.BELT, at(1, 2)?.kind)
        drag(1, 0, 1, 3)
        assertNull("retracing a belt removes it", at(1, 2))
    }

    @Test fun `dragging a splitter straight out to the edge opens or closes that side`() {
        drop(Kind.SPLIT, 0, 0)
        drag(0, 0, 0, -1)
        assertFalse(at(0, 0)!!.isOpen(3)); assertEquals("Side closed", e.notice)
        assertEquals("no belt is drawn", 1, tiles.size)
        drag(0, 0, 0, -1)
        assertTrue(at(0, 0)!!.isOpen(3)); assertEquals("Side opened", e.notice)
    }

    @Test fun `Select mode boxes pieces, then moves and rotates them together`() {
        drop(Kind.SPLIT, 0, -2)
        drag(0, -2, 2, -2)   // splitter plus two belts
        e.switchSelectMode(true)
        val before = tiles.size
        drag(-1, -3, 2, -1)
        assertEquals(3, e.selection.size)
        assertEquals("a drag in Select mode draws no belt", before, tiles.size)
        drag(1, -2, 1, 0)
        assertEquals(Kind.SPLIT, at(0, 0)?.kind); assertEquals(Kind.BELT, at(1, 0)?.kind); assertEquals(Kind.BELT, at(2, 0)?.kind)
        e.rotateSelection()
        assertEquals(setOf(Cell(1, -1), Cell(1, 0), Cell(1, 1)), e.selection)
        tap(5, 5)
        assertTrue("tapping the table deselects in Select mode too", e.selection.isEmpty())
        e.switchSelectMode(false)
        drag(1, 1, 1, 4)
        assertEquals("after Select mode a drag from a piece draws again", Kind.BELT, at(1, 3)?.kind)
    }

    @Test fun `a blocked group rotation changes nothing and says why`() {
        drop(Kind.SPLIT, 0, 0); drag(0, 0, 2, 0)
        drop(Kind.MERGE, 1, 1)
        e.switchSelectMode(true); drag(0, -1, 2, 0)
        val before = LinkedHashMap(tiles)
        e.rotateSelection()
        assertEquals(before, LinkedHashMap(tiles))
        assertEquals("No room to rotate here", e.notice)
    }

    @Test fun `undo steps back one edit at a time`() {
        drop(Kind.SPLIT, 0, 0)
        drag(0, 0, 3, 0)
        assertEquals(4, tiles.size)
        e.undo()
        assertEquals(1, tiles.size)
        e.undo()
        assertTrue(tiles.isEmpty())
        assertFalse(e.canUndo)
    }

    @Test fun `two fingers cancel a gesture without drawing or moving`() {
        drop(Kind.SPLIT, 0, 0)
        e.down(cx(0), cx(0)); e.drag(cx(2), cx(0)); e.cancel(); e.up(cx(2), cx(0))
        assertEquals(1, tiles.size)
        tap(0, 0)
        e.down(cx(0), cx(0)); e.drag(cx(0), cx(2)); e.cancel(); e.up(cx(0), cx(2))
        assertEquals(Kind.SPLIT, at(0, 0)?.kind); assertNull(e.move)
    }

    /** The even split as a machine at (0, 0): in from the left, out of the top and bottom. */
    private fun machineBoard(): String {
        val (h, code) = com.schwii.divvyrune.engine.machineOf(com.schwii.divvyrune.engine.decodeGrid("IR LR SL5").tiles!!)
        val lib = com.schwii.divvyrune.engine.MachineLib(mapOf(h to com.schwii.divvyrune.engine.MachineDef(code)))
        val t = linkedMapOf(Cell(0, 0) to Tile(Kind.MACHINE, ref = h, out = 0)) as Tiles
        e = Editor(BoardState(LEVELS.last(), t, lib = lib).apply { ox = 0f; oy = 0f; z = 10f })
        return h
    }

    @Test fun `belts draw out of a machine's ways out and into its inputs, and retrace away`() {
        machineBoard()
        drag(0, 0, 0, 2)
        assertEquals(Tile(Kind.BELT, from = 3, out = 1), at(0, 1))
        assertEquals(Kind.BELT, at(0, 2)?.kind)
        drop(Kind.SPLIT, -2, 0)
        drag(-2, 0, 0, 0)
        assertEquals(Tile(Kind.BELT, from = 2, out = 0), at(-1, 0))
        assertEquals(0, e.board.graph.nodes.count { it.errs.any { m -> "machine's input" in m } })
        drag(0, 0, 0, 2)   // back over the belts it drew: they go
        assertNull(at(0, 1)); assertNull(at(0, 2))
    }

    @Test fun `a belt can't leave a machine by its input or a side with no port`() {
        machineBoard()
        drag(0, 0, 2, 0)
        assertNull(at(1, 0)); assertEquals("No way out on that side of the machine", e.notice)
        drag(0, 0, -2, 0)
        assertNull(at(-1, 0)); assertEquals("That side of the machine takes marbles in", e.notice)
        drop(Kind.SPLIT, 0, -2)
        drag(0, -2, 0, 0)
        assertNull(at(0, -1)); assertEquals("That side of the machine sends marbles out", e.notice)
    }

    @Test fun `a moved input stays where it's put and faces the build from there`() {
        drop(Kind.SPLIT, 0, 0); drop(Kind.MERGE, 1, 0); drop(Kind.SRC, 0, -1)
        tap(0, -1); drag(0, -1, -1, 0)   // from above to the left
        assertEquals(Tile(Kind.SRC, out = 0, id = 0), at(-1, 0))
        assertNull(at(0, -1))
        drop(Kind.MERGE, 2, 0)
        assertEquals("the build changing never moves it", Kind.SRC, at(-1, 0)?.kind)
        drop(Kind.SPLIT, -1, 1); drop(Kind.SPLIT, -2, 0)
        assertEquals(Kind.SRC, at(-1, 0)?.kind)
        assertTrue(e.board.graph.nodes.first { it.kind == Kind.SRC }.errs.single().contains("inside the build"))
    }
}
