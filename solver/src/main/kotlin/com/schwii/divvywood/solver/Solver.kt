package com.schwii.divvywood.solver

import com.schwii.divvywood.engine.Level
import com.schwii.divvywood.engine.Metrics
import com.schwii.divvywood.engine.Tiles
import com.schwii.divvywood.engine.buildGraph
import com.schwii.divvywood.engine.check
import com.schwii.divvywood.engine.encodeGrid
import com.schwii.divvywood.engine.findCycle
import com.schwii.divvywood.engine.flowOf
import com.schwii.divvywood.engine.metricsOf
import kotlin.random.Random

/** A build the solver found, proved by the game's own Check, with its metrics and flow score. */
class Found(val tiles: Tiles, val code: String, val metrics: Metrics, val flow: Int)

/**
 * Up to [want] different solutions of [level] within [attempts] tries. Each try synthesizes a random machine and lays
 * it out; only layouts that pass Check (exact shares, a clean measured run with no jam skips) count.
 */
fun solveLevel(level: Level, seed: Long, want: Int = 100, attempts: Int = 60000): List<Found> {
    val rnd = Random(seed)
    val found = LinkedHashMap<String, Found>()
    repeat(attempts) {
        if (found.size >= want) return found.values.toList()
        val net = synthesize(level, rnd) ?: return@repeat
        val tiles = layOut(net, rnd, spread = 1 + rnd.nextInt(3)) ?: return@repeat
        val code = encodeGrid(tiles)
        if (code in found) return@repeat
        val g = buildGraph(tiles, level)
        if (g.errCount > 0) return@repeat
        val v = check(g, level)
        if (!v.solved || v.skips > 0) return@repeat
        val c = findCycle(g, level) ?: return@repeat
        found[code] = Found(tiles, code, metricsOf(tiles), flowOf(g, level, c).score)
    }
    return found.values.toList()
}
