package com.schwii.divvywood.wood

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.withClip
import com.schwii.divvywood.engine.DX
import com.schwii.divvywood.engine.DY
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.NONE
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.opp
import com.schwii.divvywood.wood.Palette.rgba
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Where a channel meets side [d] of the cell centred on (x, y): f = 0.475 is the block's edge, 0.5 the cell's. */
fun edge(x: Float, y: Float, z: Float, d: Int, f: Float = 0.475f) = floatArrayOf(x + DX[d] * z * f, y + DY[d] * z * f)

/**
 * Point [t] (0..1) along the route through a cell entering on side [a] and leaving on side [b] (NONE = the centre).
 * Turns are quadratic curves bending around the centre, so marbles never take a sharp corner.
 */
fun routePoint(x: Float, y: Float, z: Float, a: Int, b: Int, t: Float): FloatArray {
    val p0 = if (a == NONE) floatArrayOf(x, y) else edge(x, y, z, a, 0.5f)
    val p2 = if (b == NONE) floatArrayOf(x, y) else edge(x, y, z, b, 0.5f)
    if (a == NONE || b == NONE || a == opp(b)) return floatArrayOf(p0[0] + (p2[0] - p0[0]) * t, p0[1] + (p2[1] - p0[1]) * t)
    val u = 1 - t
    return floatArrayOf(u * u * p0[0] + 2 * u * t * x + t * t * p2[0], u * u * p0[1] + 2 * u * t * y + t * t * p2[1])
}

/**
 * A channel's route through a cell from side [a] to side [b], run on a little past both edges: drawn clipped to its
 * block, so a groove's light and shadow carry right through to the block's edge.
 */
private fun routePath(p: Path, x: Float, y: Float, z: Float, a: Int, b: Int) {
    val e0 = edge(x, y, z, a, 0.5f); val e1 = edge(x, y, z, b, 0.5f)
    val o0 = edge(x, y, z, a, 0.85f); val o1 = edge(x, y, z, b, 0.85f)
    p.moveTo(o0[0], o0[1]); p.lineTo(e0[0], e0[1])
    if (a == opp(b)) p.lineTo(e1[0], e1[1]) else p.quadTo(x, y, e1[0], e1[1])
    p.lineTo(o1[0], o1[1])
}

fun angleOf(d: Int) = atan2(DY[d].toFloat(), DX[d].toFloat())

