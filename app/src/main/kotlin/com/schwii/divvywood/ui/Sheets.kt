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
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.schwii.divvywood.board.BoardRenderer
import com.schwii.divvywood.board.BoardState
import com.schwii.divvywood.engine.Cell
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import com.schwii.divvywood.engine.LETTERS
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.Level
import com.schwii.divvywood.engine.Tile
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
                Sheet.Builder -> BuilderSheet(game)
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
    val title = if (v.solved) "Solved" else "Not yet"
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
        if (r.save) SheetButton("Save level", go = true) { game.saveCustom() }
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

/**
 * The levels as cards, three to a row, each under its number: solved ones outlined green with the solving build and
 * best scores; started ones with the build so far and the last Check's score; unstarted ones a question mark.
 */
@Composable
private fun ColumnScope.LevelsSheet(game: Game) {
    Title("Levels")
    CardGrid((0 until LEVELS.size).toList()) { i, m -> LevelCard(game, i, m.current(i == game.levelIndex)) }
    Text("Your levels", style = Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
    CardGrid((LEVELS.size..game.levelCount).toList()) { i, m ->
        if (i < game.levelCount) LevelCard(game, i, m.current(i == game.levelIndex)) else NewLevelCard(game, m.current(game.inBuilder))
    }
    Buttons {
        SheetButton("Build as text") { game.showText() }
        if (game.isCustom(game.levelIndex)) SheetButton("Delete ${game.label(game.levelIndex)}") { game.deleteCustom(game.levelIndex) }
        SheetButton("Close") { game.closeSheet() }
    }
}

/** The sheet opens scrolled to the level that's open. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.current(isCurrent: Boolean): Modifier {
    if (!isCurrent) return this
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { requester.bringIntoView() }
    return bringIntoViewRequester(requester)
}

@Composable
private fun CardGrid(items: List<Int>, card: @Composable (Int, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in row) card(i, Modifier.weight(1f))
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** The card that opens the level builder: a plus, and the builder's own build so far once there is one. */
@Composable
private fun NewLevelCard(game: Game, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.background(Raise, shape).clip(shape).clickable { game.openBuilder() }.padding(6.dp)
            .semantics { contentDescription = "Build a new level" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Page), contentAlignment = Alignment.Center) {
            Text("+", style = Body, color = if (game.inBuilder) Src else Dim, fontWeight = FontWeight.Bold, fontSize = 40.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("design one,", style = Body, color = Dim, fontSize = 11.sp, maxLines = 1)
            Text("solve it, save it", style = Body, color = Dim, fontSize = 11.sp, maxLines = 1)
        }
        Text("New", style = Body, color = if (game.inBuilder) Src else Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

@Composable
private fun LevelCard(game: Game, i: Int, modifier: Modifier) {
    val level = game.levelAt(i)
    val saved = game.saved(i)
    val best = saved.best
    val shown = (if (best != null) saved.solution else null) ?: saved.build
    val tiles = remember(level, shown) { shown.takeIf { it.isNotBlank() }?.let { decodeGrid(it).tiles }?.takeIf { it.isNotEmpty() } }
    val density = LocalDensity.current
    val px = with(density) { 100.dp.roundToPx() }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.background(Raise, shape).then(if (best != null) Modifier.border(2.dp, Out, shape) else Modifier)
            .clip(shape).clickable { game.loadLevel(i) }.padding(6.dp)
            .semantics { contentDescription = "Level ${game.label(i)}" + if (best != null) ", solved" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Page), contentAlignment = Alignment.Center) {
            if (tiles != null) {
                val img = remember(level, shown, px) { buildPreview(level, tiles, px, density.density).asImageBitmap() }
                Image(img, null, Modifier.fillMaxSize())
            } else Text("?", style = Body, color = Dim, fontWeight = FontWeight.Bold, fontSize = 32.sp)
        }
        val checked = saved.checked
        val (line1, line2) = when {
            best != null -> parts(best.parts) to "area ${best.small}" + if (best.large != best.small) "–${best.large}" else ""
            checked != null -> "✗ " + parts(checked.parts) to "area ${checked.area}"
            else -> " " to " "
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(line1, style = BodyTnum, color = Dim, fontSize = 11.sp, maxLines = 1)
            Text(line2, style = BodyTnum, color = Dim, fontSize = 11.sp, maxLines = 1)
        }
        Text(game.label(i), style = BodyTnum, color = if (i == game.levelIndex) Src else Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

private fun parts(n: Int) = if (n == 1) "1 part" else "$n parts"

/** A still picture of [tiles] on the table, fitted into a [px]-pixel square. */
private fun buildPreview(level: Level, tiles: Map<Cell, Tile>, px: Int, density: Float): Bitmap {
    val w = tiles.keys.maxOf { it.x } - tiles.keys.minOf { it.x } + 1
    val h = tiles.keys.maxOf { it.y } - tiles.keys.minOf { it.y } + 1
    val state = BoardState(level, LinkedHashMap(tiles), density).apply { z = minOf(px * 0.88f / maxOf(w, h), px / 2.4f) }
    state.fit(px.toFloat(), px.toFloat())
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    BoardRenderer(state, reduceMotion = true).draw(android.graphics.Canvas(bmp), px, px)
    return bmp
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
                "This level holds 1 input and $outs final output${if (outs > 1) "s" else ""}; splitters, convergers and bypasses are unlimited.",
        ),
        style = Body, color = Dim, fontSize = 12.sp, lineHeight = 1.6.em,
    )
    Buttons { SheetButton("Close") { game.closeSheet() } }
}
