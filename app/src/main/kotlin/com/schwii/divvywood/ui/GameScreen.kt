package com.schwii.divvywood.ui

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schwii.divvywood.board.Board
import com.schwii.divvywood.board.Editor
import com.schwii.divvywood.board.Haptic
import com.schwii.divvywood.board.TrayItem
import com.schwii.divvywood.board.readoutOf
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.LETTERS
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.wood.Painter
import com.schwii.divvywood.wood.Palette
import com.schwii.divvywood.wood.angleOf
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Ink = Color(Palette.ink)
private val Dim = Color(Palette.dim)
private val Page = Color(Palette.page)
private val Panel = Color(Palette.panel)
private val Raise = Color(0xFFFFFDF8)
private val Line = Color(0xFFDDD3C3)
private val Bad = Color(Palette.bad)

/** Plays the editor's haptic cues through the view, so they work on every phone. */
fun hapticOf(view: android.view.View): (Haptic) -> Unit = { h ->
    view.performHapticFeedback(
        when (h) {
            Haptic.PICK -> HapticFeedbackConstants.LONG_PRESS
            Haptic.DROP -> HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.CONFIRM -> if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        },
    )
}

/** The game: the board with its overlays, then the tray and the toolbar in thumb reach. */
@Composable
fun GameScreen(editor: Editor, modifier: Modifier = Modifier, animate: Boolean = true) {
    val board = editor.board
    var boardBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var dragIcon by remember { mutableStateOf<Pair<ImageBitmap, Offset>?>(null) }
    Box(modifier.fillMaxSize().background(Page)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { boardBounds = it.boundsInRoot() }) {
                Board(board, Modifier.fillMaxSize(), editor, animate = animate)
                Overlays(editor)
            }
            Column(
                Modifier.fillMaxWidth().background(Panel).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tray(editor, boardBounds) { dragIcon = it }
                Toolbar(editor)
            }
        }
        // a chip dragged off the board follows the finger; over the board, the board draws where it would land
        dragIcon?.let { (img, at) ->
            Image(img, null, Modifier.size(56.dp).offset { IntOffset((at.x - 28.dp.toPx()).roundToInt(), (at.y - 70.dp.toPx()).roundToInt()) })
        }
    }
}

