package com.schwii.divvywood.board

import android.graphics.Bitmap
import android.graphics.Canvas
import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.DX
import com.schwii.divvywood.engine.DY
import com.schwii.divvywood.engine.Frac
import com.schwii.divvywood.engine.Frame
import com.schwii.divvywood.engine.Graph
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.Level
import com.schwii.divvywood.engine.MachineLib
import com.schwii.divvywood.engine.NONE
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.buildGraph
import com.schwii.divvywood.wood.Look
import com.schwii.divvywood.wood.Painter
import com.schwii.divvywood.wood.Palette
import com.schwii.divvywood.wood.WirePainter
import com.schwii.divvywood.wood.angleOf
import com.schwii.divvywood.wood.edge
import com.schwii.divvywood.wood.hashCell
import com.schwii.divvywood.wood.routePoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** The painter for the current look. */
private val woodPainter by lazy { Painter() }
private val wirePainter by lazy { WirePainter() }
fun lookPainter(): Painter = if (Look.wire) wirePainter else woodPainter

/**
 * Where a graph's nodes are drawn: node i's cell centre and size on screen, for a board whose cell (0, 0) has its
 * corner at ([ox], [oy]) and whose cells are [z] pixels. Nodes inside machines sit in their machine's smaller grid.
 */
class Layout(val g: Graph, val ox: Float, val oy: Float, val z: Float) {
    fun x(i: Int) = g.nodes[i].let { ox + (it.frame.cx(it.local) * z).toFloat() }
    fun y(i: Int) = g.nodes[i].let { oy + (it.frame.cy(it.local) * z).toFloat() }
    fun size(i: Int) = (g.nodes[i].frame.s * z).toFloat()
    fun side(i: Int, j: Int) = sideBetween(g, i, j)

    /** The middle of the side of its machine that port [i] is on: where the parent's line meets it. */
    private fun portMid(i: Int): FloatArray {
        val n = g.nodes[i]; val v = n.portView!!
        val cx = ox + ((v.x + v.size / 2) * z).toFloat(); val cy = oy + ((v.y + v.size / 2) * z).toFloat()
        val h = (v.size * z / 2).toFloat()
        return floatArrayOf(cx + DX[n.portSide] * h, cy + DY[n.portSide] * h)
    }

    /** Where port [i]'s own line, in its row or column, meets its machine's edge. */
    private fun portLane(i: Int): FloatArray {
        val n = g.nodes[i]; val m = portMid(i)
        return if (n.portSide % 2 == 0) floatArrayOf(m[0], y(i)) else floatArrayOf(x(i), m[1])
    }

    /** The edge of the piece port [i] joins inside its machine, facing it. */
    private fun portInner(i: Int): FloatArray {
        val n = g.nodes[i]
        val j = (n.succ + n.pred).firstOrNull { g.nodes[it].frame == n.frame } ?: return portLane(i)
        return edge(x(j), y(j), size(j), side(j, i), 0.5f)
    }

    /**
     * Point [t] along node [i]'s route from side [from] to side [to] (NONE: the centre). A machine's input runs from
     * the middle of its side (where the parent's line ends) along the edge to its own row or column, then straight in
     * to the piece it feeds; a way out the same in reverse. So marbles cross into machines without a jump.
     */
    fun at(i: Int, from: Int, to: Int, t: Float): FloatArray {
        val n = g.nodes[i]
        if (n.portView != null && n.portSide != NONE && (from == n.portSide || to == n.portSide)) {
            val pts = if (from == n.portSide) listOf(portMid(i), portLane(i), portInner(i)) else listOf(portInner(i), portLane(i), portMid(i))
            val (a, b, u) = if (t < 0.5f) Triple(pts[0], pts[1], t * 2) else Triple(pts[1], pts[2], t * 2 - 1)
            return floatArrayOf(a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u)
        }
        return routePoint(x(i), y(i), size(i), from, to, t)
    }

    /** Port [i]'s line: from its machine's edge straight to the piece it joins. */
    fun connector(i: Int): FloatArray { val a = portLane(i); val b = portInner(i); return floatArrayOf(a[0], a[1], b[0], b[1]) }
}

/**
 * Draws [g]'s blocks with [painter], laid out by [lay]: board pieces, then each machine's block, then everything
 * inside machines at their own scale with the lines that carry marbles to and from their edges. [skip] leaves board
 * cells out (pieces lifted for a move); [bad] marks overloaded nodes; [label] names input blocks.
 */
