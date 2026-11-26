package com.schwii.divvyrune.wood

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.schwii.divvyrune.engine.Kind
import com.schwii.divvyrune.engine.Tile
import com.schwii.divvyrune.engine.opp
import com.schwii.divvyrune.wood.Palette.rgba

/**
 * The rune look: the build scrawled on dark slate as glowing strokes. Lines run straight and turn on a sharp
 * chamfer, as runes are cut, its middle on the curve marbles roll along. Each stroke carries a soft glow. Splitters
 * and convergers wear a small diamond knot where their strokes meet, marbles are glowing motes, and machines are
 * engraved tablets. Like the wireframe, lines keep one width on screen at every zoom, and a splitter lights only the
 * branch its next marble takes.
 */
class RunePainter : WirePainter() {
    override val ground = rgba(20, 22, 31, 1.0)
    override val white = rgba(214, 233, 255, 1.0)
    override val faint = rgba(150, 190, 240, 0.28)
    override val amber = rgba(242, 193, 78, 1.0)
    override val green = rgba(111, 211, 192, 1.0)
    override val grey = rgba(140, 150, 171, 1.0)
    /** The blue the white strokes glow with. */
    private val aura = rgba(80, 150, 255, 1.0)

    /** The glow around every stroke: a wide, faint copy of it, in a bluer tint. */
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun width(z: Float) = 2.4f * unit

    override fun stroke(c: Canvas, p: Path, z: Float, color: Int, w: Float) {
        if (color != ground) {
            // two soft passes in the colour's own hue, bluer for the white strokes: a wide haze and a closer halo
            val a = android.graphics.Color.alpha(color) / 255f
            glow.color = if (color == white || color == faint) aura else color
            glow.alpha = (26 * a).toInt(); glow.strokeWidth = w * 5.5f; c.drawPath(p, glow)
            glow.alpha = (70 * a).toInt(); glow.strokeWidth = w * 2.4f; c.drawPath(p, glow)
        }
        super.stroke(c, p, z, color, w)
    }

    /** Edge to edge: straight across, or in a quarter of the way, a sharp chamfer, and out again. */
    override fun route(p: Path, x: Float, y: Float, z: Float, a: Int, b: Int) {
        val e0 = edge(x, y, z, a, 0.5f); val e1 = edge(x, y, z, b, 0.5f)
        p.moveTo(e0[0], e0[1])
        if (a == opp(b)) { p.lineTo(e1[0], e1[1]); return }
        // halfway from each edge to the centre: the chamfer's middle is the marbles' curve's middle
        p.lineTo((e0[0] + x) / 2, (e0[1] + y) / 2)
        p.lineTo((e1[0] + x) / 2, (e1[1] + y) / 2)
        p.lineTo(e1[0], e1[1])
    }

    override fun table(c: Canvas, w: Float, h: Float, ox: Float, oy: Float, z: Float) {
        // slate, a little lighter at the top
        fill.shader = LinearGradient(0f, 0f, 0f, h, rgba(28, 31, 43, 1.0), ground, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        if (still) return
        var gx = Math.floor((-ox / z).toDouble()).toInt()
        while (ox + gx * z <= w) {
            var gy = Math.floor((-oy / z).toDouble()).toInt()
            while (oy + gy * z <= h) { gridDot(c, ox + gx * z, oy + gy * z); gy++ }
            gx++
        }
    }

    /** A tiny scratched cross where grid lines meet. */
    override fun gridDot(c: Canvas, x: Float, y: Float) {
        val r = 2.2f * unit
        line.color = rgba(150, 190, 240, 0.16); line.strokeWidth = 1f * unit; line.pathEffect = null
        c.drawLine(x - r, y, x + r, y, line); c.drawLine(x, y - r, x, y + r, line)
    }

    override fun tile(c: Canvas, x: Float, y: Float, z: Float, t: Tile, ins: List<Int>, outs: List<Int>, seed: Int, bad: Boolean, label: String?, labelScale: Float, slab: Boolean) {
        super.tile(c, x, y, z, t, ins, outs, seed, bad, label, labelScale, slab)
        // the knot where a splitter's or converger's strokes meet: hollow on a splitter, solid on a converger
        if (t.kind == Kind.SPLIT || t.kind == Kind.MERGE) {
            val r = minOf(z * 0.11f, 6f * unit)
            val d = Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }
            fill.color = if (t.kind == Kind.MERGE) (if (bad) Palette.bad else white) else ground
            c.drawPath(d, fill)
            stroke(c, d, z, if (bad) Palette.bad else white, width(z) * 0.8f)
        }
    }

    /** A machine: a slate tablet with an engraved double border. */
    override fun machineBlock(c: Canvas, x: Float, y: Float, size: Float, seed: Int, outdated: Boolean) {
        rect.set(x, y, x + size, y + size)
        fill.color = rgba(32, 36, 50, 1.0); c.drawRect(rect, fill)
        line.pathEffect = null; line.strokeCap = Paint.Cap.BUTT
        line.color = rgba(150, 190, 240, 0.55); line.strokeWidth = 1.4f * unit
        rect.inset(line.strokeWidth / 2, line.strokeWidth / 2); c.drawRect(rect, line)
        line.color = rgba(150, 190, 240, 0.22); line.strokeWidth = 1f * unit
        rect.inset(3f * unit, 3f * unit); c.drawRect(rect, line)
        line.strokeCap = Paint.Cap.ROUND
        if (outdated) { fill.color = amber; c.drawCircle(x + size * 0.88f, y + size * 0.12f, size * 0.07f, fill) }
    }

    /** A glowing mote, the same size on screen at every zoom. */
    override fun marble(c: Canvas, x: Float, y: Float, z: Float, alpha: Float, waiting: Boolean) {
        val r = 3.2f * unit
        fill.shader = RadialGradient(x, y, r * 2.6f, intArrayOf(rgba(170, 215, 255, 0.55 * alpha), rgba(120, 180, 255, 0.0)), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r * 2.6f, fill); fill.shader = null
        fill.color = rgba(240, 248, 255, alpha.toDouble()); c.drawCircle(x, y, r, fill)
        if (waiting) { line.color = Palette.bad; line.strokeWidth = 1.4f * unit; c.drawCircle(x, y, r * 1.5f, line) }
    }
}
