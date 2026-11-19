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
import androidx.compose.ui.platform.LocalViewConfiguration

/**
 * The live board: the table, blocks and rolling marbles. Two fingers pan and pinch-zoom; one finger goes to the
 * [editor] (tap, hold, drag) when there is one.
 */
@Composable
fun Board(state: BoardState, modifier: Modifier = Modifier, editor: Editor? = null, reduceMotion: Boolean = false, animate: Boolean = true) {
    val renderer = remember(state, editor) { BoardRenderer(state, reduceMotion = reduceMotion, editor = editor) }
    val slop = LocalViewConfiguration.current.touchSlop
    if (animate) LaunchedEffect(state) {
        var last = 0L
        while (true) withFrameNanos { t ->
            if (last != 0L) state.advance((t - last) / 1_000_000f)
            editor?.tick(t / 1_000_000)
            last = t
        }
    }
    Canvas(
        modifier
            .onSizeChanged { if (it.width > 0) state.setView(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(state, editor) {
                editor?.slop = slop
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var single = true
                    editor?.down(first.position.x, first.position.y, first.uptimeMillis)
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed >= 2) {
                            if (single) { editor?.cancel(); single = false }
                            val c = ev.calculateCentroid()
                            val pan = ev.calculatePan()
                            state.panZoom(c.x, c.y, ev.calculateZoom(), pan.x, pan.y)
                        } else if (single) {
                            val ch = ev.changes.first()
                            if (ch.pressed) editor?.drag(ch.position.x, ch.position.y) else editor?.up(ch.position.x, ch.position.y)
                        }
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                }
            },
    ) {
        state.frame; state.camVersion; state.version   // redraw on every frame and change
        drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt()) }
    }
}