fun paintBuild(
    c: Canvas, painter: Painter, lay: Layout, outdated: (String) -> Boolean = { false },
    skip: Set<Cell> = emptySet(), bad: (Int) -> Boolean = { false }, label: (Int) -> String? = { null }, dimmed: Set<Cell> = emptySet(),
) {
    val g = lay.g
    val nodes = g.nodes
    fun args(i: Int) = nodes[i].let { n -> Triple(n.pred.map { lay.side(i, it) }, n.succ.map { lay.side(i, it) }, hashCell(n.local.x * 31 + n.cell.x, n.local.y * 17 + n.cell.y)) }
    // one block per cell: ways out have none, a bridge's lanes share one, a machine's insides come later
    val board = nodes.indices.filter { g.idx[nodes[it].cell] == it && nodes[it].cell !in skip }.sortedWith(compareBy({ nodes[it].cell.y }, { nodes[it].cell.x }))
    for (i in board) painter.blockShadow(c, lay.ox + (nodes[i].cell.x + 0.5f) * lay.z, lay.oy + (nodes[i].cell.y + 0.5f) * lay.z, lay.z)
    for (i in board) {
        val n = nodes[i]
        val dim = n.cell in dimmed
        if (dim) c.saveLayerAlpha(null, (0.35f * 255).toInt())
        val x = lay.ox + (n.cell.x + 0.5f) * lay.z; val y = lay.oy + (n.cell.y + 0.5f) * lay.z
        val m = n.machine
        if (m != null) {
            // a machine's block (a broken one shows just that)
            painter.machineBlock(c, x - lay.z / 2, y - lay.z / 2, lay.z, args(i).third, m.ref != null && outdated(m.ref!!))
        } else {
            val (ins, outs, seed) = args(i)
            painter.tile(c, x, y, lay.z, n.bridge ?: n.tile, ins, outs, seed, bad(i) || (n.bridge != null && bad(i + 1)), label(i))
        }
        if (dim) c.restore()
    }
    // machines inside machines get their blocks too; every machine shows its own grid's dots
    for (v in g.machines) {
        if (v.size < 1.0) painter.machineBlock(c, lay.ox + (v.x * lay.z).toFloat(), lay.oy + (v.y * lay.z).toFloat(), (v.size * lay.z).toFloat(), 0, false)
        val f = v.inner
        val n = Math.round(v.size / f.s).toInt()
        val i0 = Math.round((v.x - f.ox) / f.s).toInt(); val j0 = Math.round((v.y - f.oy) / f.s).toInt()
        for (i in 1 until n) for (j in 1 until n) painter.gridDot(c, lay.ox + ((f.ox + (i0 + i) * f.s) * lay.z).toFloat(), lay.oy + ((f.oy + (j0 + j) * f.s) * lay.z).toFloat())
    }
    // everything inside machines, each cell once
    val seen = HashSet<Pair<Frame, Cell>>()
    for (i in nodes.indices) {
        val n = nodes[i]
        if (!n.inside || n.kind == Kind.MACHINE || n.cell in skip || !seen.add(n.frame to n.local)) continue
        if (n.portView != null) {   // a machine's input or way out: its line from the middle of its side
            painter.connector(c, lay.connector(i), lay.size(i))
            continue
        }
        val (ins, outs, seed) = args(i)
        painter.tile(c, lay.x(i), lay.y(i), lay.size(i), n.bridge ?: n.tile, ins, outs, seed, bad(i), slab = true)
    }
}

/**
 * Draws a [BoardState]. The table and blocks never move between build or camera changes, so they are drawn into a
 * cached bitmap; each frame only blits it and adds the marbles and the splitters' and convergers' turns.
 */
