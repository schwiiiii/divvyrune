package com.schwii.divvyrune.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.schwii.divvyrune.board.BoardState
import com.schwii.divvyrune.board.Editor
import com.schwii.divvyrune.board.Haptic
import com.schwii.divvyrune.engine.Frac
import com.schwii.divvyrune.engine.Flow
import com.schwii.divvyrune.engine.Kind
import com.schwii.divvyrune.engine.findCycle
import com.schwii.divvyrune.engine.flowOf
import com.schwii.divvyrune.engine.toFrac
import com.schwii.divvyrune.engine.LEVELS
import com.schwii.divvyrune.engine.Level
import com.schwii.divvyrune.engine.randomTarget
import com.schwii.divvyrune.engine.Metrics
import com.schwii.divvyrune.engine.Snapshot
import com.schwii.divvyrune.engine.Verdict
import com.schwii.divvyrune.engine.decodeGrid
import com.schwii.divvyrune.engine.fitsLevel
import com.schwii.divvyrune.engine.judge
import com.schwii.divvyrune.engine.metricsOf
import com.schwii.divvyrune.engine.Cell
import com.schwii.divvyrune.engine.Tile
import com.schwii.divvyrune.engine.buildGraph
import com.schwii.divvyrune.engine.machinesUsed
import com.schwii.divvyrune.wood.Look
import com.schwii.divvyrune.sound.Recipe
import com.schwii.divvyrune.sound.Score
import com.schwii.divvyrune.sound.SoundOut

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
    /** The machines to place: the best builds of solved levels. */
    data object Machines : Sheet
    /** The app's settings: the look and the sound. */
    data object Settings : Sheet
    /** The runes turning under the sound, to hold or let turn. */
    data object Runes : Sheet
}

/**
 * The game around the board: which level is open, its saved build and best scores, Check, the sheets, and the
 * level builder. The levels are the numbered ones, then the player's custom levels, then the builder.
 * Every change to the build is saved at once.
 */
