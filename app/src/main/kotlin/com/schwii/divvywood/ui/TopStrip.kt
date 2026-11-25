package com.schwii.divvywood.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.schwii.divvywood.board.BoardState
import com.schwii.divvywood.engine.LETTERS
import com.schwii.divvywood.engine.metricsOf
import com.schwii.divvywood.game.Game
import java.util.Locale

internal fun f3(x: Double) = String.format(Locale.ROOT, "%.3f", x)
internal fun twoDigits(i: Int) = String.format(Locale.ROOT, "%02d", i + 1)

/** The strip on top: the level number (it opens the levels), parts and area, then the live readouts. */
@Composable
fun TopStrip(game: Game) {
    val board = game.board
    board.version   // parts and area follow the build
    val level = game.level
    val m = metricsOf(board.tiles, board.lib)
    Column(
        Modifier.fillMaxWidth().background(Panel)
            .drawBehind { drawLine(Line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Src)) { append(game.label(game.levelIndex)) }
                        append(" ▾")
                    },
                    Modifier.heightIn(min = 36.dp).clickable { game.showLevels() }.padding(top = 9.dp, end = 4.dp)
                        .semantics { contentDescription = "Levels" },
                    style = Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // in the builder: the level's design
                if (game.inBuilder) Text(
                    "Design", Modifier.heightIn(min = 36.dp).background(Raise, RoundedCornerShape(50)).clickable { game.showBuilder() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = Body, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                )
            }
            Stat("pieces", m.pieces.toString())
            Stat("footprint", if (m.area > 0) "${m.w}×${m.h}" else "0")
            val ink = Dim
            androidx.compose.foundation.Canvas(Modifier.size(36.dp).clickable { game.showSettings() }.padding(6.dp)
                .semantics { contentDescription = "Settings" }) {
                scale(size.minDimension / 24f, pivot = Offset.Zero) { gearIcon(ink) }
            }
        }
        Readouts(board)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Text(
        buildAnnotatedString { append("$label "); withStyle(SpanStyle(color = Ink, fontWeight = FontWeight.Bold)) { append(value) } },
        style = BodyTnum, color = Dim, fontSize = 12.sp, maxLines = 1,
    )
}

/**
 * Each input's measured rate and each output's measured rate against its target, with a sparkline; every rate is a
 * share of one full belt. Refreshed every 6 frames.
 */
@Composable
private fun Readouts(board: BoardState) {
    val refresh by remember(board) { derivedStateOf { if (board.checking) board.frame else board.frame / 6 } }
    refresh
    board.version
    val level = board.level
    val top = level.totalInput.toDouble().toFloat().coerceAtLeast(0.01f)   // sparklines run up to everything the inputs send
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val n = level.inputs.size + level.out.size
        val gap = 6.dp
        val fits = maxWidth >= MIN_CARD * n + gap * (n - 1)
        Row(
            (if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            val m = if (fits) Modifier.weight(1f) else Modifier.width(MIN_CARD)
            level.inputs.forEachIndexed { k, r ->
                ReadCard(
                    m,
                    tag = level.inputLabel(k), tagColor = Src,
                    value = f3(board.inputRate(k) ?: 0.0),
                    note = "rate $r",
                    blocked = board.inputBlocked(k),
                )
            }
            level.out.forEachIndexed { id, tg ->
                ReadCard(
                    m,
                    tag = LETTERS[id].toString(), tagColor = Out,
                    value = board.measured(id)?.let { f3(it) } ?: "–",
                    note = if (tg != null) "target $tg" else "free",
                    spark = board.hist.getOrNull(id)?.toList() ?: emptyList(),
                    target = tg?.let { it.n.toFloat() / it.d },
                    top = top,
                )
            }
        }
    }
}

private val MIN_CARD: Dp = 84.dp

@Composable
private fun ReadCard(
    modifier: Modifier, tag: String, tagColor: Color, value: String, note: String,
    blocked: Boolean = false, spark: List<Float>? = null, target: Float? = null, top: Float = 1f,
) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier.background(Raise, shape).then(if (blocked) Modifier.border(1.dp, Bad, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tag, style = Body, color = tagColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Column {
                Text(value, style = BodyTnum, color = if (blocked) Bad else Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                Text(note, style = BodyTnum, color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (spark != null) Sparkline(spark, target, top, Modifier.fillMaxWidth().padding(top = 2.dp).height(14.dp))
    }
}

/** A measured rate over time, against a faint line at its target, scaled so [top] reaches the card's top. */
@Composable
private fun Sparkline(values: List<Float>, target: Float?, top: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun yOf(v: Float) = h - 1 - (v / top).coerceIn(0f, 1f) * (h - 2)
        if (target != null) drawLine(Line, Offset(0f, yOf(target)), Offset(w, yOf(target)), 1.dp.toPx())
        if (values.size > 1) {
            val p = Path()
            values.forEachIndexed { j, v ->
                val x = j.toFloat() / (BoardState.HIST - 1) * w
                if (j == 0) p.moveTo(x, yOf(v)) else p.lineTo(x, yOf(v))
            }
            drawPath(p, Out, style = Stroke(1.5.dp.toPx()))
        }
    }
}
