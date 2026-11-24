package com.schwii.divvywood.game

import android.content.Context
import androidx.core.content.edit
import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Frac
import com.schwii.divvywood.engine.Level
import com.schwii.divvywood.engine.MAX_PORTS
import com.schwii.divvywood.engine.Q
import com.schwii.divvywood.engine.Metrics
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.Tiles
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.engine.encodeGrid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A level's best solves, each metric on its own (they can come from different builds): fewest [pieces], smallest
 * footprint [area], highest [flow]. -1 where none is known yet.
 */
@Serializable data class Best(val pieces: Int = -1, val area: Int = -1, val flow: Int = -1) {
    fun with(m: Metrics, f: Int?) = Best(
        if (pieces < 0) m.pieces else minOf(pieces, m.pieces),
        if (area < 0) m.area else minOf(area, m.area),
        if (f == null) flow else maxOf(flow, f),
    )
}

/** The build's metrics at its last Check, solved or not; [flow] -1 when the build couldn't run. */
@Serializable data class Checked(val pieces: Int = -1, val area: Int = -1, val solved: Boolean = false, val flow: Int = -1)

/**
 * A level's build, as its text code; its best scores and the build that last solved it, once solved; and the
 * last Check's score.
 */
@Serializable data class LevelSave(
    val build: String = "",
    val best: Best? = null,
    val solution: String? = null,
    val checked: Checked? = null,
)

/**
 * A level a player designs in the builder. Every rate is a share of one full belt, as "p/q": each input's rate in
 * [inputs], each final output's target in [out]; [no3] turns 3-way splits off.
 */
@Serializable data class LevelDef(
    val inputs: List<String> = listOf("1/2"),
    val out: List<String> = listOf("1/8", "3/8"),
    val no3: Boolean = false,
) {
    val rates: List<Frac> get() = inputs.mapNotNull { parseFrac(it) }
    val targets: List<Frac> get() = out.mapNotNull { parseFrac(it) }
    fun toLevel(name: String) = Level(name, rates, targets, no3)

    /**
     * Why this design can't be a level, if it can't: the targets can't ask for more than the inputs send, what they
     * leave over must fit on one belt, it needs a side of the build for every port, and one piece mustn't meet it.
     */
    fun problem(): String? {
        val level = toLevel("draft")
        val want = targets.fold(Q.ZERO) { a, f -> a + f.q }
        val have = rates.fold(Q.ZERO) { a, f -> a + f.q }
        return when {
            want > have -> "The targets add up to $want of a belt, more than the inputs send ($have)."
            level.rest > Q.ONE -> "The outputs leave ${level.rest} of a belt over, more than one belt can carry out."
            level.ports > MAX_PORTS -> "A build has $MAX_PORTS sides, one per input or output" +
                (if (level.rest > Q.ZERO) ", and one more for what the outputs leave over" else "") + ". This design needs ${level.ports}."
            level.onePiece -> "A single piece meets these targets. Make them harder."
            else -> null
        }
    }
}

fun parseFrac(s: String): Frac? = s.split('/').mapNotNull { it.trim().toIntOrNull() }
    .takeIf { it.size == 2 && it[1] > 0 && it[0] > 0 && it[0] <= it[1] }?.let { Frac(it[0], it[1]) }

/** A saved custom level: its definition and the build that proved it solvable. */
@Serializable data class CustomLevel(val id: Int, val def: LevelDef, val solution: String) {
    val name get() = "custom-$id"
}

/**
 * Everything saved: the name of the level last played and each level's save, by level name; the player's custom
 * levels and the builder's draft definition (the builder's build is saved like any level's, under [BUILDER]).
 */
@Serializable data class SaveData(
    val level: String? = null,
    val levels: Map<String, LevelSave> = emptyMap(),
    val custom: List<CustomLevel> = emptyList(),
    val nextCustom: Int = 1,
    val draft: LevelDef = LevelDef(),
)

/** The save name of the level builder. */
const val BUILDER = "builder"

/** Where the save JSON lives. */
interface SaveStore {
    fun read(): String?
    fun write(json: String)
}

class PrefsStore(context: Context) : SaveStore {
    private val prefs = context.getSharedPreferences("divvywood", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(json: String) { prefs.edit { putString(KEY, json) } }
    init { if (prefs.contains(OLD_KEY)) prefs.edit { remove(OLD_KEY) } }   // saves from before ports were the build's sides start over
    private companion object { const val KEY = "save.v2"; const val OLD_KEY = "save.v1" }
}

class MemoryStore(var json: String? = null) : SaveStore {
    override fun read() = json
    override fun write(json: String) { this.json = json }
}

/** Saves per level and best scores, by level name, written through to [store] on every change. */
class Saves(private val store: SaveStore) {
    private val json = Json { ignoreUnknownKeys = true }
    var data: SaveData = store.read()?.let { runCatching { json.decodeFromString<SaveData>(it) }.getOrNull() } ?: SaveData()
        private set

    private fun put(d: SaveData) { data = d; store.write(json.encodeToString(d)) }

    /** The name of the level last played, if any. */
    var cur: String?
        get() = data.level
        set(v) { if (v != data.level) put(data.copy(level = v)) }

    fun best(name: String): Best? = data.levels[name]?.best

    /** Everything saved for the named level. */
    fun level(name: String): LevelSave = data.levels[name] ?: LevelSave()

    /** Records a Check of the named level's current build; returns the level's best afterwards. */
    fun recordCheck(name: String, m: Metrics, flow: Int?, solved: Boolean): Best? {
        val l = level(name)
        val checked = Checked(m.pieces, m.area, solved, flow ?: -1)
        val nl = if (!solved) l.copy(checked = checked) else l.copy(best = (l.best ?: Best()).with(m, flow), solution = l.build, checked = checked)
        put(data.copy(levels = data.levels + (name to nl)))
        return nl.best
    }

    /** Forgets the named level's results, keeping its build (the builder's, when its definition changes). */
    fun clearResults(name: String) {
        data.levels[name]?.let { put(data.copy(levels = data.levels + (name to it.copy(best = null, solution = null, checked = null)))) }
    }

    /** The builder's draft definition. */
    var draft: LevelDef
        get() = data.draft
        set(v) { put(data.copy(draft = v)) }

    val custom: List<CustomLevel> get() = data.custom

    /** Saves [def] as a custom level, proved by [solution]; returns it. */
    fun addCustom(def: LevelDef, solution: String): CustomLevel {
        val c = CustomLevel(data.nextCustom, def, solution)
        put(data.copy(custom = data.custom + c, nextCustom = data.nextCustom + 1))
        return c
    }

    /** Deletes a custom level with everything saved for it. */
    fun deleteCustom(id: Int) {
        val c = data.custom.firstOrNull { it.id == id } ?: return
        put(data.copy(custom = data.custom - c, levels = data.levels - c.name))
    }

    /** The named level's saved build; empty when there is none or it no longer decodes. */
    fun build(name: String): Tiles = level(name).build.takeIf { it.isNotBlank() }?.let { decodeGrid(it).tiles } ?: linkedMapOf<Cell, Tile>()

    fun putBuild(name: String, tiles: Map<Cell, Tile>) {
        val code = encodeGrid(tiles)
        if (code != level(name).build) put(data.copy(levels = data.levels + (name to level(name).copy(build = code))))
    }

}
