package com.schwii.divvywood.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.ceil

/** Fewer than this many solutions make no useful histogram. */
const val MIN_SAMPLES = 5

/**
 * How [values] (many solutions of the level) spread out, as bars, with [yours] in orange and a dashed line at the
 * average. At most 12 bars; wide ranges share bars.
 */
@Composable
fun Histogram(values: List<Int>, yours: Int, modifier: Modifier) {
    val lo = minOf(values.min(), yours); val hi = maxOf(values.max(), yours)
    val width = maxOf(1, ceil((hi - lo + 1) / 12.0).toInt())
    val bins = (hi - lo) / width + 1
    val counts = IntArray(bins).also { c -> values.forEach { c[(it - lo) / width]++ } }
    val mine = (yours - lo) / width
    val avg = values.average()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val bw = size.width / bins
            val top = counts.max().coerceAtLeast(1)
            counts.forEachIndexed { i, n ->
                val h = (size.height - 2) * n / top
                drawRect(if (i == mine) Src else Line, Offset(i * bw + 1, size.height - h), Size(bw - 2, h))
            }
            if (counts[mine] == 0) drawRect(Src, Offset(mine * bw + 1, size.height - 3.dp.toPx()), Size(bw - 2, 3.dp.toPx()))
            val ax = ((avg - lo) / width + 0.5).toFloat() * bw
            drawLine(Dim, Offset(ax, 0f), Offset(ax, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
        }
        Text("avg ${String.format(Locale.ROOT, "%.1f", avg)} · ${values.size} solutions", style = BodyTnum, color = Dim, fontSize = 10.sp)
    }
}
