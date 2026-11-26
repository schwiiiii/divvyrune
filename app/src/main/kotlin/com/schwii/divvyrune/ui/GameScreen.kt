package com.schwii.divvyrune.ui

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
import com.schwii.divvyrune.board.Board
import com.schwii.divvyrune.board.Editor
import com.schwii.divvyrune.board.Haptic
import com.schwii.divvyrune.board.TrayItem
import com.schwii.divvyrune.board.readoutOf
import com.schwii.divvyrune.engine.CHECK_TICKS
import com.schwii.divvyrune.game.Game
import com.schwii.divvyrune.wood.Fonts
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import com.schwii.divvyrune.engine.Kind
import com.schwii.divvyrune.engine.LETTERS
import com.schwii.divvyrune.engine.Tile
import com.schwii.divvyrune.wood.Painter
import com.schwii.divvyrune.wood.Palette
import com.schwii.divvyrune.wood.angleOf
import kotlinx.coroutines.delay
import kotlin.math.roundToInt


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

/** The game: readouts on top, the board with its overlays, then the tray and the toolbar in thumb reach. */
@Composable
fun GameScreen(game: Game, modifier: Modifier = Modifier, animate: Boolean = true) {
    Fonts.load(LocalContext.current)   // before any block is drawn
    val editor = game.editor
    val board = editor.board
    var boardBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var dragIcon by remember { mutableStateOf<Pair<ImageBitmap, Offset>?>(null) }
    CompositionLocalProvider(LocalTextStyle provides Body) {
    Box(modifier.fillMaxSize().background(Page)) {
        Column(Modifier.fillMaxSize()) {
            TopStrip(game)
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onGloballyPositioned { boardBounds = it.boundsInRoot() }) {
                Board(board, Modifier.fillMaxSize(), editor, animate = animate)
                Overlays(editor)
            }
            Column(
                Modifier.fillMaxWidth().background(Panel).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tray(editor, boardBounds, onPick = { game.showMachines() }) { dragIcon = it }
                Toolbar(game)
            }
        }
        // a chip dragged off the board follows the finger; over the board, the board draws where it would land
        dragIcon?.let { (img, at) ->
            Image(img, null, Modifier.size(56.dp).offset { IntOffset((at.x - 28.dp.toPx()).roundToInt(), (at.y - 70.dp.toPx()).roundToInt()) })
        }
        SheetHost(game)
    }
    }
}

