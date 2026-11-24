package com.schwii.divvywood.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

// The prototype's toolbar icons, drawn on a 24-unit square.

private fun line(w: Float) = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)

fun DrawScope.selectIcon(c: Color) =
    drawRoundRect(c, Offset(4f, 4f), Size(16f, 16f), CornerRadius(2f), style = Stroke(2.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.5f, 2.5f))))

fun DrawScope.undoIcon(c: Color) {
    val p = Path().apply {
        moveTo(9f, 7f); lineTo(4f, 12f); lineTo(9f, 17f)
        moveTo(4f, 12f); lineTo(14f, 12f)
        arcTo(Rect(8f, 12f, 20f, 24f), -90f, 180f, false)
    }
    drawPath(p, c, style = line(2.4f))
}

fun DrawScope.playIcon(c: Color) =
    drawPath(Path().apply { moveTo(7f, 5f); lineTo(19f, 12f); lineTo(7f, 19f); close() }, c)

fun DrawScope.pauseIcon(c: Color) {
    drawRect(c, Offset(6f, 5f), Size(4f, 14f))
    drawRect(c, Offset(14f, 5f), Size(4f, 14f))
}

fun DrawScope.checkIcon(c: Color) =
    drawPath(Path().apply { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7f) }, c, style = line(2.6f))

/** A clockwise turn: three quarters of a circle from the bottom round to the right, with the arrowhead there. */
fun DrawScope.rotateIcon(c: Color) {
    drawPath(Path().apply { arcTo(Rect(5f, 5f, 19f, 19f), 90f, 270f, true) }, c, style = line(2.4f))
    drawPath(Path().apply { moveTo(15.5f, 10f); lineTo(19f, 13.5f); lineTo(22.5f, 10f) }, c, style = line(2.4f))
}

/** A bin. */
fun DrawScope.deleteIcon(c: Color) {
    val p = Path().apply {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(9f, 7f); lineTo(9.5f, 4f); lineTo(14.5f, 4f); lineTo(15f, 7f)
        moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
        moveTo(10f, 11f); lineTo(10f, 16f); moveTo(14f, 11f); lineTo(14f, 16f)
    }
    drawPath(p, c, style = line(2.2f))
}

/** Step: a play triangle against a bar, one tick forward. */
fun DrawScope.stepIcon(c: Color) {
    drawPath(Path().apply { moveTo(6f, 5f); lineTo(15f, 12f); lineTo(6f, 19f); close() }, c)
    drawRect(c, Offset(16f, 5f), Size(3f, 14f))
}

/** Settings: a cog. */
fun DrawScope.gearIcon(c: Color) {
    for (k in 0 until 8) {
        val a = Math.PI * k / 4
        val cx = 12f; val cy = 12f
        drawLine(c, Offset(cx + 6.5f * kotlin.math.cos(a).toFloat(), cy + 6.5f * kotlin.math.sin(a).toFloat()),
            Offset(cx + 9.5f * kotlin.math.cos(a).toFloat(), cy + 9.5f * kotlin.math.sin(a).toFloat()), 2.6f, StrokeCap.Round)
    }
    drawCircle(c, 6.5f, Offset(12f, 12f), style = Stroke(2.4f))
    drawCircle(c, 2.2f, Offset(12f, 12f), style = Stroke(2f))
}
