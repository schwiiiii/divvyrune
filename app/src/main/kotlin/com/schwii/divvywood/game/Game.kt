package com.schwii.divvywood.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.schwii.divvywood.board.BoardState
import com.schwii.divvywood.board.Editor
import com.schwii.divvywood.board.Haptic
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.Level
import com.schwii.divvywood.engine.RANDOM
import com.schwii.divvywood.engine.randomLevel
import com.schwii.divvywood.engine.randomTarget
import com.schwii.divvywood.engine.Score
import com.schwii.divvywood.engine.Snapshot
import com.schwii.divvywood.engine.Verdict
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.engine.fitsLevel
import com.schwii.divvywood.engine.judge
import com.schwii.divvywood.engine.scoreOf

/** The sheet over the game, if any. */
sealed interface Sheet {
    /** A Check's verdict with the build's score and the level's best (updated if this run solved it). */
    class Result(val verdict: Verdict, val score: Score, val best: Best?, val next: Boolean) : Sheet
    data object Levels : Sheet
    data object Text : Sheet
}

/**
 * The game around the board: which level is open, its saved build and best scores, Check, the sheets and the hint.
 * Every change to the build is saved at once.
 */
class Game(private val saves: Saves, density: Float = 1f, haptic: (Haptic) -> Unit = {}) {
    /** The random level, last in the list after the fixed [LEVELS]. */
    var randomLevel by mutableStateOf(randomLevel(saves.randomTarget ?: randomTarget().also { saves.randomTarget = it })); private set
    val levelCount get() = LEVELS.size + 1
    val randomIndex get() = LEVELS.size
    fun levelAt(i: Int): Level = if (i < LEVELS.size) LEVELS[i] else randomLevel

    var levelIndex by mutableIntStateOf(if (saves.cur == RANDOM) LEVELS.size else LEVELS.indexOfFirst { it.name == saves.cur }.coerceAtLeast(0)); private set
    val level: Level get() = levelAt(levelIndex)

    val board = BoardState(level, saves.build(level.name), density)
    val editor = Editor(board, haptic)

    var sheet by mutableStateOf<Sheet?>(null); private set

    init {
        editor.onChange = { saves.putBuild(level.name, board.tiles) }
        editor.locked = { board.checking }
        board.onCheckDone = { w0, w1 -> finishCheck(w0, w1) }
    }

    fun best(i: Int) = saves.best(levelAt(i).name)

    /** Everything saved for level [i]. */
    fun saved(i: Int) = saves.level(levelAt(i).name)

    fun loadLevel(i: Int) {
        levelIndex = i
        saves.cur = levelAt(i).name
        sheet = null
        editor.reset()
        board.setBuild(levelAt(i), saves.build(levelAt(i).name))
        board.refit()
    }


    /** Gives the random level a new target, keeping its build, and opens it. */
    fun newTarget() {
        if (board.checking) return
        val f = randomTarget(not = randomLevel.out[0])
        saves.randomTarget = f
        saves.clearResults(RANDOM)
        randomLevel = randomLevel(f)
        loadLevel(randomIndex)
    }

    // ---------- check ----------

    /** Check: a build with structural problems gets its reasons at once; otherwise the simulation runs first. */
    fun startCheck() {
        if (board.checking) return
        editor.cancel()
        val pre = judge(board.graph, level, board.sol)
        if (pre.reasons.isNotEmpty() && (pre.rows.isEmpty() || !board.sol.ok)) { showResult(pre); return }
        board.startCheck()
    }

    private fun finishCheck(w0: Snapshot?, w1: Snapshot) = showResult(judge(board.graph, level, board.sol, w0, w1))

    private fun showResult(v: Verdict) {
        val score = scoreOf(board.tiles)
        val best = saves.recordCheck(level.name, score, v.solved)
        sheet = Sheet.Result(v, score, best, next = v.solved && levelIndex < LEVELS.lastIndex)   // Sandbox is never solved
    }

    // ---------- sheets ----------

    fun showLevels() { if (!board.checking) sheet = Sheet.Levels }
    fun showText() { sheet = Sheet.Text }
    fun closeSheet() { sheet = null }

    /** Builds from a text code in place of this level's build; returns an error message, or null when built. */
    fun buildFromText(text: String): String? {
        val d = decodeGrid(text)
        val tiles = d.tiles ?: return d.error
        fitsLevel(tiles, level)?.let { return it }
        editor.replaceAll(tiles)
        board.refit()
        sheet = null
        editor.say("Built from text")
        return null
    }
}