@Composable
private fun Overlays(editor: Editor) {
    val board = editor.board
    board.frame   // follow the board's camera and live numbers
    val notice = editor.notice
    LaunchedEffect(notice) { if (notice != null) { delay(1800); editor.dismissNotice() } }
    Box(Modifier.fillMaxSize()) {
        val banner = when {
            board.checking -> "Checking · tick ${board.sim.tick} / $CHECK_TICKS"
            notice == null && !board.playing -> "Paused · ${board.stepLabel()}"
            notice != null -> notice
            else -> null
        }
        if (banner != null) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 10.dp).background(Raise, RoundedCornerShape(50)).border(1.dp, Line, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(banner, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, style = BodyTnum, modifier = Modifier.padding(vertical = 8.dp))
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
    SelectionPanel(editor)
}

/**
 * What's selected: one piece's numbers, or how many pieces, with Rotate and Delete beside them. Floats above the
 * selection, and hides while the selection is being dragged.
 */
@Composable
private fun SelectionPanel(editor: Editor) {
    val board = editor.board
    val sel = editor.selection
    if (sel.isEmpty() || editor.move != null) return
    val single = sel.singleOrNull()
    val info = single?.let { readoutOf(board, it) }
    val ax = (sel.minOf { it.x } + sel.maxOf { it.x }) / 2f
    val top = sel.minOf { it.y }
    val enabled = !board.checking
    AnchoredAbove(board.ox + (ax + 0.5f) * board.z, board.screenY(top), board.z) {
        Column(Modifier.widthIn(max = 280.dp).background(Raise, RoundedCornerShape(10.dp)).border(1.dp, Line, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info?.title ?: "${sel.size} pieces", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp))
                ActionIcon("Rotate", enabled, { editor.rotateSelection() }) { rotateIcon(it) }
                ActionIcon("Delete", enabled, { editor.deleteSelection() }) { deleteIcon(it) }
            }
            Column(Modifier.padding(end = 8.dp)) {
                info?.errors?.forEach { Text(it, color = Bad, fontSize = 13.sp) }
                info?.note?.let { Text(it, color = Dim, fontSize = 13.sp) }
                info?.rows?.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp)); Text(v, color = Ink, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** A 44 dp icon button on the selection panel. */
@Composable
private fun ActionIcon(label: String, enabled: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }.alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (label == "Delete") Bad else Ink
        androidx.compose.foundation.Canvas(Modifier.size(22.dp)) { scale(size.width / 24f, pivot = Offset.Zero) { icon(tint) } }
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

// ---------- tray ----------

private val iconPainter by lazy { Painter() }
private val SAMPLE_MACHINE by lazy { com.schwii.divvyrune.engine.decodeGrid("IR LR SL5").tiles!! }

/** A tray item drawn as the block it places, at [px] pixels square, in the current look. */
fun chipIcon(item: TrayItem, level: com.schwii.divvyrune.engine.Level, px: Int, lib: com.schwii.divvyrune.engine.MachineLib = com.schwii.divvyrune.engine.MachineLib.EMPTY): Bitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val z = px * 0.875f; val x = px / 2f; val y = px / 2f - px * 0.02f
    val p = com.schwii.divvyrune.board.lookPainter()
    p.unit = px / 48f
    p.blockShadow(c, x, y, z)
    when (item.kind) {
        Kind.SPLIT -> { val t = Tile(Kind.SPLIT, inSide = 2); p.tile(c, x, y, z, t, listOf(2), listOf(3, 1), 905); p.turn(c, x, y, z, t, 3, angleOf(3), listOf(3, 1)); p.burnText(c, "÷", x + z * 0.27f, y, z * 0.36f, light = true) }
        Kind.MERGE -> { val t = Tile(Kind.MERGE, out = 0); p.tile(c, x, y, z, t, listOf(3, 1), listOf(0), 906); p.burnText(c, "+", x - z * 0.27f, y, z * 0.36f, light = true) }
        Kind.SRC -> p.tile(c, x, y, z, Tile(Kind.SRC, out = 0), emptyList(), listOf(0), 903 + maxOf(item.id, 0),
            label = level.inputLabel(maxOf(item.id, 0)) + (level.inputs.getOrNull(maxOf(item.id, 0))?.let { " $it" } ?: ""), labelScale = 1.5f)
        Kind.MACHINE -> if (item.ref == null) {
            // the machines button: a machine block with a plain split inside, and a plus
            p.machineBlock(c, x - z / 2, y - z / 2, z, 908, false)
            com.schwii.divvyrune.board.drawBuild(c, p, SAMPLE_MACHINE, lib, x - z * 0.4f, y - z * 0.4f, z * 0.8f)
            p.burnText(c, "+", x + z * 0.33f, y + z * 0.3f, z * 0.34f)
        } else com.schwii.divvyrune.board.drawBuild(c, p, mapOf(com.schwii.divvyrune.engine.Cell(0, 0) to Tile(Kind.MACHINE, ref = item.ref)), lib, x - z / 2, y - z / 2, z)
        else -> p.tile(c, x, y, z, Tile(Kind.BRIDGE, out = 0, inSide = 1), emptyList(), emptyList(), 907)
    }
    return bmp
}

@Composable
private fun Tray(editor: Editor, boardBounds: androidx.compose.ui.geometry.Rect, onPick: () -> Unit, onDragIcon: (Pair<ImageBitmap, Offset>?) -> Unit) {
    val board = editor.board
    board.version   // placed state follows the build
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }
    val slop = LocalViewConfiguration.current.touchSlop
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        for (item in editor.trayItems()) {
            val icon = remember(item, board.level, com.schwii.divvyrune.wood.Look.wire) { chipIcon(item, board.level, px, board.lib).asImageBitmap() }
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
                            if (editor.locked()) return@awaitEachGesture
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
                            if (!moved) { editor.chipHover(item, null, null); if (item.picker) onPick() else editor.notifyDragOnly(); return@awaitEachGesture }
                            if (item.picker) { editor.chipHover(item, null, null); return@awaitEachGesture }
                            if (boardBounds.contains(last)) editor.chipDrop(item, last.x - boardBounds.left, last.y - boardBounds.top)
                            else editor.chipHover(item, null, null)
                        }
                    })
        }
    }
}

// ---------- toolbar ----------

@Composable
private fun Toolbar(game: Game) {
    val editor = game.editor
    val board = game.board
    val playing = board.playing
    var speed by remember { androidx.compose.runtime.mutableIntStateOf(board.speed) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Tool("Select", Modifier.weight(1f), on = editor.selectMode, icon = { selectIcon(it) }) { editor.switchSelectMode(!editor.selectMode) }
        Tool("Undo", Modifier.weight(1f), enabled = editor.canUndo && !board.checking, icon = { undoIcon(it) }) { editor.undo() }
        Tool(if (playing) "Pause" else "Play", Modifier.weight(1f), icon = { if (playing) pauseIcon(it) else playIcon(it) }) {
            board.playing = !board.playing
        }
        // paused, the speed button steps one tick at a time instead
        if (playing || board.checking) Tool("Speed", Modifier.weight(1f), text = "×$speed") {
            board.speed = when (board.speed) { 1 -> 4; 4 -> 16; else -> 1 }; speed = board.speed
        } else Tool("Step", Modifier.weight(1f), icon = { stepIcon(it) }) { board.stepOnce() }
        Tool("Check", Modifier.weight(1f), check = true, enabled = !board.checking, icon = { checkIcon(it) }) { game.startCheck() }
    }
}

/** A toolbar button: an icon (or bold [text]) over a small label. Select lights up dark when on; Check is green. */
@Composable
private fun Tool(
    label: String, modifier: Modifier, on: Boolean = false, check: Boolean = false, enabled: Boolean = true,
    text: String? = null, icon: (androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit)? = null, onClick: () -> Unit,
) {
    val bg = when { check -> Out; on -> Ink; else -> Page }
    val fg = if (check || on) On else Dim
    Column(
        modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else if (check) 0.5f else 0.4f).background(bg, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        if (text != null) Text(text, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 22.sp)
        else androidx.compose.foundation.Canvas(Modifier.size(22.dp)) { scale(size.width / 24f, pivot = Offset.Zero) { icon?.invoke(this, fg) } }
        Text(label, color = fg, fontSize = 10.sp, fontWeight = if (check) FontWeight.ExtraBold else FontWeight.Medium, lineHeight = 12.sp)
    }
}

@Composable
fun rememberHaptics(): (Haptic) -> Unit { val view = LocalView.current; return remember(view) { hapticOf(view) } }
