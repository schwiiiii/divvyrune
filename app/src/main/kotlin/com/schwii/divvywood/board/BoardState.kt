package com.schwii.divvywood.board

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.schwii.divvywood.engine.CHECK_TICKS
import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Graph
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.LETTERS
import com.schwii.divvywood.engine.Level
import com.schwii.divvywood.engine.NONE
import com.schwii.divvywood.engine.Sim
import com.schwii.divvywood.engine.Snapshot
import com.schwii.divvywood.engine.Solution
import com.schwii.divvywood.engine.Tiles
import com.schwii.divvywood.engine.WARM_TICKS
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
    var playing by mutableStateOf(true)
    var speed = 1
    var alpha = 1f; private set
    private var acc = 0f
    /** Each flipper's current angle, easing toward the channel it points at next. */
    val flipperAngles = HashMap<Int, Float>()

    /** How much each node has been holding marbles up lately (0–1, decaying), and each converger turning them away. */
    var heat = FloatArray(0); private set
    var turnHeat = FloatArray(0); private set
    /** Milliseconds into the animation of a single step, while paused. */
    private var stepMs = STEP_MS

    /** Node index of each final output A, B, C, or -1 when it isn't placed. */
    var outNode = IntArray(0); private set
    /** Each final output's measured share of the input, sampled as the simulation runs: the readout sparklines. */
    var hist = List(0) { ArrayDeque<Float>() }; private set

    /** A Check in progress: the simulation restarts and runs [CHECK_TICKS] fast, measured after [WARM_TICKS]. */
    var checking by mutableStateOf(false); private set
    private var w0: Snapshot? = null
    /** Called when a Check run ends, with the snapshots bounding its measuring window. */
    var onCheckDone: ((Snapshot?, Snapshot) -> Unit)? = null

    /** The view's size in pixels, once laid out. */
    var viewW = 0f; private set
    var viewH = 0f; private set

    init { rebuild() }

    fun setBuild(level: Level, tiles: Tiles) { this.level = level; this.tiles = tiles; checking = false; rebuild() }

    fun rebuild() {
        graph = buildGraph(tiles, level)
        sol = solve(graph)
        restartSim()
        version++
    }

    private fun restartSim() {
        sim = Sim(graph, level.t)
        flipperAngles.clear()
        acc = 0f
        outNode = IntArray(level.out.size) { id -> graph.nodes.indexOfFirst { it.kind == Kind.OUT && it.id == id } }
        hist = List(level.out.size) { ArrayDeque() }
        heat = FloatArray(graph.nodes.size); turnHeat = FloatArray(graph.nodes.size)
        stepMs = STEP_MS
    }

    fun startCheck() {
        restartSim()
        w0 = null
        checking = true
    }

    /** One Check tick; ends the run after the last. */
    private fun checkStep() {
        step()
        if (sim.tick == WARM_TICKS) w0 = sim.snap()
        if (sim.tick % 25 == 0) sampleHist()
        if (sim.tick >= CHECK_TICKS) { checking = false; onCheckDone?.invoke(w0, sim.snap()) }
    }

    /** Runs the Check in progress to its end at once. */
    fun finishCheck() { while (checking) checkStep(); frame++ }

    private fun sampleHist() {
        if (sim.emitted == 0) return
        hist.forEachIndexed { id, h ->
            val i = outNode[id]
            h.addLast(if (i >= 0) sim.n[i].sunk.toFloat() / sim.emitted else 0f)
            if (h.size > HIST) h.removeFirst()
        }
    }

    /** Remembers the view's size; the first time, centres the build in it. */
    fun setView(w: Float, h: Float) {
        val first = viewW == 0f
        viewW = w; viewH = h
        if (first) fit(w, h)
    }

    /** Centres the build in the view. */
    fun refit() { if (viewW > 0f) fit(viewW, viewH) }

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

    /** One-finger pan: shifts the view by (dx, dy) pixels. */
    fun pan(dx: Float, dy: Float) { ox += dx; oy += dy; camVersion++ }

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
        if (checking) {
            repeat(40) { if (checking) checkStep() }
            alpha = 1f
        } else if (playing) {
            val tickMs = 1000f / (6 * speed)
            acc += dtMs.coerceAtMost(100f)
            while (acc >= tickMs) { acc -= tickMs; step(); if (sim.tick % 8 == 0) sampleHist() }
            alpha = acc / tickMs
        } else if (stepMs < STEP_MS) {
            stepMs += dtMs.coerceAtMost(100f)
            alpha = (stepMs / STEP_MS).coerceAtMost(1f)
        }
        frame++
    }

    /** While paused: one tick on, animated. */
    fun stepOnce() {
        if (checking || playing) return
        step()
        if (sim.tick % 8 == 0) sampleHist()
        acc = 0f; stepMs = 0f; alpha = 0f
        frame++
    }

    /** One tick, then note the side each marble entered its cell from, so it can follow the right curve. */
    fun step() {
        sim.step()
        for (i in heat.indices) {
            heat[i] = heat[i] * 0.92f + if (sim.waiting[i]) 0.08f else 0f
            turnHeat[i] = turnHeat[i] * 0.92f + if (sim.turnedAway[i]) 0.08f else 0f
        }
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

    /** The input has been blocked within the last 30 ticks. */
    val inputBlocked get() = sim.tick - sim.lastBlock < 30

    /** Final output [id]'s measured share so far, or null before anything is measured. */
    fun measured(id: Int): Double? {
        val i = outNode.getOrElse(id) { -1 }
        return if (i >= 0 && sim.emitted > 0) sim.n[i].sunk.toDouble() / sim.emitted else null
    }

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

    companion object {
        /** How many samples a sparkline holds. */
        const val HIST = 120
        /** How long a single step takes to play out. */
        const val STEP_MS = 280f
    }
}
