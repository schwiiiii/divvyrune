package com.schwii.divvywood

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.schwii.divvywood.board.BoardRenderer
import com.schwii.divvywood.board.BoardState
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.engine.levelNamed
import com.schwii.divvywood.game.Game
import com.schwii.divvywood.game.MemoryStore
import com.schwii.divvywood.game.Saves
import com.schwii.divvywood.ui.GameScreen
import com.schwii.divvywood.wood.Fonts
import com.schwii.divvywood.wood.Painter
import com.schwii.divvywood.wood.Palette
import com.schwii.divvywood.wood.angleOf
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

    /**
     * The icon on a canvas [px] square that shows [viewDp] of the 108 dp adaptive-icon square: the linen table
     * ([table]) and a walnut 3-way splitter block with its flipper ([block]), 52 dp across.
     */
    private fun icon(px: Int, viewDp: Float, table: Boolean, block: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val dp = px / viewDp
        val z = 52f * dp / 0.95f   // the painter draws a block 0.95 of its cell
        val x = px / 2f; val y = px / 2f
        val p = Painter()
        if (table) p.table(c, px.toFloat(), px.toFloat(), x - px, y - px, px.toFloat())   // grid dots fall on the edges
        if (block) {
            p.blockShadow(c, x, y, z)
            // on the board channels run on into the next block; on the icon they stop at the block's edge
            val half = z * 0.475f
            c.save(); c.clipPath(android.graphics.Path().apply { addRoundRect(x - half, y - half, x + half, y + half, z * 0.07f, z * 0.07f, android.graphics.Path.Direction.CW) })
            p.tile(c, x, y, z, Tile(Kind.SPLIT, inSide = 2), listOf(2), listOf(3, 0, 1), 905)
            c.restore()
            p.flipper(c, x, y, z, angleOf(3))
        }
        return bmp
    }

    @Test fun launcherIcon() {
        for ((dir, scale) in listOf("mdpi" to 1f, "hdpi" to 1.5f, "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f)) {
            val px = (108 * scale).toInt()
            icon(px, 108f, table = false, block = true).saveTo(File(res, "mipmap-$dir/ic_launcher_foreground.png"))
            icon(px, 108f, table = true, block = false).saveTo(File(res, "mipmap-$dir/ic_launcher_background.png"))
        }
        // the store icon shows the 72 dp an adaptive icon's mask keeps
        icon(512, 72f, table = true, block = true).saveTo(File(store, "icon.png"))
    }

    // ---------- feature graphic ----------

    @Test fun featureGraphic() {
        val w = 1024; val h = 500
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tiles = decodeGrid(".. .. FB3 FB2 FB\nIR CR SL SL SL\n.. RT RL RL TL").tiles!!
        val state = BoardState(levelNamed("One two four"), tiles, 2f).apply { z = 88f; speed = 16 }
        state.fit(w.toFloat(), h.toFloat())
        state.ox = 44f
        repeat(220) { state.step() }
        state.advance(0f)
        BoardRenderer(state, reduceMotion = true).draw(c, w, h)
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

    private fun game(level: String, build: String?, z: Float, hint: Boolean, saves: Saves = Saves(MemoryStore())): Game {
        val g = Game(saves, density = 3f)
        g.loadLevel(LEVELS.indexOf(levelNamed(level)))
        build?.let { g.buildFromText(it); g.editor.dismissNotice() }
        g.board.z = z * 3f
        if (!hint) g.hideHint()
        return g
    }

    /** Runs the live simulation for about [ticks] ticks, then on to a moment the input isn't held up. */
    private fun Game.run(ticks: Int) {
        board.speed = 16
        repeat(ticks / 9) { board.advance(100f) }
        var guard = 0
        while (board.inputBlocked && guard++ < 200) board.advance(100f)   // some loops hold the input up often
    }

    private fun Game.tap(x: Int, y: Int) { editor.down(board.screenX(x), board.screenY(y), 0); editor.up(board.screenX(x), board.screenY(y)) }

    private val oneTwoFour = ".. .. FB3 FB2 FB\nIR CR SL SL SL\n.. RT RL RL TL"
    private val thirtieth = "OR SR IL ..\nOR ST CB BL\nOR CL ST BT\n.. RT ST LT\n.. .. FT .."

    @Test fun screenshot1Running() {
        val g = game("One two four", oneTwoFour, 60f, hint = true).apply { run(900) }
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.tap(4, 0) }
        shoot(1)
    }

    @Test fun screenshot2Solved() {
        val g = game("One two four", oneTwoFour, 60f, hint = false)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.startCheck(); g.board.finishCheck() }
        shoot(2)
    }

    @Test fun screenshot3Thirtieth() {
        val g = game("Thirtieth", thirtieth, 62f, hint = true).apply { run(1200) }
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
        val g = game("Eighths", null, 60f, hint = true, saves = saves)
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.showLevels() }
        shoot(4)
    }

    @Test fun screenshot5Text() {
        val g = game("Ninths", ".. .. IB FB .. ..\nFR3 CL ST SL LB ..\n.. RT TL TR CR FL2", 54f, hint = false).apply { run(300) }
        compose.setContent { GameScreen(g, animate = false) }
        compose.runOnIdle { g.showText() }
        shoot(5)
    }
}
