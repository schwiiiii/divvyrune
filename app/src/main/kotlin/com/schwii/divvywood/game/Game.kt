package com.schwii.divvywood.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.schwii.divvywood.board.BoardState
import com.schwii.divvywood.board.Editor
import com.schwii.divvywood.board.Haptic
import com.schwii.divvywood.engine.Frac
import com.schwii.divvywood.engine.Flow
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.findCycle
import com.schwii.divvywood.engine.flowOf
import com.schwii.divvywood.engine.toFrac
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.Level
import com.schwii.divvywood.engine.randomTarget
import com.schwii.divvywood.engine.Metrics
import com.schwii.divvywood.engine.Snapshot
import com.schwii.divvywood.engine.Verdict
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.engine.fitsLevel
import com.schwii.divvywood.engine.judge
import com.schwii.divvywood.engine.metricsOf

/** The sheet over the game, if any. */
sealed interface Sheet {
    /**
     * A Check's verdict with the build's score and the level's best (updated if this run solved it); [next] offers
     * the next level, [save] (in the builder, once solved) saving the design as a custom level.
     */
    class Result(val verdict: Verdict, val metrics: Metrics, val flow: Flow?, val best: Best?, val next: Boolean, val save: Boolean = false) : Sheet
    data object Levels : Sheet
    data object Text : Sheet
    /** The builder's settings: input rate, targets, 3-way splits. */
    data object Builder : Sheet
}

/**
 * The game around the board: which level is open, its saved build and best scores, Check, the sheets, and the
 * level builder. The levels are the numbered ones, then the player's custom levels, then the builder.
 * Every change to the build is saved at once.
 */
class Game(private val saves: Saves, density: Float = 1f, haptic: (Haptic) -> Unit = {}, private val baselines: Baselines = Baselines()) {
    var customs by mutableStateOf(saves.custom); private set
    var draft by mutableStateOf(saves.draft); private set
    private val builderLevel get() = draft.toLevel(BUILDER)

    /** Numbered and custom levels; the builder comes after them, at [builderIndex]. */
    val levelCount get() = LEVELS.size + customs.size
    val builderIndex get() = levelCount
    fun levelAt(i: Int): Level = when {
        i < LEVELS.size -> LEVELS[i]
        i < levelCount -> customs[i - LEVELS.size].def.toLevel(customs[i - LEVELS.size].name)
        else -> builderLevel
    }
    fun isCustom(i: Int) = i >= LEVELS.size && i < levelCount
    val inBuilder get() = levelIndex == builderIndex

    /** A level's label: 01–17, C1, C2… for custom levels. */
    fun label(i: Int) = when {
        i < LEVELS.size -> String.format(java.util.Locale.ROOT, "%02d", i + 1)
        i < levelCount -> "C${i - LEVELS.size + 1}"
        else -> "New level"
    }

    var levelIndex by mutableIntStateOf(
        when (saves.cur) {
            BUILDER -> LEVELS.size + saves.custom.size
            else -> (LEVELS.map { it.name } + saves.custom.map { it.name }).indexOf(saves.cur).coerceAtLeast(0)
        },
    ); private set
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

    /** Many solutions' metrics for level [i], if the solver has measured it. */
    fun baseline(i: Int): LevelBaseline? = baselines[levelAt(i).name]

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


    // ---------- builder ----------

    fun openBuilder() = loadLevel(builderIndex)

    /**
     * Changes the builder's definition. The build stays, except inputs and outputs the new definition no longer
     * has; any earlier pass no longer counts.
     */
    fun updateDraft(def: LevelDef) {
        draft = def
        saves.draft = def
        saves.clearResults(BUILDER)
        if (!inBuilder) return
        board.tiles.entries.removeAll { (_, t) -> (t.kind == Kind.OUT && t.id >= def.out.size) || (t.kind == Kind.SRC && maxOf(t.id, 0) >= def.inputs.size) }
        board.setBuild(builderLevel, board.tiles)
        saves.putBuild(BUILDER, board.tiles)
    }

    /** A random single target the builder can always meet, with bypasses taking the rest. */
    fun surpriseMe() {
        val half = Frac(1, 2)   // the targets are proved solvable with the input at half a belt
        val f = randomTarget(not = draft.targets.singleOrNull()?.let { (it.q / half.q).toFrac() })
        updateDraft(LevelDef(listOf(half.toString()), listOf((f * half).toString())))
    }

    /** Saves the builder's design as a custom level, once its current build has passed Check. */
    fun saveCustom(): CustomLevel? {
        if (!inBuilder || saves.level(BUILDER).solution == null) return null
        val c = saves.addCustom(draft, saves.level(BUILDER).build)
        customs = saves.custom
        levelIndex = builderIndex   // the builder moves along one place as the list grows
        sheet = null
        editor.say("Saved as ${label(levelCount - 1)}")
        return c
    }

    /** Deletes custom level [i]; if it was open, level 1 opens. */
    fun deleteCustom(i: Int) {
        if (!isCustom(i)) return
        val wasOpen = i == levelIndex
        val builder = inBuilder
        saves.deleteCustom(customs[i - LEVELS.size].id)
        customs = saves.custom
        when {
            wasOpen -> loadLevel(0)
            builder -> levelIndex = builderIndex
            levelIndex > i -> levelIndex--
        }
        sheet = if (sheet == Sheet.Levels) Sheet.Levels else sheet
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
        val m = metricsOf(board.tiles)
        // a build that runs gets its flow measured over one loop; one that can't run has none
        val cycle = if (board.sol.ok && board.graph.errCount == 0) findCycle(board.graph, level) else null
        val flow = cycle?.let { flowOf(board.graph, level, it) }
        val best = saves.recordCheck(level.name, m, flow?.score, v.solved)
        sheet = Sheet.Result(v, m, flow, best, next = v.solved && levelIndex < LEVELS.lastIndex, save = v.solved && inBuilder)
    }

    // ---------- sheets ----------

    fun showLevels() { if (!board.checking) sheet = Sheet.Levels }
    fun showText() { sheet = Sheet.Text }
    fun showBuilder() { if (inBuilder && !board.checking) sheet = Sheet.Builder }
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
