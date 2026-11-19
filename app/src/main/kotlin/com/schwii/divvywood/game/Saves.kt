package com.schwii.divvywood.game

import android.content.Context
import androidx.core.content.edit
import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Score
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.Tiles
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.engine.encodeGrid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A level's best solved scores: fewest parts, and the smallest and largest area. */
@Serializable data class Best(val parts: Int, val small: Int, val large: Int)

/** A level's build, as its text code, and its best scores once solved. */
@Serializable data class LevelSave(val build: String = "", val best: Best? = null)

/** Everything saved: the level last played and each level's save, by level index. */
@Serializable data class SaveData(val cur: Int = 0, val levels: Map<Int, LevelSave> = emptyMap())

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

/** Saves per level and best scores, written through to [store] on every change. */
class Saves(private val store: SaveStore) {
    private val json = Json { ignoreUnknownKeys = true }
    var data: SaveData = store.read()?.let { runCatching { json.decodeFromString<SaveData>(it) }.getOrNull() } ?: SaveData()
        private set

    private fun put(d: SaveData) { data = d; store.write(json.encodeToString(d)) }
    private fun level(i: Int) = data.levels[i] ?: LevelSave()

    var cur: Int
        get() = data.cur
        set(v) { if (v != data.cur) put(data.copy(cur = v)) }

    fun best(i: Int): Best? = data.levels[i]?.best

    /** Level [i]'s saved build; empty when there is none or it no longer decodes. */
    fun build(i: Int): Tiles = level(i).build.takeIf { it.isNotBlank() }?.let { decodeGrid(it).tiles } ?: linkedMapOf<Cell, Tile>()

    fun putBuild(i: Int, tiles: Map<Cell, Tile>) {
        val code = encodeGrid(tiles)
        if (code != level(i).build) put(data.copy(levels = data.levels + (i to level(i).copy(build = code))))
    }

    /** Records a solve of level [i] with [score]; returns the level's best afterwards. */
    fun recordSolve(i: Int, score: Score): Best {
        val b = level(i).best
        val nb = if (b == null) Best(score.parts, score.area, score.area)
        else Best(minOf(b.parts, score.parts), minOf(b.small, score.area), maxOf(b.large, score.area))
        put(data.copy(levels = data.levels + (i to level(i).copy(best = nb))))
        return nb
    }
}
