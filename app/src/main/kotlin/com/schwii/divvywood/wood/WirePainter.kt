package com.schwii.divvywood.wood

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.NONE
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.opp
import com.schwii.divvywood.wood.Palette.rgba
import kotlin.math.max

/**
 * The wireframe look: white lines on a dark ground, following the same curves as the wood channels, with arrows,
 * and marbles as dots. A splitter's branches are dim but for the one its next marble takes (drawn by [turn]), so the
 * bright branch swaps as marbles pass; a converger's inputs likewise.
 */
class WirePainter : Painter() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fonts.label; Fonts.labelVariation?.let { fontVariationSettings = it } }
    private val rect = RectF()

    private val ground = rgba(20, 22, 26, 1.0)
    private val white = rgba(237, 239, 242, 1.0)
    private val faint = rgba(237, 239, 242, 0.22)
    private val amber = rgba(242, 193, 78, 1.0)
    private val green = rgba(123, 211, 137, 1.0)
    private val grey = rgba(154, 160, 168, 1.0)

    private fun width(z: Float) = max(1.2f, z * 0.06f)

    private fun stroke(c: Canvas, p: Path, z: Float, color: Int, w: Float = width(z)) {
        line.color = color; line.strokeWidth = w; line.pathEffect = null
        c.drawPath(p, line)
    }

    private fun route(p: Path, x: Float, y: Float, z: Float, a: Int, b: Int) {
        val e0 = edge(x, y, z, a, 0.5f); val e1 = edge(x, y, z, b, 0.5f)
        p.moveTo(e0[0], e0[1])
        if (a == opp(b)) p.lineTo(e1[0], e1[1]) else p.quadTo(x, y, e1[0], e1[1])
    }

    private fun arrow(c: Canvas, at: FloatArray, d: Int, z: Float, color: Int) {
        val a = angleOf(d); val s = z * 0.1f
        val p = Path()
        p.moveTo(at[0] - kotlin.math.cos(a - 0.7f) * s, at[1] - kotlin.math.sin(a - 0.7f) * s); p.lineTo(at[0], at[1])
        p.lineTo(at[0] - kotlin.math.cos(a + 0.7f) * s, at[1] - kotlin.math.sin(a + 0.7f) * s)
        stroke(c, p, z, color)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, maxWidth: Float = Float.MAX_VALUE) {
        text.textSize = size; text.color = color
        val w = text.measureText(s)
        c.save()
        if (w > maxWidth) { c.translate(x, 0f); c.scale(maxWidth / w, 1f); c.translate(-x, 0f) }
        val fm = text.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, text)
        c.restore()
    }

    override fun table(c: Canvas, w: Float, h: Float, ox: Float, oy: Float, z: Float) {
        fill.color = ground; c.drawRect(0f, 0f, w, h, fill)
        fill.color = rgba(237, 239, 242, 0.12)
        var gx = Math.floor((-ox / z).toDouble()).toInt() - 1
        while (gx < -ox / z + w / z + 2) {
            var gy = Math.floor((-oy / z).toDouble()).toInt() - 1
            while (gy < -oy / z + h / z + 2) { c.drawCircle(ox + gx * z, oy + gy * z, 1.2f, fill); gy++ }
            gx++
        }
    }

    override fun blockShadow(c: Canvas, x: Float, y: Float, z: Float) = Unit

    override fun burnText(c: Canvas, s: String, x: Float, y: Float, size: Float, maxWidth: Float, light: Boolean) =
        label(c, s, x, y, size, white, maxWidth)

    override fun tile(c: Canvas, x: Float, y: Float, z: Float, t: Tile, ins: List<Int>, outs: List<Int>, seed: Int, bad: Boolean, label: String?, labelScale: Float) {
        val col = if (bad) Palette.bad else white
        val p = Path()
        when (t.kind) {
            Kind.BELT -> { route(p, x, y, z, t.from, t.out); stroke(c, p, z, col); arrow(c, edge(x, y, z, t.out, 0.12f), t.out, z, col) }
            Kind.SPLIT -> {
                // the way in, bright; every branch dim (the next one is lit by [turn])
                val e = edge(x, y, z, t.inSide, 0.5f); p.moveTo(e[0], e[1]); p.lineTo(x, y); stroke(c, p, z, col)
                for (o in outs) { val q = Path(); route(q, x, y, z, t.inSide, o); stroke(c, q, z, faint) }
                fill.color = col; c.drawCircle(x, y, z * 0.07f, fill)
            }
            Kind.MERGE -> {
                val e = edge(x, y, z, t.out, 0.5f); p.moveTo(x, y); p.lineTo(e[0], e[1]); stroke(c, p, z, col)
                arrow(c, edge(x, y, z, t.out, 0.3f), t.out, z, col)
                for (i in ins) { val q = Path(); route(q, x, y, z, i, t.out); stroke(c, q, z, faint) }
                fill.color = col; c.drawCircle(x, y, z * 0.07f, fill)
            }
            Kind.SRC -> {
                val e = edge(x, y, z, t.out, 0.5f); p.moveTo(x, y); p.lineTo(e[0], e[1]); stroke(c, p, z, amber)
                line.color = amber; line.strokeWidth = width(z); c.drawCircle(x, y, z * 0.18f, line)
                label(c, label ?: "IN", x, y - z * 0.33f, z * 0.15f * labelScale, amber, z * 0.95f)
            }
            Kind.BRIDGE -> {
                route(p, x, y, z, opp(t.out), t.out); stroke(c, p, z, col)
                val q = Path(); route(q, x, y, z, opp(t.inSide), t.inSide)
                stroke(c, q, z, ground, width(z) * 3.2f)   // a gap where the other line passes over
                stroke(c, q, z, col)
                arrow(c, edge(x, y, z, t.out, 0.3f), t.out, z, col); arrow(c, edge(x, y, z, t.inSide, 0.3f), t.inSide, z, col)
            }
            Kind.OUT -> {
                val side = if (t.inSide != NONE) t.inSide else ins.firstOrNull() ?: NONE
                if (side != NONE) { val e = edge(x, y, z, side, 0.5f); p.moveTo(e[0], e[1]); p.lineTo(x, y); stroke(c, p, z, green) }
                line.color = green; line.strokeWidth = width(z); c.drawCircle(x, y, z * 0.18f, line)
                if (label != null) label(c, label, x, y - z * 0.35f, z * 0.14f * labelScale, green, z * 0.95f)
            }
            Kind.MACHINE -> Unit
        }
    }

    override fun turn(c: Canvas, x: Float, y: Float, z: Float, t: Tile, active: Int, angle: Float) {
        if (active == NONE) return
        val p = Path()
        if (t.kind == Kind.SPLIT) { route(p, x, y, z, t.inSide, active); stroke(c, p, z, white); arrow(c, edge(x, y, z, active, 0.32f), active, z, white) }
        else if (t.kind == Kind.MERGE) { route(p, x, y, z, active, t.out); stroke(c, p, z, white) }
    }

    override fun flipper(c: Canvas, x: Float, y: Float, z: Float, angle: Float) = Unit

    override fun machineBlock(c: Canvas, x: Float, y: Float, size: Float, seed: Int, outdated: Boolean) {
        // the border on the cell's edge
        rect.set(x, y, x + size, y + size)
        fill.color = rgba(28, 31, 36, 1.0); c.drawRect(rect, fill)
        line.color = grey; line.strokeWidth = max(1f, size * 0.02f); line.pathEffect = null; line.strokeCap = Paint.Cap.BUTT
        rect.inset(line.strokeWidth / 2, line.strokeWidth / 2); c.drawRect(rect, line); line.strokeCap = Paint.Cap.ROUND
        if (outdated) { fill.color = amber; c.drawCircle(x + size * 0.88f, y + size * 0.12f, size * 0.07f, fill) }
    }

    override fun connector(c: Canvas, pts: FloatArray, z: Float) {
        val p = Path(); p.moveTo(pts[0], pts[1]); var k = 2
        while (k < pts.size) { p.lineTo(pts[k], pts[k + 1]); k += 2 }
        stroke(c, p, z, white)
    }

    override fun taper(c: Canvas, tri: FloatArray, z: Float) {
        val q = Path()
        q.moveTo(tri[0], tri[1]); q.quadTo(tri[8], tri[9], tri[2], tri[3])       // out: the parent's line to one end
        q.quadTo(tri[10], tri[11], tri[4], tri[5])                                // in: that end to the port's line
        q.quadTo(tri[10], tri[11], tri[6], tri[7])                                // in: the port's line to the other end
        q.quadTo(tri[8], tri[9], tri[0], tri[1]); q.close()                       // out: back to the parent's line
        fill.color = grey; c.drawPath(q, fill)
    }

    override fun exitTag(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, tx: Float, ty: Float, z: Float, label: String, rest: Boolean) {
        line.color = grey; line.strokeWidth = max(1.2f, z * 0.04f)
        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(z * 0.04f, z * 0.1f), 0f)
        c.drawLine(x0, y0, x1, y1, line); line.pathEffect = null
        text.textSize = z * 0.2f
        val w = max(z * 0.5f, text.measureText(label) + z * 0.2f); val h = z * 0.34f
        rect.set(tx - w / 2, ty - h / 2, tx + w / 2, ty + h / 2)
        line.color = if (rest) grey else green; line.strokeWidth = max(1f, z * 0.03f)
        c.drawRoundRect(rect, h / 2, h / 2, line)
        label(c, label, tx, ty, z * 0.2f, if (rest) grey else green, w)
    }

    override fun machineBox(c: Canvas, l: Float, t: Float, r: Float, b: Float, z: Float) {
        line.color = rgba(154, 160, 168, 0.5); line.strokeWidth = max(1f, z * 0.025f)
        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(z * 0.12f, z * 0.1f), 0f)
        c.drawRect(l, t, r, b, line); line.pathEffect = null
    }

    override fun heatGlow(c: Canvas, x: Float, y: Float, z: Float, heat: Float) {
        val h = z * 0.5f
        fill.color = rgba(255, 107, 94, 0.08 + 0.3 * heat.coerceIn(0f, 1f))
        rect.set(x - h, y - h, x + h, y + h); c.drawRect(rect, fill)
    }

    override fun marble(c: Canvas, x: Float, y: Float, r: Float, alpha: Float, waiting: Boolean) {
        fill.color = rgba(191, 233, 255, alpha.toDouble())
        c.drawCircle(x, y, r * 0.85f, fill)
        if (waiting) { line.color = Palette.bad; line.strokeWidth = max(1.5f, r * 0.3f); c.drawCircle(x, y, r * 1.08f, line) }
    }
}
