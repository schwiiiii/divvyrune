package com.schwii.divvywood.solver

import com.schwii.divvywood.engine.LEVELS
import java.io.File

/** Writes every level's solutions' metrics as the app's baselines.json (path in args[0]). */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "baselines.json")
    val json = StringBuilder("{\n")
    LEVELS.forEachIndexed { i, level ->
        val t0 = System.currentTimeMillis()
        val found = solveLevel(level, seed = 1000L + i)
        val ms = System.currentTimeMillis() - t0
        fun list(f: (Found) -> Int) = found.map(f).sorted().joinToString(",", "[", "]")
        println("%2d %-18s %3d solutions in %5d ms | best pieces %s, side %s, io %s, cycle %s".format(
            i + 1, level.name, found.size, ms,
            found.minOfOrNull { it.metrics.pieces }, found.minOfOrNull { it.metrics.side }, found.maxOfOrNull { it.metrics.io }, found.minOfOrNull { it.cycle }))
        json.append("  \"${level.name}\": {\"pieces\": ${list { it.metrics.pieces }}, \"side\": ${list { it.metrics.side }}, ")
            .append("\"io\": ${list { it.metrics.io }}, \"cycle\": ${list { it.cycle }}}")
            .append(if (i < LEVELS.lastIndex) ",\n" else "\n")
    }
    json.append("}\n")
    out.writeText(json.toString())
    println("wrote ${out.path}")
}
