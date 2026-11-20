package com.schwii.divvywood.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.schwii.divvywood.game.Game
import com.schwii.divvywood.game.MemoryStore
import com.schwii.divvywood.game.Saves
import com.schwii.divvywood.board.Editor
import com.schwii.divvywood.board.TrayItem
import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.levelNamed
import com.schwii.divvywood.engine.LEVELS
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

    private fun gameFor(level: Int, grid: String?, z: Float, hint: Boolean = false): Game {
        val g = Game(Saves(MemoryStore()), density = 3f)
        g.loadLevel(level)
        grid?.let { g.buildFromText(it); g.editor.dismissNotice() }
        g.board.z = z * 3f
        if (!hint) g.hideHint()
        return g
    }

    /** Runs the live simulation for about [ticks] ticks at top speed, as the frame clock would. */
    private fun Game.run(ticks: Int) { board.speed = 16; repeat(ticks / 9) { board.advance(100f) } }

    private fun Editor.tapCell(c: Cell) { down(board.screenX(c.x), board.screenY(c.y), 0); up(board.screenX(c.x), board.screenY(c.y)) }

    @Test fun holdMenu() {
        val g = gameFor(LEVELS.lastIndex, "OR SR IL ..\nOR ST CB BL\nOR CL ST BT\n.. RT ST LT\n.. .. FT ..", 56f)
        val e = g.editor
        compose.setContent { GameScreen(g, animate = false) }   // a still board, so the screen goes idle
        compose.runOnIdle { g.editor.openMenu(Cell(2, 2)) }
        save("screen-hold-menu")
    }

    @Test fun selectAndMove() {
        val g = gameFor(LEVELS.indexOf(levelNamed("Eighths")), "IR SL FL\n.. ST ..\n.. FT2 ..", 64f)
        val e = g.editor
        compose.setContent { GameScreen(g, animate = false) }   // a still board, so the screen goes idle
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
        val g = gameFor(0, "IR SL ..\n.. .. ..", 64f)
        val e = g.editor
        compose.setContent { GameScreen(g, animate = false) }   // a still board, so the screen goes idle
        compose.runOnIdle {
            e.tapCell(Cell(1, 0))
            e.chipHover(TrayItem(Kind.OUT, 0), e.board.screenX(2), e.board.screenY(0))
        }
        save("screen-tray-drag")
    }

    @Test fun readoutsAndHint() {
        val g = gameFor(LEVELS.indexOf(levelNamed("Eighths")), "IR SL FL\n.. ST ..\n.. FT2 ..", 64f, hint = true)
        g.run(900)
        compose.setContent { GameScreen(g, animate = false) }
        save("screen-readouts")
    }

    @Test fun solvedResult() {
        val g = gameFor(0, "IR SL FL\n.. FT2 ..", 64f)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.startCheck(); g.board.finishCheck() }
        save("screen-result-solved")
    }

    @Test fun notYetResult() {
        val g = gameFor(1, "IR SL FL\n.. FT2 ..", 64f)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.startCheck(); g.board.finishCheck() }
        save("screen-result-not-yet")
    }

    @Test fun checking() {
        val g = gameFor(LEVELS.indexOf(levelNamed("Eighths")), "IR SL FL\n.. ST ..\n.. FT2 ..", 64f)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.startCheck(); repeat(30) { g.board.advance(16f) } }
        save("screen-checking")
    }

    @Test fun levelsSheet() {
        val store = MemoryStore()
        Game(Saves(store)).apply {
            buildFromText("IR SL FL\n.. FT2 .."); startCheck(); board.finishCheck()
            loadLevel(LEVELS.indexOf(levelNamed("Eighths"))); buildFromText("IR SL FL\n.. ST ..\n.. FT2 ..")
        }
        val g = Game(Saves(store), density = 3f).apply { board.z = 64f * 3; hideHint() }
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.showLevels() }
        save("screen-levels")
    }

    @Test fun textSheet() {
        val g = gameFor(LEVELS.lastIndex, "OR SR IL ..\nOR ST CB BL\nOR CL ST BT\n.. RT ST LT\n.. .. FT ..", 56f)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.showText() }
        save("screen-text")
    }

    @Test fun randomLevel() {
        val store = MemoryStore()
        Saves(store).randomTarget = com.schwii.divvywood.engine.Frac(5, 13)
        val g = Game(Saves(store), density = 3f).apply { loadLevel(randomIndex); board.z = 64f * 3 }
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.showLevels() }
        save("screen-random-levels")
    }
}
