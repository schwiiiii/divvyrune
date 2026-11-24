package com.schwii.divvywood.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.schwii.divvywood.game.Baselines
import com.schwii.divvywood.game.Game
import com.schwii.divvywood.game.MemoryStore
import com.schwii.divvywood.game.LevelDef
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

    private fun gameFor(level: Int, grid: String?, z: Float): Game {
        val g = Game(Saves(MemoryStore()), density = 3f)
        g.loadLevel(level)
        grid?.let { g.buildFromText(it); g.editor.dismissNotice() }
        g.board.z = z * 3f
        return g
    }

    /** Runs the live simulation for about [ticks] ticks at top speed, as the frame clock would. */
    private fun Game.run(ticks: Int) { board.speed = 16; repeat(ticks / 9) { board.advance(100f) } }

    private fun Editor.tapCell(c: Cell) { down(board.screenX(c.x), board.screenY(c.y)); up(board.screenX(c.x), board.screenY(c.y)) }

    @Test fun startCell() {
        // an empty board: the dashed start cell, with the input dropped above it facing down into it
        val g = gameFor(0, null, 64f)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.board.refit(); g.editor.chipDrop(TrayItem(Kind.SRC), g.board.screenX(0), g.board.screenY(-3)) }
        save("screen-start-cell")
    }

    @Test fun selectedPiece() {
        val g = gameFor(LEVELS.lastIndex, ".. .. .. .. IB ..\n.. BR CB5 RL TL ..\nRB SR6 ST7 SL6 LB ..\nTB .. CB6 TL TB ..\nTR CB5 CL3 SR3 TL ..\n.. CB6 RL ST7 LR LR\n.. CB6 RL TL .. ..", 56f)
        val e = g.editor
        compose.setContent { GameScreen(g, animate = false) }   // a still board, so the screen goes idle
        compose.runOnIdle { g.editor.tapCell(Cell(2, 2)) }
        save("screen-selected")
    }

    @Test fun selectAndMove() {
        val g = gameFor(LEVELS.indexOf(levelNamed("Eighths")), "BT .. .. .. .. ..\nBT .. .. BR LR LR\nBT RB SB5 CT6 .. ..\nRT CL3 SR6 SR6 BL ..\n.. .. .. .. IT ..", 64f)
        val e = g.editor
        compose.setContent { GameScreen(g, animate = false) }   // a still board, so the screen goes idle
        compose.runOnIdle {
            e.switchSelectMode(true)
            listOf(Cell(2, 2), Cell(2, 3), Cell(3, 3)).forEach { e.tapCell(it) }
            e.down(e.board.screenX(2), e.board.screenY(3))
            e.drag(e.board.screenX(2), e.board.screenY(5))
        }
        save("screen-select-move")
    }

    @Test fun groupSelected() {
        val g = gameFor(LEVELS.indexOf(levelNamed("Eighths")), "BT .. .. .. .. ..\nBT .. .. BR LR LR\nBT RB SB5 CT6 .. ..\nRT CL3 SR6 SR6 BL ..\n.. .. .. .. IT ..", 64f)
        val e = g.editor
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { e.switchSelectMode(true); listOf(Cell(2, 2), Cell(2, 3), Cell(3, 3)).forEach { e.tapCell(it) } }
        save("screen-group-selected")
    }

    @Test fun trayDragAndReadout() {
        val g = gameFor(0, "IR LR SL6", 64f)
        val e = g.editor
        compose.setContent { GameScreen(g, animate = false) }   // a still board, so the screen goes idle
        compose.runOnIdle {
            e.tapCell(Cell(2, 0))
            e.chipHover(TrayItem(Kind.BRIDGE), e.board.screenX(1), e.board.screenY(0))
        }
        save("screen-tray-drag")
    }

    @Test fun readouts() {
        val g = gameFor(LEVELS.indexOf(levelNamed("Eighths")), "BT .. .. .. .. ..\nBT .. .. BR LR LR\nBT RB SB5 CT6 .. ..\nRT CL3 SR6 SR6 BL ..\n.. .. .. .. IT ..", 64f)
        g.run(900)
        compose.setContent { GameScreen(g, animate = false) }
        save("screen-readouts")
    }

    @Test fun solvedResult() {
        // the solver's baselines, as the app ships them
        val base = Baselines.load(compose.activity)
        val g = Game(Saves(MemoryStore()), density = 3f, baselines = base).apply { buildFromText(".. .. IB ..\nRL BL TB ..\n.. CT3 ST6 ..\n.. RT ST6 ..\n.. .. TR LR"); editor.dismissNotice(); board.z = 64f * 3 }
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.startCheck(); g.board.finishCheck() }
        save("screen-result-solved")
        compose.runOnIdle { g.closeSheet(); g.board.playing = false; repeat(13) { g.board.step() } }
        save("screen-paused-step")
    }

    @Test fun notYetResult() {
        val g = gameFor(1, "IR LR SL5", 64f)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.startCheck(); g.board.finishCheck() }
        save("screen-result-not-yet")
    }

    @Test fun checking() {
        val g = gameFor(LEVELS.indexOf(levelNamed("Eighths")), "BT .. .. .. .. ..\nBT .. .. BR LR LR\nBT RB SB5 CT6 .. ..\nRT CL3 SR6 SR6 BL ..\n.. .. .. .. IT ..", 64f)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.startCheck(); repeat(30) { g.board.advance(16f) } }
        save("screen-checking")
    }

    @Test fun levelsSheet() {
        val store = MemoryStore()
        Game(Saves(store)).apply {
            buildFromText(".. .. IB ..\nRL BL TB ..\n.. CT3 ST6 ..\n.. RT ST6 ..\n.. .. TR LR"); startCheck(); board.finishCheck()
            loadLevel(LEVELS.indexOf(levelNamed("Sixth"))); buildFromText("IR LR SL5"); startCheck(); board.finishCheck()
            loadLevel(LEVELS.indexOf(levelNamed("Eighths"))); buildFromText("BT .. .. .. .. ..\nBT .. .. BR LR LR\nBT RB SB5 CT6 .. ..\nRT CL3 SR6 SR6 BL ..\n.. .. .. .. IT ..")
        }
        val g = Game(Saves(store), density = 3f).apply { board.z = 64f * 3 }
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.showLevels() }
        save("screen-levels")
    }

    @Test fun textSheet() {
        val g = gameFor(LEVELS.lastIndex, ".. .. .. .. IB ..\n.. BR CB5 RL TL ..\nRB SR6 ST7 SL6 LB ..\nTB .. CB6 TL TB ..\nTR CB5 CL3 SR3 TL ..\n.. CB6 RL ST7 LR LR\n.. CB6 RL TL .. ..", 56f)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.showText() }
        save("screen-text")
    }

    @Test fun builderAndCustomLevels() {
        val store = MemoryStore()
        Game(Saves(store)).apply {
            openBuilder(); updateDraft(LevelDef(listOf("1/2"), listOf("1/8", "3/8"))); buildFromText(".. .. IB ..\nRL BL TB ..\n.. CT3 ST6 ..\n.. RT ST6 ..\n.. .. TR LR"); startCheck(); board.finishCheck(); saveCustom()
        }
        val g = Game(Saves(store), density = 3f).apply { board.z = 64f * 3 }
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.updateDraft(LevelDef(listOf("1/3", "1/4"), listOf("1/5", "2/5"), no3 = true)); g.showBuilder() }
        save("screen-builder")
        compose.runOnIdle { g.showLevels() }
        save("screen-custom-levels")
    }

    @Test fun bottleneckWhilePaused() {
        // a loop into the converger at the input: at full belt there's no room, so marbles wait and the input is held up
        val g = gameFor(LEVELS.indexOf(levelNamed("Full belt")), "IR CR SL6 SL6\n.. RT TL ..", 64f)
        g.run(240)
        g.board.playing = false
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.board.stepOnce(); repeat(30) { g.board.advance(16f) } }
        compose.runOnIdle { g.editor.tapCell(Cell(1, 0)) }
        save("screen-bottleneck")
    }

    @Test fun twoInputs() {
        val g = gameFor(LEVELS.indexOf(levelNamed("Swap")), ".. .. BT .. .. ..\n.. .. RT CL6 BL ..\nIR LR LR LT BT ..\n.. .. RB SB5 LT ..\n.. RB CL3 SB3 .. ..\n.. TB .. RT RL IL2\n.. TB .. .. .. ..", 60f)
        g.run(600)
        compose.setContent { GameScreen(g, animate = false) }
        save("screen-two-inputs")
    }
}
