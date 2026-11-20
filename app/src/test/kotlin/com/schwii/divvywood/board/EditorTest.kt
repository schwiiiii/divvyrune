package com.schwii.divvywood.board

import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.Tiles
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

    @Test fun `tray chips drop blocks that face what feeds them`() {
        assertTrue(drop(Kind.SPLIT, 0, 0))
        assertEquals(Kind.SPLIT, at(0, 0)?.kind)
        assertTrue(drop(Kind.SRC, -2, 0))
        assertTrue(Haptic.DROP in haptics)
        assertFalse("an output can't land on another piece", drop(Kind.OUT, 0, 0, 0))
        drop(Kind.OUT, 2, 0, 0)
        assertTrue(e.isPlaced(TrayItem(Kind.OUT, 0)))
        assertFalse("each output is placed once", drop(Kind.OUT, 3, 3, 0))
    }

    @Test fun `a chip dropped on a belt replaces it and takes its direction`() {
        drop(Kind.SPLIT, 0, 0)
        drag(0, 0, 0, 3)   // belts down from the splitter
        assertTrue(drop(Kind.MERGE, 0, 1))
        assertEquals(Tile(Kind.MERGE, out = 1), at(0, 1))
        assertTrue(drop(Kind.OUT, 0, 3, 0))
        assertEquals(Tile(Kind.OUT, id = 0, inSide = 3), at(0, 3))
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

    @Test fun `Rotate turns the selected piece and Delete returns an output to the tray`() {
        drop(Kind.SPLIT, 0, 0); drop(Kind.OUT, 2, 0, 0)
        val in0 = at(0, 0)!!.inSide
        tap(0, 0); e.rotateSelection()
        assertEquals((in0 + 1) % 4, at(0, 0)!!.inSide)
        assertEquals("the piece stays selected for another turn", setOf(Cell(0, 0)), e.selection)
        tap(2, 0); e.deleteSelection()
        assertNull(at(2, 0))
        assertFalse(e.isPlaced(TrayItem(Kind.OUT, 0)))
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
        drop(Kind.MERGE, 0, 3); drop(Kind.OUT, 2, 3, 0)
        tap(0, 3)
        e.down(cx(0), cx(3)); e.drag(cx(1), cx(3)); e.drag(cx(2), cx(3))
        assertTrue("an output blocks", e.blockedAt(Cell(2, 3)))
        e.up(cx(2), cx(3))
        assertEquals(Kind.MERGE, at(0, 3)?.kind); assertEquals("No room here", e.notice)
        drag(0, 3, 2, 0)
        assertEquals(Kind.MERGE, at(2, 0)?.kind)
        assertNull(at(0, 3))
        assertEquals(Kind.BELT, at(1, 0)?.kind); assertEquals(Kind.BELT, at(3, 0)?.kind)
    }

    @Test fun `dragging the table or an unselected output pans`() {
        drop(Kind.OUT, 2, 2, 0)
        val ox = e.board.ox; val oy = e.board.oy
        drag(0, 0, 3, 1)
        assertEquals(ox + 30f, e.board.ox, 0.01f); assertEquals(oy + 10f, e.board.oy, 0.01f)
        assertEquals("no belt from the table", 1, tiles.size)
        val ox2 = e.board.ox
        drag(5, 5, 4, 5)   // the output, now at (5, 5) on screen after the pan
        assertEquals(ox2 - 10f, e.board.ox, 0.01f)
        assertEquals(1, tiles.size)
    }

    @Test fun `a belt is drawn from a piece only when it's valid`() {
        drop(Kind.SRC, 0, 0); drop(Kind.SPLIT, 2, 0)
        drag(0, 0, 4, 0)   // straight through the splitter: not allowed
        assertNull(at(1, 0)); assertNull(at(4, 0))
        drag(0, 0, 0, 3)
        assertEquals(Kind.BELT, at(0, 2)?.kind)
        assertEquals(1, at(0, 2)!!.out)
        drag(0, 3, 2, 3)   // on from the belt's end
        assertEquals(Kind.BELT, at(2, 3)?.kind)
    }

    @Test fun `drawing out of a converger and into a splitter sets their sides`() {
        drop(Kind.MERGE, 0, 0)
        drag(0, 0, 0, -2)
        assertEquals(3, at(0, 0)!!.out)
        drop(Kind.SPLIT, 0, 4); drop(Kind.SRC, 2, 4)
        drag(2, 4, 0, 4)
        assertEquals(0, at(0, 4)!!.inSide)
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
}
