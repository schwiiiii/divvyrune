package com.schwii.divvywood.board

import android.graphics.Bitmap
import android.graphics.Canvas
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.NONE
import com.schwii.divvywood.wood.Painter
import com.schwii.divvywood.wood.Palette
import com.schwii.divvywood.wood.angleOf
import com.schwii.divvywood.wood.hashCell
import com.schwii.divvywood.wood.routePoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Draws a [BoardState]. The table and blocks never move between build or camera changes, so they are drawn into a
 * cached bitmap; each frame only blits it and adds the marbles and flippers.
 */
class BoardRenderer(
    private val state: BoardState,
    private val painter: Painter = Painter(),
    private val reduceMotion: Boolean = false,
    private val editor: Editor? = null,
) {
    private var layer: Bitmap? = null
    private var layerKey = ""

    private fun tileArgs(i: Int) = state.graph.nodes[i].let { n ->
        Triple(n.pred.map { state.sideTo(i, it) }, n.succ.map { state.sideTo(i, it) }, hashCell(n.cell.x, n.cell.y))
    }

    private fun drawLayer(w: Int, h: Int) {
        val s = state
        val lifted = editor?.move?.takeIf { it.shifted }?.cells?.toSet()
        val key = "${s.version}|${s.ox}|${s.oy}|${s.z}|$w|$h|${lifted?.joinToString(";")}"
        if (key == layerKey && layer != null) return
        layerKey = key
        val bmp = layer?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { layer = it }
        val c = Canvas(bmp)
        painter.table(c, w.toFloat(), h.toFloat(), s.ox, s.oy, s.z)
        val nodes = s.graph.nodes
        val order = nodes.indices.sortedWith(compareBy({ nodes[it].cell.y }, { nodes[it].cell.x }))
        for (i in order) {
            if (lifted != null && nodes[i].cell in lifted) continue
            painter.blockShadow(c, s.screenX(nodes[i].cell.x), s.screenY(nodes[i].cell.y), s.z)
        }
        for (i in order) {
            val n = nodes[i]
            val (ins, outs, seed) = tileArgs(i)
            val dim = lifted != null && n.cell in lifted   // left behind while its lifted copy hovers elsewhere
            if (dim) c.saveLayerAlpha(null, (0.35f * 255).toInt())
            painter.tile(c, s.screenX(n.cell.x), s.screenY(n.cell.y), s.z, n.tile, ins, outs, seed, s.isOverloaded(i), s.labelOf(i))
            if (dim) c.restore()
        }
        for (n in nodes) if (n.errs.isNotEmpty()) painter.outline(c, s.screenX(n.cell.x), s.screenY(n.cell.y), s.z, Palette.bad, 2f * max(1f, s.z / 44f), dashed = true)
    }

    fun draw(c: Canvas, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        drawLayer(w, h)
        c.drawBitmap(layer!!, 0f, 0f, null)
        val s = state
        val nodes = s.graph.nodes
        val sim = s.sim
        val z = s.z
        val a = s.alpha
        fun at(i: Int, from: Int, to: Int, t: Float) = routePoint(s.screenX(nodes[i].cell.x), s.screenY(nodes[i].cell.y), z, from, to, t)
        val r = max(3f, z * 0.12f)
        // where marbles have been held up lately: a red glow, and a red ring on convergers turning them away
        nodes.forEachIndexed { i, n ->
            val x = s.screenX(n.cell.x); val y = s.screenY(n.cell.y)
            s.heat.getOrNull(i)?.takeIf { it > 0.03f }?.let { painter.heatGlow(c, x, y, z, it) }
            s.turnHeat.getOrNull(i)?.takeIf { it > 0.05f }?.let { painter.outline(c, x, y, z, Palette.bad, max(2f, z * 0.06f) * (0.4f + it)) }
        }
        // marbles: the first half of a tick finishes the old cell's curve, the second half starts the new one
        sim.n.forEachIndexed { i, sn ->
            val it = sn.item ?: return@forEachIndexed
            val p = when {
                it.pf == i -> at(i, it.curIn, s.exitSide(i), 0.5f)
                a < 0.5f -> at(it.pf, it.prevIn, s.sideTo(it.pf, i), 0.5f + a)
                else -> at(i, it.curIn, s.exitSide(i), a - 0.5f)
            }
            painter.marble(c, p[0], p[1], r, waiting = sim.waiting[i])
        }
        for (g in sim.ghosts) {
            val p = if (a < 0.5f) at(g.from, g.item?.curIn ?: NONE, s.sideTo(g.from, g.to), 0.5f + a)
            else at(g.to, s.sideTo(g.to, g.from), NONE, a - 0.5f)
            painter.marble(c, p[0], p[1], r, if (a < 0.5f) 1f else max(0f, 1f - (a - 0.5f) * 2))
        }
        // flippers swing toward the next channel
        sim.n.forEachIndexed { i, sn ->
            if (sn.kind != Kind.SPLIT && sn.kind != Kind.MERGE) return@forEachIndexed
            val list = if (sn.kind == Kind.SPLIT) sn.succ else sn.pred
            if (list.size < 2) return@forEachIndexed
            val target = angleOf(s.sideTo(i, list[sn.rr % list.size]))
            var ang = s.flipperAngles[i] ?: target
            val d = (target - ang).let { atan2(sin(it), cos(it)) }
            ang += if (reduceMotion) d else d * 0.22f
            s.flipperAngles[i] = ang
            painter.flipper(c, s.screenX(nodes[i].cell.x), s.screenY(nodes[i].cell.y), z, ang)
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
            for (cell in m.cells) {
                val i = s.graph.idx[cell] ?: continue
                val n = s.graph.nodes[i]
                val to = com.schwii.divvywood.engine.Cell(cell.x + m.dx, cell.y + m.dy)
                val x = s.screenX(to.x); val y = s.screenY(to.y)
                val (ins, outs, seed) = tileArgs(i)
                painter.blockShadow(c, x + z * 0.04f, y + z * 0.06f, z)
                painter.tile(c, x, y, z, n.tile, ins, outs, seed, false, s.labelOf(i))
                if (e.blockedAt(to)) painter.outline(c, x, y, z, Palette.bad, outlineW * 1.5f)
            }
        }
        e.chipGhost?.let { gh ->
            val x = s.screenX(gh.cell.x); val y = s.screenY(gh.cell.y)
            val fits = e.chipFits(gh.item, gh.cell)
            c.saveLayerAlpha(null, (0.9f * 255).toInt())
            painter.blockShadow(c, x + z * 0.04f, y + z * 0.06f, z)
            painter.tile(c, x, y, z, e.chipTile(gh.item, gh.cell), emptyList(), emptyList(), 77,
                label = if (gh.item.kind == com.schwii.divvywood.engine.Kind.SRC) "IN" else if (gh.item.kind == com.schwii.divvywood.engine.Kind.OUT) com.schwii.divvywood.engine.LETTERS[gh.item.id].toString() else null)
            c.restore()
            painter.outline(c, x, y, z, if (fits) Palette.ink else Palette.bad, if (fits) outlineW else outlineW * 1.5f)
        }
    }
}