class Game(
    private val saves: Saves, density: Float = 1f, haptic: (Haptic) -> Unit = {}, private val baselines: Baselines = Baselines(),
    private val out: SoundOut? = null,
) {
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

    val board = BoardState(level, saves.build(level.name), density, saves.lib).also { b ->
        b.outdated = { outdated(it) }
        b.levelLabel = { h -> saves.machine(h)?.level?.let { name -> (0 until levelCount).firstOrNull { levelAt(it).name == name } }?.let { label(it) } }
        b.levelOf = { h -> saves.machine(h)?.level?.let { name -> (0 until levelCount).firstOrNull { levelAt(it).name == name } }?.let { levelAt(it) } }
    }
    val editor = Editor(board, haptic)

    var sheet by mutableStateOf<Sheet?>(null); private set

    /** The sound: the live build's score, played while the app is in front and the sound is on. */
    val score = Score()
    private var resumed = false

    init {
        Look.wire = saves.look == "wire"
        editor.onChange = { saves.putBuild(level.name, board.tiles) }
        editor.locked = { board.checking }
        board.onCheckDone = { w0, w1 -> finishCheck(w0, w1) }
        score.setHolds(saves.runes(level.name))
        score.onHolds = { saves.putRunes(level.name, it) }
        board.onLiveTick = { tickSound() }
    }

    /** One tick of live play, heard: quietly thinner at ×4, silent at ×16. */
    private fun tickSound() {
        val notes = score.tick(board.sim, board.graph, board.level, board.cycles.cycle, levelIndex + 1, LEVELS.size,
            { board.exitTag(it).second != com.schwii.divvyrune.wood.ExitTone.BAD }, Look.wire,
            1f / (BoardState.TICKS_PER_SECOND * board.speed), if (board.speed > 1f) 3 else Recipe.MAX_VOICES)
        val heard = soundOn && board.speed < 16f
        if (heard) board.flash(score.flashes)   // only what's heard flashes
        val synth = out?.synth ?: return
        if (heard) synth.play(notes, score.mix) else synth.steer(score.mix)
    }

    /** Whether the game plays its sound; saved. */
    var soundOn by mutableStateOf(saves.sound); private set
    fun switchSound(on: Boolean) { soundOn = on; saves.sound = on; if (on && resumed) out?.start() else out?.stop() }

    /** The app came to the front: the sound starts, if it's on. */
    fun resume() { resumed = true; if (soundOn) out?.start() }
    /** The app went to the back: the sound stops. */
    fun pause() { resumed = false; out?.stop() }

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
        score.setHolds(saves.runes(levelAt(i).name))
        board.setBuild(levelAt(i), saves.build(levelAt(i).name))
        board.refit()
    }


    // ---------- builder ----------

    fun openBuilder() = loadLevel(builderIndex)

    /**
     * Changes the builder's definition. The build stays, except inputs the new definition no longer has; any earlier
     * pass no longer counts.
     */
    fun updateDraft(def: LevelDef) {
        draft = def
        saves.draft = def
        saves.clearResults(BUILDER)
        if (!inBuilder) return
        board.tiles.entries.removeAll { (_, t) -> t.kind == Kind.SRC && maxOf(t.id, 0) >= def.inputs.size }
        board.setBuild(builderLevel, board.tiles)
        saves.putBuild(BUILDER, board.tiles)
    }

    /** A random single target the builder can always meet, the rest leaving on a side of its own. */
    fun surpriseMe() {
        val f = randomTarget(not = draft.targets.singleOrNull())
        updateDraft(LevelDef(listOf("1/1"), listOf(f.toString())))
    }

    /** Saves the builder's design as a custom level, once its current build has passed Check. */
    fun saveCustom(): CustomLevel? {
        if (!inBuilder || saves.level(BUILDER).solution == null || draft.problem() != null) return null
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
        updateMachines()
        val pre = judge(board.graph, level, board.sol)
        if (pre.reasons.isNotEmpty() && (pre.rows.isEmpty() || !board.sol.ok)) { showResult(pre); return }
        board.startCheck()
    }

    private fun finishCheck(w0: Snapshot?, w1: Snapshot) = showResult(judge(board.graph, level, board.sol, w0, w1))

    private fun showResult(v: Verdict) {
        val m = metricsOf(board.tiles, board.lib)
        // a build that runs gets its flow measured over one loop; one that can't run has none
        val cycle = if (board.sol.ok && board.graph.errCount == 0) findCycle(board.graph, level) else null
        val flow = cycle?.let { flowOf(board.graph, level, it) }
        val best = saves.recordCheck(level.name, m, flow?.score, v.solved)
        if (v.solved) board.lib = saves.lib
        sheet = Sheet.Result(v, m, flow, best, next = v.solved && levelIndex < LEVELS.lastIndex, save = v.solved && inBuilder && draft.problem() == null)
    }

    // ---------- sheets ----------

    fun showLevels() { if (!board.checking) sheet = Sheet.Levels }
    fun showText() { sheet = Sheet.Text }
    fun showBuilder() { if (inBuilder && !board.checking) sheet = Sheet.Builder }
    fun showMachines() { if (!board.checking) sheet = Sheet.Machines }
    fun showSettings() { sheet = Sheet.Settings }
    fun showRunes() { sheet = Sheet.Runes }

    /** Wireframe (white lines on dark) or wood; saved. */
    var wire: Boolean
        get() = Look.wire
        set(v) { Look.wire = v; saves.look = if (v) "wire" else "wood"; board.rebuild() }
    fun closeSheet() { sheet = null }

    /** Builds from a text code in place of this level's build; returns an error message, or null when built. */
    fun buildFromText(text: String): String? {
        val d = decodeGrid(text)
        val tiles = d.tiles ?: return d.error
        fitsLevel(tiles, level)?.let { return it }
        val known = saves.lib.defs.keys + d.machines.keys
        tiles.values.firstOrNull { it.kind == Kind.MACHINE && it.ref !in known }?.let { return "The text uses machine #${it.ref} without its build." }
        saves.addMachines(d.machines)
        board.lib = saves.lib
        editor.replaceAll(tiles)
        board.refit()
        sheet = null
        editor.say("Built from text")
        return null
    }

    // ---------- machines ----------

    /** A machine to place: its name, the level it solves, what it's best at there, and its build. */
    class MachineChoice(val hash: String, val level: Int, val best: List<String>, val tiles: Map<Cell, Tile>)

    /**
     * The machines this level may use: each other solved level's best builds (one per metric, the same build once),
     * leaving out any that hold this level's own machine, and any whose ports don't line up across the middle.
     */
    fun machineChoices(): List<MachineChoice> {
        val lib = board.lib
        return (0 until levelCount).filter { it != levelIndex }.flatMap { i ->
            val b = saves.best(levelAt(i).name) ?: return@flatMap emptyList()
            b.machines.mapNotNull { (h, what) ->
                val t = lib.tiles(h) ?: return@mapNotNull null
                if ((machinesUsed(t, lib) + h).any { lib[it]?.level == level.name }) return@mapNotNull null
                if (!com.schwii.divvyrune.engine.portsAligned(t, lib)) return@mapNotNull null   // its ports must line up
                MachineChoice(h, i, what, t)
            }
        }
    }

    /** Puts machine [hash] in the tray, ready to drag onto the board. */
    fun pickMachine(hash: String) {
        editor.hold(hash)
        sheet = null
        editor.say("Drag the machine onto the board")
    }

    /** Is machine [hash] an older build of its level, no longer one of its bests? */
    fun outdated(hash: String): Boolean {
        val m = saves.machine(hash) ?: return false
        val b = saves.best(m.level ?: return false) ?: return false
        return b.machines.none { it.first == hash }
    }

    /**
     * Before a Check, each outdated machine moves to the build that is now its level's best at what it was best at,
     * when that build has the same sides; one whose sides changed stays as it was.
     */
    private fun updateMachines() {
        val lib = board.lib
        fun sides(h: String) = lib.tiles(h)?.let { t ->
            buildGraph(t, Level("m", List(t.values.count { it.kind == Kind.SRC }) { Frac(1, 1) }, emptyList()), lib).ports
                .map { it.side to it.isInput }.toSet()
        }
        val swaps = board.tiles.filterValues { it.kind == Kind.MACHINE && it.ref != null && outdated(it.ref!!) }.mapNotNull { (c, t) ->
            val m = saves.machine(t.ref!!) ?: return@mapNotNull null
            val b = saves.best(m.level!!) ?: return@mapNotNull null
            val now = when (m.best.firstOrNull()) { "smallest area" -> b.areaBuild; "best flow" -> b.flowBuild; else -> b.piecesBuild } ?: return@mapNotNull null
            if (sides(now) != sides(t.ref!!)) null else c to t.copy(ref = now)
        }
        if (swaps.isEmpty()) return
        editor.edit { swaps.forEach { (c, t) -> board.tiles[c] = t }; true }
        editor.say(if (swaps.size == 1) "A machine moved to its level's newer build" else "${swaps.size} machines moved to their levels' newer builds")
    }
}
