package com.schwii.divvywood.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.schwii.divvywood.engine.LETTERS
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.engine.encodeGrid
import com.schwii.divvywood.engine.encodeLine
import com.schwii.divvywood.game.Game
import com.schwii.divvywood.game.Sheet

/** The open sheet, rising from the bottom over a dimmed game; tapping the dimmed part or Back closes it. */
@Composable
fun SheetHost(game: Game) {
    val sheet = game.sheet ?: return
    BackHandler { game.closeSheet() }
    val none = remember { MutableInteractionSource() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Scrim).clickable(none, null) { game.closeSheet() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.82f)
                .background(Panel, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .drawBehind { drawLine(Line, Offset(14.dp.toPx(), 0f), Offset(size.width - 14.dp.toPx(), 0f), 1.dp.toPx()) }
                .clickable(none, null) {}   // taps on the card stay on the card
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (sheet) {
                is Sheet.Result -> ResultSheet(game, sheet)
                Sheet.Levels -> LevelsSheet(game)
                Sheet.Text -> TextSheet(game)
            }
        }
    }
}

@Composable
private fun Title(text: String, color: Color = Ink) =
    Text(text, style = Body, color = color, fontWeight = FontWeight.Bold, fontSize = 20.sp)

@Composable
private fun Buttons(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

@Composable
private fun RowScope.SheetButton(label: String, go: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).heightIn(min = 48.dp).background(if (go) Out else Raise, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Body, color = if (go) On else Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

// ---------- result ----------

@Composable
private fun ColumnScope.ResultSheet(game: Game, r: Sheet.Result) {
    val v = r.verdict
    val title = if (game.level.sandbox) "Measured" else if (v.solved) "Solved" else "Not yet"
    Title(title, if (v.solved) Out else Src)
    if (v.rows.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TableRow(listOf("out", "exact", "target", "measured"), null, header = true)
            for (row in v.rows) {
                TableRow(
                    listOf(LETTERS[row.id].toString(), row.exact.toString(), row.target?.toString() ?: "–", if (row.measured.isNaN()) "–" else f3(row.measured)),
                    if (row.target == null) null else row.ok,
                )
            }
        }
    }
    if (v.reasons.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (reason in v.reasons) Row {
                Text("•", style = Body, fontSize = 14.sp, modifier = Modifier.width(18.dp), textAlign = TextAlign.Center)
                Text(reason, style = Body, fontSize = 14.sp, lineHeight = 1.5.em)
            }
        }
    }
    if (v.skips > 0) Text("Splitters skipped a jammed output ${v.skips} times during the run.", style = Body, color = Dim, fontSize = 14.sp)
    val sc = r.score
    val best = r.best
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScoreCard("parts", sc.parts.toString(), "${sc.belts} belts · ${sc.splits} split · ${sc.merges} join")
        ScoreCard("area", sc.area.toString(), "${sc.w}×${sc.h}")
        ScoreCard("best", best?.parts?.toString() ?: "–", if (best != null) "area ${best.small} to ${best.large}" else "not solved yet")
    }
    Buttons {
        SheetButton("Keep building") { game.closeSheet() }
        if (r.next) SheetButton("Next level", go = true) { game.loadLevel(game.levelIndex + 1) }
    }
}

@Composable
private fun TableRow(cells: List<String>, ok: Boolean?, header: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, c ->
            val m = if (i == 0) Modifier.width(30.dp) else Modifier.weight(1f)
            if (header) Text(c.uppercase(), m, style = Body, color = Dim, fontSize = 11.sp, letterSpacing = 0.06.em)
            else Text(c, m, style = BodyTnum, fontSize = 14.sp, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Medium)
        }
        Text(
            when (ok) { true -> "✓"; false -> "✗"; null -> "" }, Modifier.width(16.dp),
            style = Body, color = if (ok == true) Out else Bad, fontSize = 14.sp,
        )
    }
}

