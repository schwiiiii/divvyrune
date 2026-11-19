package com.schwii.divvywood.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged

/** The live board: the table, blocks and rolling marbles. Two fingers pan and pinch-zoom. */
@Composable
fun Board(state: BoardState, modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val renderer = remember(state) { BoardRenderer(state, reduceMotion = reduceMotion) }
    val fitted = remember(state) { booleanArrayOf(false) }
    LaunchedEffect(state) {
        var last = 0L
        while (true) withFrameNanos { t ->
            if (last != 0L) state.advance((t - last) / 1_000_000f)
            last = t
        }
    }
    Canvas(
        modifier
            .onSizeChanged { if (!fitted[0] && it.width > 0) { state.fit(it.width.toFloat(), it.height.toFloat()); fitted[0] = true } }
            .pointerInput(state) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2) {
                            val c = ev.calculateCentroid()
                            val pan = ev.calculatePan()
                            state.panZoom(c.x, c.y, ev.calculateZoom(), pan.x, pan.y)
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
    ) {
        state.frame; state.camVersion; state.version   // redraw on every frame and change
        drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt()) }
    }
}
