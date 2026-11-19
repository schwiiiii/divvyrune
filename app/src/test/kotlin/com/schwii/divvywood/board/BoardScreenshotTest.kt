package com.schwii.divvywood.board

import android.graphics.Bitmap
import android.graphics.Canvas
import com.github.takahirom.roborazzi.captureRoboImage
import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Frac
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.Level
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.engine.tilesOf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Renders boards to PNGs (app/src/test/screenshots) with the same code the app draws with. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardScreenshotTest {
    private val density = 2.625f
    private val w = 1080
    private val h = 1400

    private fun render(state: BoardState, ticks: Int, name: String): Bitmap {
        state.fit(w.toFloat(), h.toFloat())
        repeat(ticks) { state.step() }
        state.advance(0f)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        BoardRenderer(state, reduceMotion = true).draw(Canvas(bmp), w, h)
        bmp.captureRoboImage("src/test/screenshots/$name.png")
        return bmp
    }

    @Test fun whiteboard130() {
        val tiles = decodeGrid("OR SR IL ..\nOR ST CB BL\nOR CL ST BT\n.. RT ST LT\n.. .. FT ..").tiles!!
        val state = BoardState(LEVELS.last(), tiles, density).apply { z = 64 * density }
        val bmp = render(state, 401, "board-1-30")
        // the build is in the middle of the frame and isn't blank: some pixels differ from the table
        val corner = bmp.getPixel(5, 5)
        assertTrue((0 until 50).any { bmp.getPixel(w / 2 - 200 + it * 8, h / 2) != corner })
    }

    @Test fun everyBlock() {
        // a splitter fed by the input, feeding an output, a bypass and a converger loop; an overloaded loop at full speed,
        // plus a stray converger and an output with nothing feeding it (outlined in red)
        val tiles = decodeGrid("IR SL OL\n.. ST RT\n.. CL TL\n.. FT ..").tiles!!
        tiles[Cell(4, 0)] = Tile(Kind.MERGE, out = 0)
        tiles[Cell(4, 2)] = Tile(Kind.OUT, id = 1, inSide = 3)
        val level = Level("blocks", 1, listOf(Frac(1, 2), Frac(1, 2)), 1, "")
        render(BoardState(level, tiles, density).apply { z = 72 * density }, 7, "board-every-block")
    }
}
