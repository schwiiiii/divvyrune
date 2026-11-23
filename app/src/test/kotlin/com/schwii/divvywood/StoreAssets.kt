package com.schwii.divvywood

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.schwii.divvywood.board.BoardRenderer
import com.schwii.divvywood.board.BoardState
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.engine.levelNamed
import com.schwii.divvywood.game.Game
import com.schwii.divvywood.game.MemoryStore
import com.schwii.divvywood.game.Saves
import com.schwii.divvywood.ui.GameScreen
import com.schwii.divvywood.wood.Fonts
import com.schwii.divvywood.wood.Painter
import com.schwii.divvywood.wood.Palette
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the launcher icon, the store icon, the feature graphic and the store screenshots with the game's own
 * renderer. Runs only when asked: `DIVVYWOOD_ASSETS=1 gradle :app:testDebugUnitTest --tests '*StoreAssets*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class StoreAssets {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val res = File("src/main/res")
    private val store = File("../fastlane/metadata/android/en-US/images")

    @Before fun onlyWhenAsked() {
        assumeTrue("set DIVVYWOOD_ASSETS=1 to draw the store assets", System.getenv("DIVVYWOOD_ASSETS") != null)
        Fonts.load(RuntimeEnvironment.getApplication())
    }

    private fun Bitmap.saveTo(f: File) {
        f.parentFile!!.mkdirs()
        f.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------- icon ----------

    /** Brett's logo build: the input drops into a converger, a splitter loops back to it and feeds three cups. */
    private val logo = "..,IB,..,..,../BR,CB,FB,FB2,FB3/RT,ST,SL,SL,LT"

    /** The logo build on the board as the game draws it, [cell] pixels a cell, centred in [w] × [h], marbles rolling. */
    private fun board(w: Int, h: Int, cell: Float, dx: Float = 0f): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val level = com.schwii.divvywood.engine.Level("logo", 2, listOf(null, null, null), 0)
        val state = BoardState(level, decodeGrid(logo).tiles!!, 1f).apply { z = cell }
        state.fit(w.toFloat(), h.toFloat())
        state.ox += dx
        repeat(41) { state.step() }
        state.advance(0f)
        BoardRenderer(state, reduceMotion = true).draw(Canvas(bmp), w, h)
        return bmp
    }

    /** The icon on a canvas [px] square that shows [viewDp] of the 108 dp adaptive-icon square; the build spans 62 dp. */
    private fun icon(px: Int, viewDp: Float): Bitmap = board(px, px, 62f / 5 * px / viewDp)

    /** The bare linen table, for the adaptive icon's back layer. */
    private fun table(px: Int): Bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
        Painter().table(Canvas(it), px.toFloat(), px.toFloat(), -px.toFloat(), -px.toFloat(), px.toFloat() * 2)
    }

    @Test fun launcherIcon() {
        for ((dir, scale) in listOf("mdpi" to 1f, "hdpi" to 1.5f, "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f)) {
            val px = (108 * scale).toInt()
            icon(px, 108f).saveTo(File(res, "mipmap-$dir/ic_launcher_foreground.png"))
            table(px).saveTo(File(res, "mipmap-$dir/ic_launcher_background.png"))
        }
        // the store icon shows the 72 dp an adaptive icon's mask keeps
        icon(512, 72f).saveTo(File(store, "icon.png"))
    }

    // ---------- feature graphic ----------

    @Test fun featureGraphic() {
        val w = 1024; val h = 500
        val bmp = board(w, h, 92f, dx = -250f)
        val c = Canvas(bmp)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.label; Fonts.labelVariation?.let { fontVariationSettings = it }
            color = Palette.ink; textSize = 84f; textAlign = Paint.Align.RIGHT
        }
        c.drawText("Divvywood", w - 48f, h / 2f + 6f, title)
        title.apply { textSize = 30f; color = Palette.dim; fontVariationSettings = "'wght' 600" }
        c.drawText("Marbles, split exactly", w - 50f, h / 2f + 56f, title)
        bmp.saveTo(File(store, "featureGraphic.png"))
    }

    // ---------- screenshots ----------

    private fun shoot(n: Int) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        bmp.saveTo(File(store, "phoneScreenshots/$n.png"))
    }

    private fun game(level: String, build: String?, z: Float, saves: Saves = Saves(MemoryStore())): Game {
        val g = Game(saves, density = 3f)
        g.loadLevel(LEVELS.indexOf(levelNamed(level)))
        build?.let { g.buildFromText(it); g.editor.dismissNotice() }
        g.board.z = z * 3f
        return g
    }

    /** Runs the live simulation for about [ticks] ticks, then on to a moment the input isn't held up. */
    private fun Game.run(ticks: Int) {
        board.speed = 16
        repeat(ticks / 9) { board.advance(100f) }
        var guard = 0
        while (board.inputBlocked && guard++ < 200) board.advance(100f)   // some loops hold the input up often
    }

    private fun Game.tap(x: Int, y: Int) { editor.down(board.screenX(x), board.screenY(y)); editor.up(board.screenX(x), board.screenY(y)) }

    private val oneTwoFour = ".. .. FB3 FB2 FB\nIR CR SL SL SL\n.. RT RL RL TL"
    private val ninths = ".. .. IB FB .. ..\nFR3 CL ST SL LB ..\n.. RT TL TR CR FL2"
    private val thirtieth = "OR SR IL ..\nOR ST CB BL\nOR CL ST BT\n.. RT ST LT\n.. .. FT .."

    @Test fun screenshot1Running() {
        val g = game("Ninths", ninths, 60f).apply { run(900) }
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.tap(3, 0) }
        shoot(1)
    }

    @Test fun screenshot2Solved() {
        val g = game("One two four", oneTwoFour, 60f)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.startCheck(); g.board.finishCheck() }
        shoot(2)
    }

    @Test fun screenshot3Thirtieth() {
        val g = game("Thirtieth", thirtieth, 62f).apply { run(1200) }
        compose.setContent { GameScreen(g, animate = false) }
        shoot(3)
    }

    @Test fun screenshot4Levels() {
        val saves = Saves(MemoryStore())
        for ((name, build) in listOf(
            "Halves" to "IR SL FL\n.. FT2 ..",
            "Three ways" to ".. FB ..\nIR SL FL2\n.. FT3 ..",
            "One two four" to oneTwoFour,
        )) Game(saves).apply { loadLevel(LEVELS.indexOf(levelNamed(name))); buildFromText(build); startCheck(); board.finishCheck() }
        val g = game("Eighths", null, 60f, saves = saves)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.showLevels() }
        shoot(4)
    }

    @Test fun screenshot5Text() {
        val g = game("One two four", oneTwoFour, 60f)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.showText() }
        shoot(5)
    }
}
