package com.schwii.divvywood.board

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Graph
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.LETTERS
import com.schwii.divvywood.engine.Level
import com.schwii.divvywood.engine.NONE
import com.schwii.divvywood.engine.Sim
import com.schwii.divvywood.engine.Solution
import com.schwii.divvywood.engine.Tiles
import com.schwii.divvywood.engine.buildGraph
import com.schwii.divvywood.engine.dirTo
import com.schwii.divvywood.engine.overloaded
import com.schwii.divvywood.engine.solve

/**
 * Everything the board shows: the level, its tiles and the derived graph, exact solution and live simulation,
 * plus the camera. [version] changes whenever the build changes; [frame] changes every animation frame.
 */
class BoardState(level: Level, tiles: Tiles, private val density: Float = 1f) {
    var level = level; private set
    var tiles = tiles; private set
    lateinit var graph: Graph; private set
    lateinit var sol: Solution; private set
    lateinit var sim: Sim; private set

    var version by mutableIntStateOf(0); private set
    var frame by mutableIntStateOf(0); private set

    /** Camera: screen position of cell (0, 0)'s corner, and the size of one cell, in pixels. */
    var ox = 0f; var oy = 0f; var z = 44f * density
    val minZ get() = 14f * density
    val maxZ get() = 96f * density
    var camVersion by mutableIntStateOf(0); private set

    /** Animation: ticks per second at speed 1, the speed multiplier, and how far between ticks this frame is. */
    var playing = true
    var speed = 1
    var alpha = 1f; private set
    private var acc = 0f
    /** Each flipper's current angle, easing toward the channel it points at next. */
    val flipperAngles = HashMap<Int, Float>()

    init { rebuild() }

    fun setBuild(level: Level, tiles: Tiles) { this.level = level; this.tiles = tiles; rebuild() }

    fun rebuild() {
        graph = buildGraph(tiles, level)
        sol = solve(graph)
        sim = Sim(graph, level.t)
        flipperAngles.clear()
        acc = 0f
        version++
    }

    /** Centres the build in a [w] × [h] view. */
    fun fit(w: Float, h: Float) {
        val cx: Float; val cy: Float
        if (tiles.isEmpty()) { cx = 0.5f; cy = 0.5f } else {
            cx = (tiles.keys.minOf { it.x } + tiles.keys.maxOf { it.x } + 1) / 2f
            cy = (tiles.keys.minOf { it.y } + tiles.keys.maxOf { it.y } + 1) / 2f
        }
        ox = w / 2 - cx * z; oy = h / 2 - cy * z
        camVersion++
    }

    /** Two-finger pan and pinch: keeps the point under the fingers' centre fixed while zooming. */
    fun panZoom(cx: Float, cy: Float, zoom: Float, dx: Float, dy: Float) {
        val nz = (z * zoom).coerceIn(minZ, maxZ)
        val wx = (cx - ox) / z; val wy = (cy - oy) / z
        z = nz; ox = cx - wx * nz + dx; oy = cy - wy * nz + dy
        camVersion++
    }

    fun screenX(x: Int) = ox + (x + 0.5f) * z
    fun screenY(y: Int) = oy + (y + 0.5f) * z
    fun cellAt(sx: Float, sy: Float) = Cell(kotlin.math.floor((sx - ox) / z).toInt(), kotlin.math.floor((sy - oy) / z).toInt())

    /** Advances the simulation by [dtMs] of real time at 6 ticks a second times [speed]. */
    fun advance(dtMs: Float) {
        if (playing) {
            val tickMs = 1000f / (6 * speed)
            acc += dtMs.coerceAtMost(100f)
            while (acc >= tickMs) { acc -= tickMs; step() }
            alpha = acc / tickMs
        }
        frame++
    }

    /** One tick, then note the side each marble entered its cell from, so it can follow the right curve. */
    fun step() {
        sim.step()
        sim.n.forEachIndexed { i, sn ->
            val it = sn.item ?: return@forEachIndexed
            if (it.pf == i || it.at == i) return@forEachIndexed
            it.prevIn = if (sim.n[it.pf].kind == Kind.SRC) NONE else it.curIn
            it.curIn = sideTo(i, it.pf)
            it.at = i
        }
    }

    fun sideTo(i: Int, j: Int) = dirTo(graph.nodes[i].cell, graph.nodes[j].cell)

    /** Where node [i] sends its marble next: a splitter, wherever its flipper points. */
    fun exitSide(i: Int): Int {
        val n = sim.n[i]
        if (n.kind == Kind.OUT || n.kind == Kind.DUMP || n.succ.isEmpty()) return NONE
        return sideTo(i, if (n.kind == Kind.SPLIT) n.succ[n.rr % n.succ.size] else n.succ[0])
    }

    fun isOverloaded(i: Int) = overloaded(graph, sol, level, i)

    /** The label burned onto an input or output block. */
    fun labelOf(i: Int): String? {
        val n = graph.nodes[i]
        return when (n.kind) {
            Kind.SRC -> "IN"
            Kind.OUT -> {
                val tg = level.out.getOrNull(n.id)
                LETTERS[n.id] + if (tg != null && z >= 34f * density) " $tg" else ""
            }
            else -> null
        }
    }
}
