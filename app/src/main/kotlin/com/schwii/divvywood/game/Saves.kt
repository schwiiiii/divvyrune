package com.schwii.divvywood.game

import android.content.Context
import androidx.core.content.edit
import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Frac
import com.schwii.divvywood.engine.Score
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.Tiles
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.engine.encodeGrid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A level's best solved scores: fewest parts, and the smallest and largest area. */
@Serializable data class Best(val parts: Int, val small: Int, val large: Int)

/** The build's score at its last Check, solved or not. */
@Serializable data class Checked(val parts: Int, val w: Int, val h: Int, val solved: Boolean) {
    val area get() = w * h
}

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
 * Everything saved: the name of the level last played and each level's save, by level name.
 * Saves from 0.1.x keyed levels by their index in the first 12-level list and kept the current one in [cur].
 */
@Serializable data class SaveData(
    val level: String? = null,
    val levels: Map<String, LevelSave> = emptyMap(),
    val cur: Int = 0,
    /** The random level's target, as "p/q". */
    val random: String? = null,
)

/** The level order of saves that keyed levels by index. */
private val V1_ORDER = listOf(
    "Halves", "Quarter back", "Three ways", "Sixth", "Eighths", "One two three",
    "Thirds by halves", "Fifth", "Seventh", "Full belt", "Seventeenth", "Sandbox",
)

private fun migrate(d: SaveData): SaveData =
    if (d.level != null) d
    else d.copy(level = V1_ORDER.getOrNull(d.cur), levels = d.levels.mapKeys { (k, _) -> k.toIntOrNull()?.let { V1_ORDER.getOrNull(it) } ?: k }, cur = 0)

/** Where the save JSON lives. */
interface SaveStore {
    fun read(): String?
    fun write(json: String)
}

class PrefsStore(context: Context) : SaveStore {
    private val prefs = context.getSharedPreferences("divvywood", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(json: String) { prefs.edit { putString(KEY, json) } }
    private companion object { const val KEY = "save.v1" }
}

class MemoryStore(var json: String? = null) : SaveStore {
    override fun read() = json
    override fun write(json: String) { this.json = json }
}

/** Saves per level and best scores, by level name, written through to [store] on every change. */
class Saves(private val store: SaveStore) {
    private val json = Json { ignoreUnknownKeys = true }
    var data: SaveData = store.read()?.let { runCatching { migrate(json.decodeFromString<SaveData>(it)) }.getOrNull() } ?: SaveData()
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
    fun recordCheck(name: String, score: Score, solved: Boolean): Best? {
        val l = level(name)
        val checked = Checked(score.parts, score.w, score.h, solved)
        val nl = if (!solved) l.copy(checked = checked) else {
            val b = l.best
            val nb = if (b == null) Best(score.parts, score.area, score.area)
            else Best(minOf(b.parts, score.parts), minOf(b.small, score.area), maxOf(b.large, score.area))
            l.copy(best = nb, solution = l.build, checked = checked)
        }
        put(data.copy(levels = data.levels + (name to nl)))
        return nl.best
    }

    /** The random level's current target, if one was picked. */
    var randomTarget: Frac?
        get() = data.random?.split('/')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 && it[1] > 0 }?.let { Frac(it[0], it[1]) }
        set(v) { put(data.copy(random = v?.toString())) }

    /** Forgets the named level's results, keeping its build (the random level's, when its target changes). */
    fun clearResults(name: String) {
        data.levels[name]?.let { put(data.copy(levels = data.levels + (name to it.copy(best = null, solution = null, checked = null)))) }
    }

    /** The named level's saved build; empty when there is none or it no longer decodes. */
    fun build(name: String): Tiles = level(name).build.takeIf { it.isNotBlank() }?.let { decodeGrid(it).tiles } ?: linkedMapOf<Cell, Tile>()

    fun putBuild(name: String, tiles: Map<Cell, Tile>) {
        val code = encodeGrid(tiles)
        if (code != level(name).build) put(data.copy(levels = data.levels + (name to level(name).copy(build = code))))
    }

}