/** Draws walnut and maple blocks, carved channels, cups, flippers and glass marbles; a port of the prototype's renderer. */
open class Painter(private val labelFace: Typeface = Fonts.label, private val labelVariation: String? = Fonts.labelVariation) {
    /** Screen pixels per dp, for lines drawn at a fixed width on screen. */
    var unit = 1f
    /** A still picture (the icon): every branch drawn alike, no grid. */
    var still = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = labelFace
        labelVariation?.let { fontVariationSettings = it }
    }
    private val rect = RectF()
    private val path = Path()
    private val matrix = Matrix()
    private val shaders = HashMap<Bitmap, BitmapShader>()

    /** Puts [sh] on the fill paint at full opacity: a paint keeps the last alpha set on it otherwise. */
    private fun shade(sh: Shader): Paint { fill.color = android.graphics.Color.BLACK; fill.shader = sh; return fill }

    private fun shaderOf(b: Bitmap) = shaders.getOrPut(b) { BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }

    /** The linen table, its grain anchored to the board so it pans and zooms with the blocks; plus faint pencil dots. */
    open fun table(c: Canvas, w: Float, h: Float, ox: Float, oy: Float, z: Float) {
        val sh = shaderOf(Woods.table)
        matrix.reset(); matrix.setTranslate(ox, oy); matrix.preScale(z / 34f, z / 34f)
        sh.setLocalMatrix(matrix)
        c.drawRect(0f, 0f, w, h, shade(sh)); fill.shader = null
        fill.color = rgba(255, 255, 255, 0.16); c.drawRect(0f, 0f, w, h, fill)
        fill.color = rgba(90, 70, 50, 0.16)
        val gx0 = Math.floor((-ox / z).toDouble()).toInt() - 1
        val gy0 = Math.floor((-oy / z).toDouble()).toInt() - 1
        var gx = gx0
        while (gx < gx0 + w / z + 2) {
            var gy = gy0
            while (gy < gy0 + h / z + 2) { c.drawRect(ox + gx * z - 1, oy + gy * z - 1, ox + gx * z + 1, oy + gy * z + 1, fill); gy++ }
            gx++
        }
    }

    open fun blockShadow(c: Canvas, x: Float, y: Float, z: Float) {
        val s = z
        fill.color = rgba(60, 38, 20, 0.10)
        rect.set(x - s / 2 + z * 0.01f, y - s / 2 + z * 0.06f, x - s / 2 + z * 0.01f + s + z * 0.05f, y - s / 2 + z * 0.06f + s + z * 0.04f)
        c.drawRect(rect, fill)
        fill.color = rgba(60, 38, 20, 0.16)
        rect.set(x - s / 2 + z * 0.025f, y - s / 2 + z * 0.045f, x + s / 2 + z * 0.025f, y + s / 2 + z * 0.045f)
        c.drawRect(rect, fill)
    }

    private fun blockBody(c: Canvas, x: Float, y: Float, z: Float, wood: Bitmap, seed: Int, stain: Int?) {
        val s = z; val x0 = x - s / 2; val y0 = y - s / 2; val r = 0f   // blocks fill their cells: neighbours share an edge
        val rr = rand(seed)
        c.save()
        path.reset(); rect.set(x0, y0, x0 + s, y0 + s); path.addRoundRect(rect, r, r, Path.Direction.CW); c.clipPath(path)
        val sh = shaderOf(wood)
        matrix.reset(); matrix.setTranslate(x, y); matrix.preScale(z / 60f, z / 60f)
        if (rr() >= 0.5) matrix.preRotate(90f)
        matrix.preTranslate((rr() * 384).toFloat(), (rr() * 384).toFloat())
        sh.setLocalMatrix(matrix)
        c.drawRect(rect, shade(sh)); fill.shader = null
        if (stain != null) { fill.color = stain; fill.alpha = (0.78 * 255).toInt(); c.drawRect(rect, fill) }
        shade(LinearGradient(x0, y0, x0 + s, y0 + s, intArrayOf(rgba(255, 255, 255, 0.28), rgba(255, 255, 255, 0.0), rgba(80, 50, 25, 0.16)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP))
        c.drawRect(rect, fill); fill.shader = null
        stroke.strokeWidth = max(1f, z * 0.025f); stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = rgba(255, 255, 255, 0.45)
        c.drawLine(x0 + r, y0 + 1, x0 + s - r, y0 + 1, stroke); c.drawLine(x0 + 1, y0 + r, x0 + 1, y0 + s - r, stroke)
        stroke.color = rgba(80, 50, 25, 0.22)
        c.drawLine(x0 + r, y0 + s - 1, x0 + s - r, y0 + s - 1, stroke); c.drawLine(x0 + s - 1, y0 + r, x0 + s - 1, y0 + s - r, stroke)
        c.restore()
    }

    /** A routed channel: cut edge, recessed floor, shade under the upper wall, light on the lower lip. */
    /** A channel's width: the same on screen at every zoom and inside machines, but never wider than a block can hold. */
    private fun chan(z: Float) = minOf(12f * unit, z * 0.55f)

    private val atop = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)

    /**
     * A routed channel, a rounded groove lit from the top left: a faint rim, the top-left wall in the soft shadow the
     * rim casts, the floor and the bottom-right wall catching the light, every edge feathered. Walnut cuts darker
     * than maple.
     */
    private fun carve(c: Canvas, z: Float, p: Path, dark: Boolean, bad: Boolean = false) {
        val w = chan(z); val s = w * 0.24f
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeJoin = Paint.Join.ROUND; stroke.pathEffect = null
        stroke.color = if (dark) rgba(30, 17, 8, 0.45) else rgba(110, 74, 42, 0.3); stroke.strokeWidth = w * 1.08f; c.drawPath(p, stroke)
        c.saveLayer(null, null)
        // the groove in shadow, then the light falling on all of it but the top-left wall
        stroke.color = if (bad) rgba(165, 75, 62, 1.0) else if (dark) rgba(48, 29, 16, 1.0) else rgba(168, 126, 84, 1.0); stroke.strokeWidth = w; c.drawPath(p, stroke)
        stroke.xfermode = atop
        // the light's edges feathered, so the shadow fades softly into the floor
        stroke.maskFilter = android.graphics.BlurMaskFilter(s * 0.9f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.translate(s, s)
        stroke.color = if (bad) rgba(214, 120, 108, 1.0) else if (dark) rgba(78, 51, 32, 1.0) else rgba(206, 168, 124, 1.0); c.drawPath(p, stroke)
        c.translate(s * 0.9f, s * 0.9f)
        stroke.color = if (dark) rgba(110, 78, 54, 1.0) else rgba(230, 202, 162, 1.0); stroke.strokeWidth = w * 0.55f; c.drawPath(p, stroke)
        c.translate(-s * 1.9f, -s * 1.9f)
        stroke.maskFilter = null
        stroke.xfermode = null
        c.restore()
    }

    private fun hollow(c: Canvas, x: Float, y: Float, r: Float, deep: Boolean) {
        shade(RadialGradient(x - r * 0.25f, y - r * 0.35f, r, intArrayOf(
            if (deep) rgba(30, 18, 10, 0.9) else rgba(70, 42, 20, 0.45),
            if (deep) rgba(50, 30, 16, 0.75) else rgba(110, 72, 40, 0.25),
            rgba(255, 248, 235, 0.3)), floatArrayOf(0.1f, 0.8f, 1f), Shader.TileMode.CLAMP))
        c.drawCircle(x, y, r, fill); fill.shader = null
    }

    open fun burnText(c: Canvas, s: String, x: Float, y: Float, size: Float, maxWidth: Float = Float.MAX_VALUE, light: Boolean = false) {
        text.textSize = size
        text.color = if (light) rgba(255, 240, 215, 0.7) else rgba(59, 38, 22, 0.75)
        val w = text.measureText(s)
        c.save()
        if (w > maxWidth) { c.translate(x, 0f); c.scale(maxWidth / w, 1f); c.translate(-x, 0f) }
        val fm = text.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, text)
        c.restore()
    }

    /**
     * One block. [ins] / [outs] are the sides its channels connect to; [bad] tints overloaded channels;
     * [label] is burned onto inputs and outputs ("IN", "A 1/2").
     */
    open fun tile(c: Canvas, x: Float, y: Float, z: Float, t: Tile, ins: List<Int>, outs: List<Int>, seed: Int, bad: Boolean = false, label: String? = null, labelScale: Float = 1f, slab: Boolean = false) {
        val mech = t.kind == Kind.SPLIT || t.kind == Kind.MERGE
        val stain = when (t.kind) { Kind.SRC -> Palette.stainSrc; Kind.OUT -> Palette.stainOut; else -> null }
        // on the board each piece is its own block; inside a machine its channels are carved into the machine's slab
        if (!slab) blockBody(c, x, y, z, if (mech) Woods.walnut else Woods.maple, seed, stain)
        val p = Path()
        c.save(); c.clipRect(x - z / 2, y - z / 2, x + z / 2, y + z / 2)   // channels run on past the edge, cut at the block
        when (t.kind) {
            Kind.BELT -> {
                routePath(p, x, y, z, t.from, t.out); carve(c, z, p, false, bad)
            }
            Kind.SPLIT -> if (outs.isNotEmpty()) {
                outs.forEach { routePath(p, x, y, z, t.inSide, it) }; carve(c, z, p, !slab, bad)
            } else stub(c, p, x, y, z, t.inSide, true)
            Kind.MERGE -> {
                if (ins.isNotEmpty()) { ins.forEach { routePath(p, x, y, z, it, t.out) }; carve(c, z, p, !slab, bad) }
                else stub(c, p, x, y, z, t.out, false)
            }
            Kind.SRC -> {
                val e = edge(x, y, z, t.out, 0.85f); p.moveTo(x, y); p.lineTo(e[0], e[1]); carve(c, z, p, false)
                hollow(c, x, y, z * 0.27f, false)
                burnText(c, label ?: "IN", x, y - z * 0.33f, z * 0.15f * labelScale)
            }
            Kind.OUT -> {
                // an output cup: only drawn for the store icon; in the game, ways out are the build's sides
                val side = if (t.inSide != NONE) t.inSide else ins.firstOrNull() ?: NONE
                if (side != NONE) { val e = edge(x, y, z, side); p.moveTo(e[0], e[1]); p.lineTo(x, y); carve(c, z, p, false) }
                hollow(c, x, y, z * 0.23f, true)
                if (label != null) burnText(c, label, x, y - z * 0.35f, z * 0.14f * labelScale, z * 0.84f)
            }
            Kind.MACHINE -> Unit   // drawn by [machine], with its build inside
            Kind.BRIDGE -> {
                // the across lane is carved into the block; the other runs over it on a raised plank
                routePath(p, x, y, z, opp(t.out), t.out); carve(c, z, p, false, bad)
                val vertical = t.inSide % 2 == 1
                val hw = z * 0.2f; val hl = z * 0.475f
                if (vertical) rect.set(x - hw, y - hl, x + hw, y + hl) else rect.set(x - hl, y - hw, x + hl, y + hw)
                fill.color = rgba(60, 38, 20, 0.28)
                rect.offset(z * 0.03f, z * 0.05f); c.drawRoundRect(rect, z * 0.05f, z * 0.05f, fill); rect.offset(-z * 0.03f, -z * 0.05f)
                shade(LinearGradient(rect.left, rect.top, rect.right, rect.bottom, 0xFFF3E4C4.toInt(), 0xFFDCC193.toInt(), Shader.TileMode.CLAMP))
                c.drawRoundRect(rect, z * 0.05f, z * 0.05f, fill); fill.shader = null
                val q = Path(); routePath(q, x, y, z, opp(t.inSide), t.inSide); carve(c, z * 0.8f, q, false, bad)
            }
        }
        c.restore()
    }

    /**
     * A machine's block: walnut, with a pale floor its build is drawn onto, filling the square at ([x], [y]) of side
     * [size] (top left). An [outdated] machine wears an amber dot: its level has a better build now.
     */
    open fun machineBlock(c: Canvas, x: Float, y: Float, size: Float, seed: Int, outdated: Boolean) {
        // one maple slab filling the cell, its build carved into it, a fine walnut edge marking it as a machine
        val cx = x + size / 2; val cy = y + size / 2
        blockBody(c, cx, cy, size, Woods.maple, seed, rgba(214, 190, 150, 1.0))
        rect.set(x, y, x + size, y + size)
        stroke.color = machineEdge; stroke.strokeWidth = minOf(1.6f * unit, size * 0.06f); stroke.pathEffect = null
        rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2); c.drawRect(rect, stroke)
        if (outdated) {
            fill.color = rgba(212, 162, 79, 1.0); c.drawCircle(cx + size * 0.38f, cy - size * 0.38f, size * 0.08f, fill)
            stroke.color = rgba(59, 38, 22, 0.7); stroke.strokeWidth = max(1f, size * 0.02f); c.drawCircle(cx + size * 0.38f, cy - size * 0.38f, size * 0.08f, stroke)
        }
    }

    /** A machine's border, and the flares where its sides become ports. */
    private val machineEdge = rgba(98, 66, 44, 1.0)

    /** A faint dot where grid lines meet, inside a machine: none in wood, where a machine is one plain slab. */
    open fun gridDot(c: Canvas, x: Float, y: Float) = Unit

    /** A machine's input or way out: a channel along the points [pts] (x, y pairs) from its cell's edge, at cell size [z]. */
    open fun connector(c: Canvas, pts: FloatArray, z: Float) {
        val q = Path(); q.moveTo(pts[0], pts[1]); var k = 2
        while (k < pts.size) { q.lineTo(pts[k], pts[k + 1]); k += 2 }
        carve(c, z, q, false)
    }

    /**
     * Where a splitter or converger sends or takes its next marble: [active] is that side, [angle] its direction
     * eased for animation. Wood shows a flipper.
     */
    open fun turn(c: Canvas, x: Float, y: Float, z: Float, t: Tile, active: Int, angle: Float) = flipper(c, x, y, z, angle)

    /**
     * Where a way out leaves the build: a dotted line from the piece at ([x0], [y0]) to the box's edge at ([x1], [y1]),
     * then a small green tag past the edge with [label] ("A 1/4", "rest").
     */
    open fun exitTag(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, tx: Float, ty: Float, z: Float, label: String, rest: Boolean) {
        stroke.color = rgba(95, 62, 32, 0.4); stroke.strokeWidth = max(1.5f, z * 0.05f); stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = 1.2f * unit; stroke.color = rgba(95, 62, 32, 0.3)
        c.drawLine(x0, y0, x1, y1, stroke)
        text.textSize = z * 0.2f
        val w = max(z * 0.5f, text.measureText(label) + z * 0.2f); val h = z * 0.34f
        rect.set(tx - w / 2, ty - h / 2, tx + w / 2, ty + h / 2)
        fill.color = if (rest) Palette.stainDump else Palette.stainOut
        c.drawRoundRect(rect, h / 2, h / 2, fill)
        burnText(c, label, tx, ty, z * 0.2f, w)
    }

    /** The box around the machine, where ports sit: a faint dashed frame. */
    open fun machineBox(c: Canvas, l: Float, t: Float, r: Float, b: Float, z: Float) {
        stroke.color = rgba(95, 62, 32, 0.28); stroke.strokeWidth = 1f * unit; stroke.strokeCap = Paint.Cap.BUTT
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(z * 0.16f, z * 0.1f), 0f)
        c.drawRect(l, t, r, b, stroke); stroke.pathEffect = null
    }

    /** An unconnected splitter or converger: a channel stub toward its marked side and a small hollow. */
    private fun stub(c: Canvas, p: Path, x: Float, y: Float, z: Float, side: Int, inward: Boolean) {
        val e = edge(x, y, z, side)
        if (inward) { p.moveTo(e[0], e[1]); p.lineTo(x, y) } else { p.moveTo(x, y); p.lineTo(e[0], e[1]) }
        carve(c, z, p, true)
        hollow(c, x, y, z * 0.2f, false)
    }

    /** Round robin: a maple paddle on a brass pin, pointing down the channel the next marble takes. */
    /**
     * Round robin: a slim maple paddle on a brass pin at the fork, sized to the channels, lying in the channel the
     * next marble takes.
     */
    open fun flipper(c: Canvas, x: Float, y: Float, z: Float, angle: Float) {
        val w = chan(z); val l = w * 1.9f; val t = w * 0.36f
        c.save(); c.translate(x, y); c.rotate(Math.toDegrees(angle.toDouble()).toFloat())
        fill.color = rgba(30, 18, 8, 0.3)
        rect.set(w * 0.05f, -t / 2 + w * 0.12f, l + w * 0.05f, t / 2 + w * 0.12f); c.drawRoundRect(rect, t / 2, t / 2, fill)
        shade(LinearGradient(0f, -t / 2, 0f, t / 2, 0xFFF6E7C6.toInt(), 0xFFD6B98A.toInt(), Shader.TileMode.CLAMP))
        rect.set(0f, -t / 2, l, t / 2); c.drawRoundRect(rect, t / 2, t / 2, fill); fill.shader = null
        stroke.color = rgba(90, 60, 30, 0.5); stroke.strokeWidth = max(0.8f, w * 0.06f); c.drawRoundRect(rect, t / 2, t / 2, stroke)
        val pin = w * 0.28f
        shade(RadialGradient(-pin * 0.3f, -pin * 0.3f, pin, intArrayOf(0xFFFFF2C8.toInt(), 0xFFCFA24E.toInt(), 0xFF7A561C.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP))
        c.drawCircle(0f, 0f, pin, fill); fill.shader = null
        c.restore()
    }

    /** A block where marbles are being held up: a red wash, stronger the more often lately ([heat], 0–1). */
    open fun heatGlow(c: Canvas, x: Float, y: Float, z: Float, heat: Float) {
        val h = z * 0.5f
        fill.color = rgba(192, 84, 74, (0.12 + 0.45 * heat.coerceIn(0f, 1f)))
        rect.set(x - h, y - h, x + h, y + h); c.drawRect(rect, fill)
    }

    /** A clear glass marble with its shadow, as wide as the channels of a cell [z] across; [alpha] fades it out. */
    open fun marble(c: Canvas, x: Float, y: Float, z: Float, alpha: Float = 1f, waiting: Boolean = false) {
        val r = chan(z) * 0.5f   // it fills the channel it rolls in
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        fill.color = rgba(50, 30, 15, 0.25); fill.alpha = (fill.alpha * alpha).toInt()
        rect.set(x + r * 0.25f - r * 0.95f, y + r * 0.45f - r * 0.55f, x + r * 0.25f + r * 0.95f, y + r * 0.45f + r * 0.55f); c.drawOval(rect, fill)
        shade(RadialGradient(x - r * 0.35f, y - r * 0.4f, r * 1.35f, intArrayOf(rgba(255, 255, 255, 0.95), rgba(190, 225, 222, 0.8), rgba(80, 140, 140, 0.9)),
            floatArrayOf(0.04f, 0.25f, 0.75f), Shader.TileMode.CLAMP))
        fill.alpha = a
        c.drawCircle(x, y, r, fill); fill.shader = null; fill.alpha = 255
        // a marble that couldn't move on this tick wears a red rim
        if (waiting) { stroke.color = rgba(192, 84, 74, 0.95); stroke.strokeWidth = max(1.5f, r * 0.3f); c.drawCircle(x, y, r * 1.08f, stroke) }
    }

    /** The belt being drawn: a soft stroke through cell centres, [color] red when it can't be placed. */
    open fun previewPath(c: Canvas, pts: List<Float>, width: Float, color: Int) {
        path.reset(); path.moveTo(pts[0], pts[1])
        for (k in 2 until pts.size step 2) path.lineTo(pts[k], pts[k + 1])
        stroke.color = color; stroke.alpha = (0.55 * 255).toInt(); stroke.strokeWidth = width
        stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, stroke); stroke.alpha = 255
    }

    /** The Select-mode box: a faint fill with a dashed edge. */
    open fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = color; fill.alpha = (0.06 * 255).toInt(); c.drawRect(l, t, r, b, fill); fill.alpha = 255
        stroke.color = color; stroke.strokeWidth = 3f; stroke.strokeCap = Paint.Cap.BUTT
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
        c.drawRect(l, t, r, b, stroke); stroke.pathEffect = null
    }

    /** A selection or placement outline around one cell. */
    open fun outline(c: Canvas, x: Float, y: Float, z: Float, color: Int, width: Float, dashed: Boolean = false) {
        stroke.color = color; stroke.strokeWidth = width; stroke.strokeCap = Paint.Cap.BUTT
        stroke.pathEffect = if (dashed) android.graphics.DashPathEffect(floatArrayOf(z * 0.12f, z * 0.08f), 0f) else null
        rect.set(x - z * 0.5f + 1, y - z * 0.5f + 1, x + z * 0.5f - 1, y + z * 0.5f - 1)
        c.drawRoundRect(rect, z * 0.14f, z * 0.14f, stroke)
        stroke.pathEffect = null
    }
}