@Composable
private fun Overlays(editor: Editor) {
    val board = editor.board
    board.frame   // follow the board's camera and live numbers
    val notice = editor.notice
    LaunchedEffect(notice) { if (notice != null) { delay(1800); editor.dismissNotice() } }
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        val banner = when {
            editor.move != null -> "Drag to move"
            notice != null -> notice
            else -> null
        }
        if (banner != null) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 10.dp).background(Raise, RoundedCornerShape(50)).border(1.dp, Line, RoundedCornerShape(50))
                    .padding(start = 16.dp, end = if (editor.move != null) 4.dp else 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(banner, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
                if (editor.move != null) Pill("Cancel") { editor.cancelMove() }
            }
        }
        if (editor.selectMode) {
            val n = editor.selection.size
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).background(Raise, RoundedCornerShape(50)).border(1.dp, Line, RoundedCornerShape(50))
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(if (n > 0) "$n selected" else "Drag a box or tap pieces", color = Ink, fontSize = 14.sp)
                if (n > 0) Pill("Clear") { editor.clearSelection() }
                Pill("Done") { editor.switchSelectMode(false) }
            }
        }
    }
    editor.menu?.let { m ->
        AnchoredAbove(board.screenX(m.anchor.x), board.screenY(m.anchor.y), board.z) {
            Row(Modifier.background(Raise, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuButton(if (m.cells.size > 1) "Move ${m.cells.size}" else "Move") { editor.menuMove() }
                MenuButton("Rotate") { editor.menuRotate() }
                MenuButton("Delete", Bad) { editor.menuDelete() }
            }
        }
    }
    editor.readout?.let { cell ->
        val info = readoutOf(board, cell) ?: return@let
        AnchoredAbove(board.screenX(cell.x), board.screenY(cell.y), board.z) {
            Column(Modifier.widthIn(max = 260.dp).background(Raise, RoundedCornerShape(10.dp)).border(1.dp, Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(info.title, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                info.errors.forEach { Text(it, color = Bad, fontSize = 13.sp) }
                info.note?.let { Text(it, color = Dim, fontSize = 13.sp) }
                info.rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp)); Text(v, color = Ink, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** Places [content] centred above board point (x, y), or below it when there's no room above. Clamped to the board. */
@Composable
private fun AnchoredAbove(x: Float, y: Float, z: Float, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val gap = (z * 0.7f).roundToInt()
                val px = (x - p.width / 2f).roundToInt().coerceIn(8, (constraints.maxWidth - p.width - 8).coerceAtLeast(8))
                val above = y.roundToInt() - gap - p.height
                val py = if (above >= 8) above else (y.roundToInt() + gap).coerceAtMost(constraints.maxHeight - p.height - 8)
                p.place(px, py)
            }
        }) { content() }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 36.dp).background(Page, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) { Text(label, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

@Composable
private fun MenuButton(label: String, color: Color = Ink, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).widthIn(min = 64.dp).background(Page, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) { Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

// ---------- tray ----------

private val iconPainter by lazy { Painter() }

/** A tray item drawn as the block it places, at [px] pixels square. */
fun chipIcon(item: TrayItem, levelTargets: List<String?>, px: Int): Bitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val z = px * 0.875f; val x = px / 2f; val y = px / 2f - px * 0.02f
    val p = iconPainter
    p.blockShadow(c, x, y, z)
    when (item.kind) {
        Kind.SPLIT -> { p.tile(c, x, y, z, Tile(Kind.SPLIT, inSide = 2), listOf(2), listOf(3, 1), 905); p.flipper(c, x, y, z, angleOf(3)); p.burnText(c, "÷", x + z * 0.27f, y, z * 0.36f, light = true) }
        Kind.MERGE -> { p.tile(c, x, y, z, Tile(Kind.MERGE, out = 0), listOf(3, 1), listOf(0), 906); p.flipper(c, x, y, z, angleOf(3)); p.burnText(c, "+", x - z * 0.27f, y, z * 0.36f, light = true) }
        Kind.SRC -> p.tile(c, x, y, z, Tile(Kind.SRC, out = 0), emptyList(), listOf(0), 903, label = "IN", labelScale = 1.5f)
        Kind.OUT -> p.tile(c, x, y, z, Tile(Kind.OUT, id = item.id, inSide = 2), emptyList(), emptyList(), 910 + item.id,
            label = LETTERS[item.id] + (levelTargets.getOrNull(item.id)?.let { " $it" } ?: ""), labelScale = 1.9f)
        else -> p.tile(c, x, y, z, Tile(Kind.DUMP, id = item.id, inSide = 2), emptyList(), emptyList(), 920 + item.id)
    }
    return bmp
}

@Composable
private fun Tray(editor: Editor, boardBounds: androidx.compose.ui.geometry.Rect, onDragIcon: (Pair<ImageBitmap, Offset>?) -> Unit) {
    val board = editor.board
    board.version   // placed state follows the build
    val targets = board.level.out.map { it?.toString() }
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }
    val slop = LocalViewConfiguration.current.touchSlop
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        for (item in editor.trayItems()) {
            val icon = remember(item, board.level) { chipIcon(item, targets, px).asImageBitmap() }
            val placed = editor.isPlaced(item)
            var origin by remember { mutableStateOf(Offset.Zero) }
            Image(icon, null,
                Modifier.size(48.dp).alpha(if (placed) 0.25f else 1f)
                    .semantics { contentDescription = (if (placed) "Placed: " else "Drag to place: ") + editor.chipLabel(item) }
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .pointerInput(item, placed, boardBounds) {
                        if (placed) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var moved = false
                            var last = origin + down.position
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.first()
                                last = origin + ch.position
                                if (!moved && (ch.position - down.position).getDistance() > slop) moved = true
                                if (!ch.pressed) break
                                if (moved) {
                                    ch.consume()
                                    val over = boardBounds.contains(last)
                                    editor.chipHover(item, if (over) last.x - boardBounds.left else null, if (over) last.y - boardBounds.top else null)
                                    onDragIcon(if (over) null else icon to last)
                                }
                            }
                            onDragIcon(null)
                            if (!moved) { editor.chipHover(item, null, null); editor.notifyDragOnly(); return@awaitEachGesture }
                            if (boardBounds.contains(last)) editor.chipDrop(item, last.x - boardBounds.left, last.y - boardBounds.top)
                            else editor.chipHover(item, null, null)
                        }
                    })
        }
    }
}

// ---------- toolbar ----------

@Composable
private fun Toolbar(editor: Editor) {
    val board = editor.board
    var playing by remember { mutableStateOf(board.playing) }
    var speed by remember { androidx.compose.runtime.mutableIntStateOf(board.speed) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Tool("Select", Modifier.weight(1f), on = editor.selectMode) { editor.switchSelectMode(!editor.selectMode) }
        Tool("Undo", Modifier.weight(1f), enabled = editor.canUndo) { editor.undo() }
        Tool(if (playing) "Pause" else "Play", Modifier.weight(1f)) { board.playing = !board.playing; playing = board.playing }
        Tool("×$speed", Modifier.weight(1f)) {
            board.speed = when (board.speed) { 1 -> 4; 4 -> 16; else -> 1 }; speed = board.speed
        }
    }
}

@Composable
private fun Tool(label: String, modifier: Modifier, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).background(if (on) Ink else Page, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Raise else Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
fun rememberHaptics(): (Haptic) -> Unit { val view = LocalView.current; return remember(view) { hapticOf(view) } }
