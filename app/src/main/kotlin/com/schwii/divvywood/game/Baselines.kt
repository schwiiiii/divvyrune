package com.schwii.divvywood.game

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Many solutions' metrics for one level: the histograms a player's build is shown against. */
@Serializable data class LevelBaseline(val pieces: List<Int> = emptyList(), val side: List<Int> = emptyList(), val io: List<Int> = emptyList())

/** Baselines by level name, from `assets/baselines.json` (written by the solver). */
class Baselines(val levels: Map<String, LevelBaseline> = emptyMap()) {
    operator fun get(name: String): LevelBaseline? = levels[name]

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String) = Baselines(runCatching { json.decodeFromString<Map<String, LevelBaseline>>(text) }.getOrDefault(emptyMap()))
        fun load(context: Context) = parse(runCatching { context.assets.open("baselines.json").bufferedReader().use { it.readText() } }.getOrDefault("{}"))
    }
}
