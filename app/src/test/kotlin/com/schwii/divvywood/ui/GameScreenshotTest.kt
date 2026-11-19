package com.schwii.divvywood.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.schwii.divvywood.board.BoardState
import com.schwii.divvywood.board.Editor
import com.schwii.divvywood.board.TrayItem
import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.decodeGrid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Whole-screen captures (board, overlays, tray, toolbar) saved to app/src/test/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class GameScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        // draw the window's own view: Robolectric renders it with Android's real graphics
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        File("src/test/screenshots").mkdirs()
        File("src/test/screenshots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun editorFor(level: Int, grid: String?, z: Float): Editor {
        val d = 3f
        val tiles = grid?.let { decodeGrid(it).tiles!! } ?: linkedMapOf()
        return Editor(BoardState(LEVELS[level], tiles, d).apply { this.z = z * d })
    }

    private fun Editor.tapCell(c: Cell) { down(board.screenX(c.x), board.screenY(c.y), 0); up(board.screenX(c.x), board.screenY(c.y)) }

    @Test fun holdMenu() {
        val e = editorFor(11, "OR SR IL ..\nOR ST CB BL\nOR CL ST BT\n.. RT ST LT\n.. .. FT ..", 56f)
        compose.setContent { GameScreen(e, animate = false) }   // a still board, so the screen goes idle
        compose.runOnIdle { e.openMenu(Cell(2, 2)) }
        save("screen-hold-menu")
    }

    @Test fun selectAndMove() {
        val e = editorFor(4, "IR SL FL\n.. ST ..\n.. FT2 ..", 64f)
        compose.setContent { GameScreen(e, animate = false) }   // a still board, so the screen goes idle
        compose.runOnIdle {
            e.switchSelectMode(true)
            listOf(Cell(1, 0), Cell(1, 1), Cell(2, 0)).forEach { e.tapCell(it) }
            e.openMenu(Cell(1, 0)); e.menuMove()
            e.down(e.board.screenX(1), e.board.screenY(1), 0)
            e.drag(e.board.screenX(1), e.board.screenY(3))
        }
        save("screen-select-move")
    }

    @Test fun trayDragAndReadout() {
        val e = editorFor(0, "IR SL ..\n.. .. ..", 64f)
        compose.setContent { GameScreen(e, animate = false) }   // a still board, so the screen goes idle
        compose.runOnIdle {
            e.tapCell(Cell(1, 0))
            e.chipHover(TrayItem(Kind.OUT, 0), e.board.screenX(2), e.board.screenY(0))
        }
        save("screen-tray-drag")
    }
}
