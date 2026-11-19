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
    private var clock = 0L
    private val tiles get() = e.board.tiles
    private fun at(x: Int, y: Int) = tiles[Cell(x, y)]
    private fun cx(x: Int) = x * 10f + 5f

    @Before fun setUp() {
        val board = BoardState(LEVELS.last(), linkedMapOf<Cell, Tile>() as Tiles).apply { ox = 0f; oy = 0f; z = 10f }
        e = Editor(board) { haptics += it }
    }

    private fun tap(x: Int, y: Int) { e.down(cx(x), cx(y), clock); e.up(cx(x), cx(y)); clock += 100 }
    private fun hold(x: Int, y: Int) { e.down(cx(x), cx(y), clock); clock += 500; e.tick(clock); e.up(cx(x), cx(y)) }
    /** A finger drag through cell centres, a few steps per cell. */
    private fun drag(fx: Int, fy: Int, tx: Int, ty: Int) {
        e.down(cx(fx), cx(fy), clock)
        for (s in 1..8) e.drag(cx(fx) + (cx(tx) - cx(fx)) * s / 8, cx(fy) + (cx(ty) - cx(fy)) * s / 8)
        e.up(cx(tx), cx(ty)); clock += 100
    }
    private fun drop(kind: Kind, x: Int, y: Int, id: Int = -1) = e.chipDrop(TrayItem(kind, id), cx(x), cx(y))

    @Test fun `tray chips drop blocks that face what feeds them`() {
        assertTrue(drop(Kind.SPLIT, 0, 0))
        assertEquals(Kind.SPLIT, at(0, 0)?.kind)
        assertTrue(drop(Kind.SRC, -2, 0))
        assertTrue(Haptic.DROP in haptics)
        assertFalse("an output can't land on an occupied square", drop(Kind.OUT, 0, 0, 0))
        drop(Kind.OUT, 2, 0, 0)
        assertTrue(e.isPlaced(TrayItem(Kind.OUT, 0)))
        assertFalse("each output is placed once", drop(Kind.OUT, 3, 3, 0))
    }

    @Test fun `tap shows the readout, tap empty closes it`() {
        drop(Kind.SPLIT, 0, 0)
        tap(0, 0)
        assertEquals(Cell(0, 0), e.readout)
        assertNull(e.menu)
        assertEquals("Splitter", readoutOf(e.board, Cell(0, 0))?.title)
        tap(4, 3)
        assertNull(e.readout)
    }

    @Test fun `hold opens the menu and Rotate turns the piece`() {
        drop(Kind.SPLIT, 0, 0)
        val in0 = at(0, 0)!!.inSide
        hold(0, 0)
        assertNotNull(e.menu)
        assertTrue(Haptic.PICK in haptics)
        e.menuRotate()
        assertEquals((in0 + 1) % 4, at(0, 0)!!.inSide)
        tap(4, 3)
        assertNull("tapping elsewhere closes the menu", e.menu)
    }

    @Test fun `Move carries pieces by the finger's offset from anywhere`() {
        drop(Kind.SPLIT, 0, 0)
        hold(0, 0); e.menuMove()
        assertNotNull(e.move)
        drag(3, 3, 3, 1)
        assertNull(at(0, 0)); assertEquals(Kind.SPLIT, at(0, -2)?.kind)
        assertNull(e.move)
    }

    @Test fun `Move onto an occupied square stays lifted until Cancel`() {
        drop(Kind.SPLIT, 0, -2); drop(Kind.OUT, 2, 0, 0)
        hold(0, -2); e.menuMove()
        drag(0, 3, 2, 5)
        assertEquals(Kind.SPLIT, at(0, -2)?.kind)
        assertNotNull(e.move)
        assertTrue(e.blockedAt(Cell(2, 0)))
        e.cancelMove()
        assertNull(e.move); assertEquals(Kind.SPLIT, at(0, -2)?.kind)
    }

    @Test fun `Delete returns an output to the tray`() {
        drop(Kind.OUT, 2, 0, 0)
        hold(2, 0); e.menuDelete()
        assertNull(at(2, 0))
        assertFalse(e.isPlaced(TrayItem(Kind.OUT, 0)))
    }

    @Test fun `a drag draws a belt only when it's valid`() {
        drop(Kind.SPLIT, 2, 0)
        drag(0, 0, 4, 0)   // straight through the splitter: not allowed
        assertNull(at(0, 0)); assertNull(at(4, 0))
        drag(0, 1, 0, 4)
        assertEquals(Kind.BELT, at(0, 2)?.kind)
        assertEquals(1, at(0, 2)!!.out)
    }

    @Test fun `drawing out of a converger and into a splitter sets their sides`() {
        drop(Kind.MERGE, 0, 0)
        drag(0, 0, 0, -2)
        assertEquals(3, at(0, 0)!!.out)
        drop(Kind.SPLIT, 0, 4)
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
        hold(1, -2)
        assertEquals(3, e.menu!!.cells.size)
        e.menuMove(); drag(0, 2, 0, 4)
        assertEquals(Kind.SPLIT, at(0, 0)?.kind); assertEquals(Kind.BELT, at(1, 0)?.kind); assertEquals(Kind.BELT, at(2, 0)?.kind)
        hold(1, 0); e.menuRotate()
        assertEquals(setOf(Cell(1, -1), Cell(1, 0), Cell(1, 1)), e.selection)
        tap(5, 5)
        e.switchSelectMode(false)
        assertTrue(e.selection.isEmpty())
        drag(-3, 3, -3, 5)
        assertEquals("after Select mode a drag draws again", Kind.BELT, at(-3, 4)?.kind)
    }

    @Test fun `a blocked group rotation changes nothing and says why`() {
        drop(Kind.SPLIT, 0, 0); drag(0, 0, 2, 0)
        drop(Kind.MERGE, 1, 1)
        e.switchSelectMode(true); drag(0, 0, 2, 0)
        val before = LinkedHashMap(tiles)
        hold(1, 0); e.menuRotate()
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

    @Test fun `two fingers cancel a gesture without drawing`() {
        e.down(cx(0), cx(0), clock)
        e.drag(cx(2), cx(0))
        e.cancel()
        e.up(cx(2), cx(0))
        assertTrue(tiles.isEmpty())
    }
}
