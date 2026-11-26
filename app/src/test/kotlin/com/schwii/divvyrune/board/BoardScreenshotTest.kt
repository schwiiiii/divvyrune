package com.schwii.divvyrune.board

import android.graphics.Bitmap
import android.graphics.Canvas
import com.schwii.divvyrune.engine.Cell
import com.schwii.divvyrune.engine.Frac
import com.schwii.divvyrune.engine.Kind
import com.schwii.divvyrune.engine.LEVELS
import com.schwii.divvyrune.engine.Level
import com.schwii.divvyrune.engine.Tile
import com.schwii.divvyrune.engine.decodeGrid
import com.schwii.divvyrune.engine.tilesOf
import org.junit.Assert.assertTrue
import java.io.File
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
        File("src/test/screenshots").mkdirs()
        File("src/test/screenshots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    @Test fun whiteboard130() {
        val tiles = decodeGrid(".. .. .. .. IB ..\n.. BR CB5 RL TL ..\nRB SR6 ST7 SL6 LB ..\nTB .. CB6 TL TB ..\nTR CB5 CL3 SR3 TL ..\n.. CB6 RL ST7 LR LR\n.. CB6 RL TL .. ..").tiles!!
        val state = BoardState(LEVELS.last(), tiles, density).apply { z = 64 * density }
        val bmp = render(state, 401, "board-1-30")
        // the build is in the middle of the frame and isn't blank: some pixels differ from the table
        val corner = bmp.getPixel(5, 5)
        assertTrue((0 until 50).any { bmp.getPixel(w / 2 - 200 + it * 8, h / 2) != corner })
    }

    @Test fun everyBlock() {
        // input 2's splitter sends right into a converger and down across input 1's line on a bridge; the converger
        // joins input 1 and leaves on the right (A); the bridge's lane leaves at the bottom (B); a stray converger
        // is outlined in red
        val tiles = decodeGrid(".. IB2 ..\n.. ST3 CB\nIR X CR\n.. TB CL").tiles!!
        val level = Level("blocks", listOf(Frac(1, 2), Frac(1, 4)), listOf(Frac(5, 8), Frac(1, 8)))
        render(BoardState(level, tiles, density).apply { z = 72 * density }, 7, "board-every-block")
    }

    @Test fun splitterMechanisms() {
        // a 3-way splitter (slim flipper) feeding a 2-way one (T-shaped flip-flop)
        val code = ".. .. IB ..\nSR3 RL ST7 LR\nCB6 RL TL .."
        val level = Level("mech", 2, listOf(null, null, null))
        render(BoardState(level, decodeGrid(code).tiles!!, density).apply { z = 110 * density }, 9, "board-splitters")
        render(BoardState(level, decodeGrid(code).tiles!!, density).apply { z = 110 * density }, 10, "board-splitters-next")
    }
}
