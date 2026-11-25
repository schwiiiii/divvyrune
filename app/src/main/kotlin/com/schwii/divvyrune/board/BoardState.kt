package com.schwii.divvyrune.board

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.schwii.divvyrune.engine.CHECK_TICKS
import com.schwii.divvyrune.engine.Cell
import com.schwii.divvyrune.engine.CycleFinder
import com.schwii.divvyrune.engine.Graph
import com.schwii.divvyrune.engine.Kind
import com.schwii.divvyrune.engine.LETTERS
import com.schwii.divvyrune.engine.Level
import com.schwii.divvyrune.engine.NONE
import com.schwii.divvyrune.engine.Sim
import com.schwii.divvyrune.engine.Snapshot
import com.schwii.divvyrune.engine.Solution
import com.schwii.divvyrune.engine.Tiles
import com.schwii.divvyrune.engine.WARM_TICKS
import com.schwii.divvyrune.engine.assignOutputs
import com.schwii.divvyrune.engine.buildGraph
import com.schwii.divvyrune.engine.MachineLib
import com.schwii.divvyrune.engine.dirTo
import com.schwii.divvyrune.engine.overloaded
import com.schwii.divvyrune.engine.solve
import com.schwii.divvyrune.wood.ExitTone

/**
 * Everything the board shows: the level, its tiles and the derived graph, exact solution and live simulation,
 * plus the camera. [version] changes whenever the build changes; [frame] changes every animation frame.
 */
class BoardState(level: Level, tiles: Tiles, val density: Float = 1f, lib: MachineLib = MachineLib.EMPTY) {
    /** The machines builds here may use. */
    var lib = lib
        set(v) { field = v; rebuild() }
    /** Whether a machine is an older build of its level (shown on its block). */
    var outdated: (String) -> Boolean = { false }
    /** The label of the level a machine solves ("05", "C2"), if known. */
    var levelLabel: (String) -> String? = { null }
    /** The level a machine solves, if known: its rates are the machine's. */
    var levelOf: (String) -> Level? = { null }
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

    /** Node index of the way out each final output A, B, C takes, or -1 when none does (see [assignOutputs]). */
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
        graph = buildGraph(tiles, level, lib)
        sol = solve(graph, level)
        restartSim()
        version++
    }

    /** Watches the live simulation for its loop, so steps can be counted from the loop's start. */
    lateinit var cycles: CycleFinder; private set

    private fun restartSim() {
        sim = Sim(graph, level)
        cycles = CycleFinder(sim).also { it.observe() }
        flipperAngles.clear()
        acc = 0f
        outNode = assignOutputs(graph, level, sol)
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
        if (sim.tick == 0) return
        hist.forEachIndexed { id, h ->
            val i = outNode[id]
            h.addLast(if (i >= 0) sim.n[i].sunk.toFloat() / sim.tick else 0f)
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
        cycles.observe()
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

    fun sideTo(i: Int, j: Int) = sideBetween(graph, i, j)

    /** Where node [i] sends its marble next: a splitter, wherever its flipper points. */
    fun exitSide(i: Int): Int {
        val n = sim.n[i]
        if (n.kind == Kind.OUT || n.succ.isEmpty()) return NONE
        return sideTo(i, if (n.kind == Kind.SPLIT) n.succ[n.rr % n.succ.size] else n.succ[0])
    }

    fun isOverloaded(i: Int) = overloaded(graph, sol, level, i)

    /** The live step as players read it: "step 3 of 18" once the loop has shown, the tick until then. */
    fun stepLabel(): String {
        val c = cycles.cycle ?: return "tick ${sim.tick}"
        val k = c.step(sim.tick) ?: return "tick ${sim.tick}"
        return "step $k of ${c.length}"
    }

    /** The input has been blocked within the last 30 ticks. */
    val inputBlocked get() = sim.tick - sim.lastBlock < 30

    /** Input [k] has been held up within the last 30 ticks. */
    fun inputBlocked(k: Int) = sim.tick - sim.lastBlockBy.getOrElse(k) { -1_000_000_000 } < 30

    /** Input [k]'s measured rate so far (marbles per tick), or null before the first tick. */
    fun inputRate(k: Int): Double? = if (sim.tick > 0) sim.emittedBy.getOrElse(k) { 0 }.toDouble() / sim.tick else null

    /** Final output [id]'s measured rate so far (marbles per tick), or null before anything is measured. */
    fun measured(id: Int): Double? {
        val i = outNode.getOrElse(id) { -1 }
        return if (i >= 0 && sim.tick > 0) sim.n[i].sunk.toDouble() / sim.tick else null
    }

    /** The label burned onto an input block. */
    fun labelOf(i: Int): String? {
        val n = graph.nodes[i]
        if (n.kind != Kind.SRC) return null
        val r = level.inputs.getOrNull(n.inputId)
        return level.inputLabel(n.inputId) + if (r != null && z >= 34f * density) " $r" else ""
    }

    /** Which output way out [i] serves ("A"), or "rest" for what no output takes. */
    fun exitName(i: Int): String = outNode.indexOf(i).let { if (it < 0) "rest" else LETTERS[it].toString() }

    /**
     * The tag on way out [i]: what actually leaves there ("1/4"; "?" without an exact solve),
     * and whether that's right: it meets the output it serves, or it's the one way out carrying exactly the rest.
     */
    fun exitTag(i: Int): Pair<String, ExitTone> {
        if (!sol.ok) return "?" to ExitTone.BAD
        val f = sol.flow[i]
        val id = outNode.indexOf(i)
        val tone = when {
            id >= 0 -> if (level.out[id].let { it == null || it.q == f }) ExitTone.OK else ExitTone.BAD
            graph.exits.count { it !in outNode } == 1 && f == level.rest && !f.isZero -> ExitTone.REST
            else -> ExitTone.BAD
        }
        return f.toString() to tone
    }

    companion object {
        /** How many samples a sparkline holds. */
        const val HIST = 120
        /** How long a single step takes to play out. */
        const val STEP_MS = 280f
    }
}

/**
 * The side of node [i] facing node [j], its neighbour in the graph. In one grid it's where [j]'s cell lies; across a
 * machine's edge it's the side the machine's input or way out is on.
 */
fun sideBetween(g: com.schwii.divvyrune.engine.Graph, i: Int, j: Int): Int {
    val a = g.nodes[i]; val b = g.nodes[j]
    return when {
        a.frame == b.frame -> dirTo(a.local, b.local)
        a.portView != null && a.portSide != NONE -> a.portSide
        b.portView != null && b.portSide != NONE -> com.schwii.divvyrune.engine.opp(b.portSide)
        else -> dirTo(a.cell, b.cell)
    }
}