class BoardRenderer(
    private val state: BoardState,
    private val fixedPainter: Painter? = null,
    private val reduceMotion: Boolean = false,
    private val editor: Editor? = null,
    /** Just the build's lines, every branch lit: no box, labels, tags, grid or marbles (for the icon). */
    private val bare: Boolean = false,
) {
    private val painter get() = fixedPainter ?: lookPainter()
    private var layer: Bitmap? = null
    private var layerKey = ""

    private fun lay() = Layout(state.graph, state.ox, state.oy, state.z)

    private fun drawLayer(w: Int, h: Int) {
        val s = state
        val lifted = editor?.move?.takeIf { it.shifted }?.cells?.toSet()
        val key = "${s.version}|${s.ox}|${s.oy}|${s.z}|$w|$h|${lifted?.joinToString(";")}|${Look.wire}"
        if (key == layerKey && layer != null) return
        layerKey = key
        val bmp = layer?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { layer = it }
        val c = Canvas(bmp)
        painter.table(c, w.toFloat(), h.toFloat(), s.ox, s.oy, s.z)
        val nodes = s.graph.nodes
        // the machine's box, or the start cell while there's no machine: inputs sit around it
        val b = com.schwii.divvywood.engine.portBox(s.tiles)
        if (!bare) painter.machineBox(c, s.ox + b.x0 * s.z, s.oy + b.y0 * s.z, s.ox + (b.x1 + 1) * s.z, s.oy + (b.y1 + 1) * s.z, s.z)
        paintBuild(c, painter, lay(), s.outdated, bad = { s.isOverloaded(it) }, label = { if (bare) "" else s.labelOf(it) }, dimmed = lifted ?: emptySet())
        // ways out: a dotted line to the edge of the box, and a tag past it naming the output it serves
        if (!bare) for (p in s.graph.ports) if (!p.isInput) {
            val from = nodes[p.piece].cell
            val half = s.z * 0.5f
            val ex = s.screenX(p.edge.x) - DX[p.side] * half
            val ey = s.screenY(p.edge.y) - DY[p.side] * half
            val label = s.exitLabel(p.node)
            painter.exitTag(c, s.screenX(from.x) + DX[p.side] * half, s.screenY(from.y) + DY[p.side] * half,
                ex, ey, s.screenX(p.edge.x), s.screenY(p.edge.y), s.z, label, label == "rest")
        }
        for (n in nodes) if (n.errs.isNotEmpty() && n.frame == Frame.BOARD) painter.outline(c, s.screenX(n.cell.x), s.screenY(n.cell.y), s.z, Palette.bad, 2f * max(1f, s.z / 44f), dashed = true)
    }

    fun draw(c: Canvas, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        painter.unit = state.density
        painter.still = bare
        drawLayer(w, h)
        c.drawBitmap(layer!!, 0f, 0f, null)
        if (bare) { painter.still = false; return }   // the icon: just the lines
        val s = state
        val nodes = s.graph.nodes
        val sim = s.sim
        val lay = lay()
        val a = s.alpha
        // where marbles have been held up lately: a red glow, and a red ring on convergers turning them away
        nodes.forEachIndexed { i, n ->
            if (n.kind == Kind.OUT || n.kind == Kind.MACHINE) return@forEachIndexed
            val x = lay.x(i); val y = lay.y(i); val z = lay.size(i)
            s.heat.getOrNull(i)?.takeIf { it > 0.03f }?.let { painter.heatGlow(c, x, y, z, it) }
            if (!n.inside) s.turnHeat.getOrNull(i)?.takeIf { it > 0.05f }?.let { painter.outline(c, x, y, z, Palette.bad, max(1f, z * 0.06f) * (0.4f + it)) }
        }
        // the way each splitter sends: its branches but one dimmed. A marble still rolling out (the first half of a
        // tick) keeps its branch open until it has left; then the splitter shows where the next one goes.
        val leaving = HashMap<Int, Int>()
        if (a < 0.5f) {
            sim.n.forEachIndexed { j, sn -> sn.item?.let { if (it.pf != j) leaving[it.pf] = j } }
            for (g in sim.ghosts) leaving[g.from] = g.to
        }
        sim.n.forEachIndexed { i, sn ->
            if (sn.kind != Kind.SPLIT) return@forEachIndexed   // a converger takes whatever comes, so it shows no turn
            val list = sn.succ
            if (list.isEmpty()) return@forEachIndexed
            val active = s.sideTo(i, leaving[i] ?: list[sn.rr % list.size])
            val target = angleOf(active)
            var ang = s.flipperAngles[i] ?: target
            val d = (target - ang).let { atan2(sin(it), cos(it)) }
            ang += if (reduceMotion) d else d * 0.22f
            s.flipperAngles[i] = ang
            if (list.size > 1 || painter is WirePainter) painter.turn(c, lay.x(i), lay.y(i), lay.size(i), nodes[i].tile, active, ang, list.map { s.sideTo(i, it) })
        }
        // marbles: the first half of a tick finishes the old cell's route, the second half starts the new one
        sim.n.forEachIndexed { i, sn ->
            val it = sn.item ?: return@forEachIndexed
            val p = when {
                it.pf == i -> lay.at(i, it.curIn, s.exitSide(i), 0.5f)
                a < 0.5f -> lay.at(it.pf, it.prevIn, s.sideTo(it.pf, i), 0.5f + a)
                else -> lay.at(i, it.curIn, s.exitSide(i), a - 0.5f)
            }
            val sz = if (a < 0.5f && it.pf != i) lay.size(it.pf) else lay.size(i)
            painter.marble(c, p[0], p[1], sz, waiting = sim.waiting[i])
        }
        for (g in sim.ghosts) {
            val p = if (a < 0.5f) lay.at(g.from, g.item?.curIn ?: NONE, s.sideTo(g.from, g.to), 0.5f + a)
            else lay.at(g.to, s.sideTo(g.to, g.from), NONE, a - 0.5f)
            painter.marble(c, p[0], p[1], lay.size(g.from), if (a < 0.5f) 1f else max(0f, 1f - (a - 0.5f) * 2))
        }
        editor?.let { drawOverlays(c, it) }
    }

    /** Selection, the belt being drawn, the selection box, lifted pieces and a tray chip's landing spot. */
    private fun drawOverlays(c: Canvas, e: Editor) {
        val s = state
        val z = s.z
        val m = e.move
        val outlineW = 2f * max(1f, z / 44f)
        (if (m != null && m.shifted) emptyList() else e.selection).forEach {
            painter.outline(c, s.screenX(it.x), s.screenY(it.y), z, Palette.ink, outlineW)
        }
        when (val g = e.gesture) {
            is Editor.Draw -> if (g.path.size > 1) {
                val valid = com.schwii.divvywood.engine.pathValid(s.tiles, g.path)
                val pts = g.path.flatMap { listOf(s.screenX(it.x), s.screenY(it.y)) }
                painter.previewPath(c, pts, z * 0.3f, if (valid) Palette.beltPreview else Palette.bad)
            }
            is Editor.Box -> {
                val x0 = minOf(g.a.x, g.b.x); val x1 = maxOf(g.a.x, g.b.x); val y0 = minOf(g.a.y, g.b.y); val y1 = maxOf(g.a.y, g.b.y)
                painter.box(c, s.ox + x0 * z, s.oy + y0 * z, s.ox + (x1 + 1) * z, s.oy + (y1 + 1) * z, Palette.ink)
            }
            else -> Unit
        }
        if (m != null && m.shifted) {
            val moved = m.cells.mapNotNull { cell -> s.tiles[cell]?.let { Cell(cell.x + m.dx, cell.y + m.dy) to it } }.toMap()
            drawTiles(c, moved, s.ox, s.oy, z, lift = true)
            for (to in moved.keys) if (e.blockedAt(to)) painter.outline(c, s.screenX(to.x), s.screenY(to.y), z, Palette.bad, outlineW * 1.5f)
        }
        e.chipGhost?.let { gh ->
            val at = e.chipCell(gh.item, gh.cell)
            val fits = e.chipFits(gh.item, gh.cell)
            c.saveLayerAlpha(null, (0.9f * 255).toInt())
            drawTiles(c, mapOf(at to e.chipTile(gh.item, gh.cell)), s.ox, s.oy, z, lift = true,
                label = if (gh.item.kind == Kind.SRC) s.level.inputLabel(maxOf(gh.item.id, 0)) else null)
            c.restore()
            painter.outline(c, s.screenX(at.x), s.screenY(at.y), z, if (fits) Palette.ink else Palette.bad, if (fits) outlineW else outlineW * 1.5f)
        }
    }

    /** A few tiles on their own (lifted pieces, a chip's ghost), machines with their insides. */
    private fun drawTiles(c: Canvas, tiles: Map<Cell, Tile>, ox: Float, oy: Float, z: Float, lift: Boolean, label: String? = null) {
        val g = buildGraph(tiles, state.level, state.lib)
        if (lift) for (cell in tiles.keys) painter.blockShadow(c, ox + (cell.x + 0.54f) * z, oy + (cell.y + 0.56f) * z, z)
        paintBuild(c, painter, Layout(g, ox, oy, z), state.outdated, label = { i -> if (g.nodes[i].kind == Kind.SRC) label else null })
    }
}

/**
 * Draws [tiles] fitted into the square at ([l], [t]) of side [size]: tray chips and previews.
 */
fun drawBuild(c: Canvas, painter: Painter, tiles: Map<Cell, Tile>, lib: MachineLib, l: Float, t: Float, size: Float, outdated: (String) -> Boolean = { false }) {
    if (tiles.isEmpty()) return
    val x0 = tiles.keys.minOf { it.x }; val y0 = tiles.keys.minOf { it.y }
    val w = tiles.keys.maxOf { it.x } - x0 + 1; val h = tiles.keys.maxOf { it.y } - y0 + 1
    val z = size / maxOf(w, h)
    val ins = Level("draw", List(tiles.values.count { it.kind == Kind.SRC }) { Frac(1, 1) }, emptyList())
    val g = buildGraph(tiles, ins, lib)
    paintBuild(c, painter, Layout(g, l + (size - w * z) / 2 - x0 * z, t + (size - h * z) / 2 - y0 * z, z), outdated)
}