@Composable
private fun RowScope.ScoreCard(label: String, value: String, note: String) {
    Column(Modifier.weight(1f).background(Raise, RoundedCornerShape(8.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Body, color = Dim, fontSize = 11.sp)
        Text(value, style = BodyTnum, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(note, style = BodyTnum, color = Dim, fontSize = 11.sp)
    }
}

// ---------- levels ----------

@Composable
private fun ColumnScope.LevelsSheet(game: Game) {
    Title("Levels")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LEVELS.forEachIndexed { i, l ->
            val b = game.best(i)
            val goal = if (l.sandbox) "free build" else l.out.joinToString(" + ") + if (l.dump > 0) " + dump" else ""
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Raise, RoundedCornerShape(8.dp))
                    .then(if (i == game.levelIndex) Modifier.border(1.5.dp, Src, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { game.loadLevel(i) }.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(twoDigits(i), Modifier.width(30.dp), style = Body, color = Src, fontSize = 14.sp)
                Column(Modifier.weight(1f)) {
                    Text(l.name, style = Body, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(goal, style = Body, color = Dim, fontSize = 11.sp)
                }
                if (b != null) Column(horizontalAlignment = Alignment.End) {
                    Text(
                        buildAnnotatedString { withStyle(SpanStyle(color = Out)) { append("✓") }; append(" ${b.parts} parts") },
                        style = BodyTnum, color = Dim, fontSize = 11.sp,
                    )
                    Text("area ${b.small}–${b.large}", style = BodyTnum, color = Dim, fontSize = 11.sp)
                }
            }
        }
    }
    Buttons {
        SheetButton("Build as text") { game.showText() }
        SheetButton("Close") { game.closeSheet() }
    }
}

// ---------- build as text ----------

/** Turns `<b>…</b>` spans into bold ink on dim text. */
private fun legend(s: String): AnnotatedString = buildAnnotatedString {
    var rest = s
    while (true) {
        val a = rest.indexOf("<b>")
        if (a < 0) { append(rest); break }
        append(rest.substring(0, a))
        val b = rest.indexOf("</b>", a)
        withStyle(SpanStyle(color = Ink, fontWeight = FontWeight.Bold)) { append(rest.substring(a + 3, b)) }
        rest = rest.substring(b + 4)
    }
}

@Composable
private fun ColumnScope.TextSheet(game: Game) {
    val level = game.level
    val context = LocalContext.current
    var text by remember { mutableStateOf(encodeGrid(game.board.tiles)) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    fun copy(which: String, s: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Divvywood build", s))
        copied = which
    }
    Title("Build as text")
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(Page, RoundedCornerShape(8.dp)).border(1.dp, Line, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        BasicTextField(
            text, { text = it; error = null; copied = null },
            Modifier.widthIn(min = maxWidth).heightIn(min = 144.dp).padding(10.dp),
            textStyle = Body.copy(fontSize = 16.sp, lineHeight = 1.5.em),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            cursorBrush = SolidColor(Ink),
        )
    }
    error?.let { Text(it, style = Body, color = Bad, fontSize = 14.sp) }
    Buttons {
        SheetButton(if (copied == "grid") "Copied" else "Copy") { copy("grid", text) }
        SheetButton(if (copied == "line") "Copied" else "Copy as one line") {
            val d = decodeGrid(text)
            copy("line", d.tiles?.let { encodeLine(it) } ?: text.trim().lines().joinToString("/") { it.trim().split(Regex("\\s+")).joinToString(",") })
        }
    }
    Buttons { SheetButton("Build from text", go = true) { error = game.buildFromText(text) } }
    val outs = level.out.size
    Text(
        legend(
            "<b>I</b> input, <b>S</b> splitter, <b>C</b> converger, <b>O</b> bypass, <b>F</b> final output (<b>F</b>…<b>2</b>, <b>F</b>…<b>3</b> for outputs B, C), then a side: <b>T R B L</b>. " +
                "I and C name the side they output to; S, O and F the one side their input comes from. " +
                "A belt is two sides, fed from then feeding (<b>RT</b>: from the right, up). <b>..</b> is empty. " +
                "One-line codes put commas between cells and <b>/</b> between rows (<b>..,IB/CL,ST</b>); either form builds. " +
                "Building from text replaces this level's build; Undo brings it back. " +
                "This level holds 1 input, $outs final output${if (outs > 1) "s" else ""} and ${level.dump} bypass${if (level.dump == 1) "" else "es"}.",
        ),
        style = Body, color = Dim, fontSize = 12.sp, lineHeight = 1.6.em,
    )
    Buttons { SheetButton("Close") { game.closeSheet() } }
}
