package com.schwii.divvyrune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schwii.divvyrune.board.BoardState
import com.schwii.divvyrune.game.Game
import com.schwii.divvyrune.sound.Score
import com.schwii.divvyrune.sound.Tide
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The runes, drawn as straight strokes in a unit box (x right, y down): five for the background tides (filter ᛚ,
 * swell ᚹ, spread ᛋ, flange ᚠ, echo ᛞ), then seven for the ensemble's voices (ᛗ ᚲ ᚷ ᛟ ᛇ ᚱ ᛏ). Each is a list of
 * polylines.
 */
private val GLYPHS: List<List<FloatArray>> = listOf(
    listOf(floatArrayOf(.4f, 0f, .4f, 1f), floatArrayOf(.4f, 0f, .75f, .3f)),                                               // ᛚ
    listOf(floatArrayOf(.32f, 1f, .32f, 0f, .7f, .25f, .32f, .5f)),                                                         // ᚹ
    listOf(floatArrayOf(.68f, 0f, .32f, .35f, .68f, .65f, .32f, 1f)),                                                       // ᛋ
    listOf(floatArrayOf(.35f, 0f, .35f, 1f), floatArrayOf(.35f, .3f, .72f, .05f), floatArrayOf(.35f, .58f, .72f, .33f)),    // ᚠ
    listOf(floatArrayOf(.22f, 0f, .22f, 1f), floatArrayOf(.78f, 0f, .78f, 1f), floatArrayOf(.22f, 0f, .78f, 1f), floatArrayOf(.78f, 0f, .22f, 1f)), // ᛞ
    listOf(floatArrayOf(.25f, 1f, .25f, 0f, .75f, .42f), floatArrayOf(.75f, 1f, .75f, 0f, .25f, .42f)),                     // ᛗ
    listOf(floatArrayOf(.68f, 0f, .3f, .5f, .68f, 1f)),                                                                     // ᚲ
    listOf(floatArrayOf(.25f, 0f, .75f, 1f), floatArrayOf(.75f, 0f, .25f, 1f)),                                             // ᚷ
    listOf(floatArrayOf(.5f, 0f, .8f, .33f, .27f, 1f), floatArrayOf(.5f, 0f, .2f, .33f, .73f, 1f)),                         // ᛟ
    listOf(floatArrayOf(.5f, 0f, .5f, 1f), floatArrayOf(.5f, 0f, .76f, .22f), floatArrayOf(.5f, 1f, .24f, .78f)),           // ᛇ
    listOf(floatArrayOf(.3f, 1f, .3f, 0f, .7f, .25f, .3f, .5f, .7f, 1f)),                                                   // ᚱ
    listOf(floatArrayOf(.5f, 0f, .5f, 1f), floatArrayOf(.18f, .32f, .5f, 0f, .82f, .32f)),                                  // ᛏ
)

/** Draws rune [glyph] in the square at ([x], [y]) of side [s]. */
fun DrawScope.rune(glyph: Int, x: Float, y: Float, s: Float, color: Color, width: Float) {
    for (line in GLYPHS[glyph % GLYPHS.size]) {
        for (k in 0 until line.size / 2 - 1) {
            drawLine(color, Offset(x + line[2 * k] * s, y + line[2 * k + 1] * s), Offset(x + line[2 * k + 2] * s, y + line[2 * k + 3] * s), width, StrokeCap.Round)
        }
    }
}

/** The top strip's way into the runes: ᚱ in a ring. */
fun DrawScope.runesIcon(c: Color) {
    drawCircle(c, 10f, Offset(12f, 12f), style = Stroke(1.6f))
    rune(10, 7f, 6.5f, 11f, c, 1.6f)
}

/**
 * One rune's ring: the tide's position as a dot going round, how hard it pulls now as an arc from the top, the
 * rune in the middle. A turning rune is lit in the accent colour and glides between ticks; a held one goes dim and
 * stops. It redraws every board frame.
 */
@Composable
private fun RuneRing(board: BoardState, score: Score, index: Int, tide: Tide, modifier: Modifier) {
    val layer = index >= Score.BG.size
    val dim = Dim; val line = Line; val accent = Src
    Canvas(modifier) {
        board.frame   // read while drawing: the ring redraws every frame without recomposing
        val held = tide.held
        val a = tide.angleAt(if (board.playing) board.alpha else 0f)
        val v = sin(a).toFloat()
        val gain = if (layer) score.layerGain(index - Score.BG.size) else abs(v)
        val ink = if (held) dim else accent
        val r = size.minDimension * 0.4f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(if (held) line else accent, r, c, alpha = if (held) 1f else 0.45f, style = Stroke(1.5.dp.toPx()))
        if (layer) drawCircle(ink, r, c, alpha = (if (held) 0.08f else 0.15f) * gain)
        drawArc(ink, -90f, v * 180, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r),
            alpha = if (held) 0.35f else 0.3f + 0.7f * gain, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        val p = Offset(c.x + cos(a).toFloat() * r, c.y + sin(a).toFloat() * r)
        val dot = (if (held) 3f else 3f + 3f * gain).dp.toPx()
        drawCircle(ink, dot, p)
        val s = r * 0.95f
        rune(tide.glyph, c.x - s / 2, c.y - s / 2, s, ink.copy(alpha = if (held) 0.6f else 1f), 2.dp.toPx())
    }
}

/** The runes: every tide turning under this build's sound. Tapping one holds it where it is, or lets it turn again. */
@Composable
fun ColumnScope.RunesSheet(game: Game) {
    val score = game.score
    Text("Runes", style = Body, color = Ink, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    if (!game.soundOn) Text("Sound off", style = Body, color = Dim, fontSize = 14.sp)
    val tides = score.tides
    var taps by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }   // re-reads held states after a tap
    if (tides.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = (maxWidth / 76.dp).toInt().coerceIn(3, 6)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tides.indices.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    for (i in row) {
                        val t = tides[i]
                        RuneRing(game.board, score, i, t, Modifier.size(68.dp).clip(CircleShape).clickable { score.toggle(t.key); taps++ }
                            .semantics { taps; contentDescription = "Rune ${i + 1}"; stateDescription = if (t.held) "Held" else "Turning" })
                    }
                    repeat(cols - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.size(68.dp)) }
                }
            }
        }
    }
}
