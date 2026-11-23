package com.schwii.divvywood.board

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.schwii.divvywood.engine.Cell
import com.schwii.divvywood.engine.Kind
import com.schwii.divvywood.engine.LETTERS
import com.schwii.divvywood.engine.Tile
import com.schwii.divvywood.engine.commitPath
import com.schwii.divvywood.engine.inferDir
import com.schwii.divvywood.engine.moveBlockedAt
import com.schwii.divvywood.engine.moveGroup
import com.schwii.divvywood.engine.pathValid
import com.schwii.divvywood.engine.placePiece
import com.schwii.divvywood.engine.rotateGroup
import com.schwii.divvywood.engine.turnPiece
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

enum class Haptic { PICK, DROP, CONFIRM }

/** Something in the tray: a splitter, converger or bypass (unlimited), or the level's input and outputs (one each). */
data class TrayItem(val kind: Kind, val id: Int = -1) {
    val limited get() = kind == Kind.SRC || kind == Kind.OUT
}

/** Selected pieces being dragged, shifted by (dx, dy) cells from where they sit. */
data class MoveState(val cells: List<Cell>, val dx: Int = 0, val dy: Int = 0) {
    val shifted get() = dx != 0 || dy != 0
}

/** A tray chip being dragged over the board. */
data class ChipGhost(val item: TrayItem, val cell: Cell)

/**
 * The building controls. Coordinates are board-local pixels; one finger only (the board handles two-finger pan and
 * zoom itself).
 * - tap a piece: select it, which shows its numbers with Rotate and Delete (in Select mode: add it or take it out)
 * - tap the table: deselect everything
 * - drag a selected piece: move the selection; pieces landing on belts replace them, other pieces block the move
 * - drag from an unselected piece that sends marbles (input, splitter, converger, belt): draw a belt, placed on
 *   release if valid
 * - drag anything else: pan the board (in Select mode: a selection box)
 */
class Editor(val board: BoardState, private val haptic: (Haptic) -> Unit = {}) {
    var selection by mutableStateOf(setOf<Cell>()); private set
    var selectMode by mutableStateOf(false); private set
    var move by mutableStateOf<MoveState?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var chipGhost by mutableStateOf<ChipGhost?>(null); private set
    var canUndo by mutableStateOf(false); private set

    /** The gesture in progress. */
    sealed interface Gesture
    class Tap(val x: Float, val y: Float, val cell: Cell) : Gesture
    class Draw(val path: MutableList<Cell>) : Gesture
    class Box(val a: Cell, var b: Cell) : Gesture
    class Moving(val c0: Cell) : Gesture
    class Pan(var x: Float, var y: Float) : Gesture
    var gesture: Gesture? = null; private set

    private val undoStack = ArrayDeque<Map<Cell, Tile>>()
    private val tiles get() = board.tiles

    /** Called after every change to the build (edits and undo), e.g. to save it. */
    var onChange: () -> Unit = {}
    /** While true (a Check is running) the build can't be changed and gestures only pan. */
    var locked: () -> Boolean = { false }

    /** Movement beyond this many pixels turns a tap into a drag. */
    var slop = 8f

    // ---------- edits ----------

    private fun snapshot(): Map<Cell, Tile> = LinkedHashMap(tiles)

    private fun pushUndo(snap: Map<Cell, Tile>) {
        undoStack.addLast(snap)
        if (undoStack.size > 100) undoStack.removeFirst()
        canUndo = true
    }

    /** Applies [change] to the tiles; if it returns true the change is kept with one undo step, otherwise rolled back. */
    fun edit(change: () -> Boolean): Boolean {
        val snap = snapshot()
        val ok = change()
        if (!ok) { tiles.clear(); tiles.putAll(snap); return false }
        pushUndo(snap)
        afterEdit()
        return true
    }

    private fun afterEdit() {
        board.rebuild()
        selection = selection.filter { it in tiles }.toSet()
        onChange()
    }

    /** Puts the whole build in place of the current one, as one undo step. */
    fun replaceAll(newTiles: Map<Cell, Tile>) {
        move = null
        selection = emptySet()
        edit { tiles.clear(); tiles.putAll(newTiles); true }
    }

    /** Forgets undo history, selection and any gesture: for a freshly loaded level. */
    fun reset() {
        undoStack.clear(); canUndo = false
        selection = emptySet(); selectMode = false
        move = null; chipGhost = null; gesture = null
    }

    fun undo() {
        if (locked()) return
        val snap = undoStack.removeLastOrNull() ?: return
        tiles.clear(); tiles.putAll(snap)
        move = null
        canUndo = undoStack.isNotEmpty()
        afterEdit()
    }

    // ---------- pointer ----------

    /** Pieces a belt can be drawn out of: everything that sends marbles. */
    private fun sendsMarbles(c: Cell) = when (tiles[c]?.kind) { Kind.SRC, Kind.SPLIT, Kind.MERGE, Kind.BELT -> true; else -> false }

    fun down(x: Float, y: Float) {
        gesture = Tap(x, y, board.cellAt(x, y))
    }

    fun drag(x: Float, y: Float) {
        val c = board.cellAt(x, y)
        when (val g = gesture) {
            is Tap -> if (hypot(x - g.x, y - g.y) > slop) {
                gesture = when {
                    locked() -> Pan(g.x, g.y)
                    g.cell in selection -> { move = MoveState(selection.toList()); haptic(Haptic.PICK); Moving(g.cell) }
                    selectMode -> Box(g.cell, c)
                    sendsMarbles(g.cell) -> Draw(mutableListOf(g.cell))
                    else -> Pan(g.x, g.y)
                }
                drag(x, y)
            }
            is Moving -> move = move?.copy(dx = c.x - g.c0.x, dy = c.y - g.c0.y)
            is Box -> g.b = c
            is Draw -> extendPath(g.path, c)
            is Pan -> { board.pan(x - g.x, y - g.y); g.x = x; g.y = y }
            null -> Unit
        }
    }

    fun up(x: Float, y: Float) {
        val g = gesture
        gesture = null
        when (g) {
            is Moving -> drop()
            is Box -> {
                val x0 = min(g.a.x, g.b.x); val x1 = max(g.a.x, g.b.x); val y0 = min(g.a.y, g.b.y); val y1 = max(g.a.y, g.b.y)
                selection = selection + tiles.keys.filter { it.x in x0..x1 && it.y in y0..y1 }
            }
            is Draw -> if (g.path.size > 1 && pathValid(tiles, g.path)) edit { commitPath(tiles, g.path); true }
            is Tap -> selection = when {
                g.cell !in tiles -> emptySet()
                selectMode -> if (g.cell in selection) selection - g.cell else selection + g.cell
                else -> setOf(g.cell)
            }
            else -> Unit
        }
    }

    /** A second finger or a cancelled touch: the gesture ends without changing anything. */
    fun cancel() {
        gesture = null
        move = null
    }

    /** Drops the dragged selection where it is; a move onto another piece is refused and the pieces go back. */
    private fun drop() {
        val m = move ?: return
        move = null
        if (!m.shifted) return
        var moved: List<Cell>? = null
        if (!edit { moved = moveGroup(tiles, m.cells, m.dx, m.dy); moved != null }) { notice = "No room here"; return }
        selection = moved!!.toSet()
        haptic(Haptic.DROP)
    }

    /** True if a dragged piece would land on another piece (belts don't count: they get replaced). */
    fun blockedAt(c: Cell): Boolean {
        val m = move ?: return false
        return moveBlockedAt(tiles, m.cells, c)
    }

    /** Extends a belt path cell by cell toward [c]; stepping back onto the previous cell retracts it. */
    private fun extendPath(path: MutableList<Cell>, c: Cell) {
        var last = path.last()
        var guard = 0
        while (last != c && guard++ < 200) {
            val dx = c.x - last.x; val dy = c.y - last.y
            val next = if (abs(dx) >= abs(dy)) Cell(last.x + dx.sign, last.y) else Cell(last.x, last.y + dy.sign)
            val prev = path.getOrNull(path.size - 2)
            when {
                prev == next -> path.removeAt(path.size - 1)
                next in path -> return
                else -> path += next
            }
            last = path.last()
        }
    }

    // ---------- actions on the selection ----------

    /** Rotate: one piece turns a quarter; a group turns 90° clockwise as a unit, or not at all if there's no room. */
    fun rotateSelection() {
        if (locked()) return
        val cells = selection.toList()
        if (cells.isEmpty()) return
        if (cells.size == 1) { edit { tiles[cells[0]] = turnPiece(tiles.getValue(cells[0])); true }; return }
        var moved: List<Cell>? = null
        if (!edit { moved = rotateGroup(tiles, cells); moved != null }) { notice = "No room to rotate here"; return }
        selection = moved!!.toSet()
    }

    fun deleteSelection() {
        if (locked()) return
        val cells = selection
        if (cells.isEmpty()) return
        edit { cells.forEach { tiles.remove(it) }; true }
        selection = emptySet()
    }

    // ---------- select mode ----------

    fun switchSelectMode(on: Boolean) {
        selectMode = on
        if (!on) selection = emptySet()
    }

    fun clearSelection() { selection = emptySet() }

    /** Selects [cells] (those that hold pieces). */
    fun select(cells: Collection<Cell>) { selection = cells.filter { it in tiles }.toSet() }

    fun dismissNotice() { notice = null }

    /** Shows [msg] briefly in the banner. */
    fun say(msg: String) { notice = msg }

    /** Tapping a tray chip does nothing but say how to use it: chips only drag. */
    fun notifyDragOnly() { notice = "Drag it onto the board" }

    // ---------- tray ----------

    /** The unlimited pieces first (they are used most and must never scroll out of reach), then the input and outputs. */
    fun trayItems(): List<TrayItem> = buildList {
        add(TrayItem(Kind.SPLIT)); add(TrayItem(Kind.MERGE)); add(TrayItem(Kind.DUMP, 0))
        board.level.inputs.indices.forEach { add(TrayItem(Kind.SRC, it)) }
        board.level.out.indices.forEach { add(TrayItem(Kind.OUT, it)) }
    }

    fun isPlaced(item: TrayItem) = item.limited && tiles.values.any {
        it.kind == item.kind && (if (it.kind == Kind.SRC) maxOf(it.id, 0) == maxOf(item.id, 0) else it.id == item.id)
    }

    /** What a chip would put on the board at [c], facing the way it will be placed (a belt there lends its direction). */
    fun chipTile(item: TrayItem, c: Cell): Tile {
        val belt = tiles[c]?.takeIf { it.kind == Kind.BELT }
        return when (item.kind) {
            Kind.SPLIT -> Tile(Kind.SPLIT, inSide = belt?.from ?: inferDir(tiles, c, Kind.SPLIT))
            Kind.MERGE -> Tile(Kind.MERGE, out = belt?.out ?: inferDir(tiles, c, Kind.MERGE))
            Kind.SRC -> Tile(Kind.SRC, out = belt?.out ?: inferDir(tiles, c, Kind.SRC), id = maxOf(item.id, 0))
            else -> Tile(item.kind, id = item.id, inSide = belt?.from ?: inferDir(tiles, c, item.kind))
        }
    }

    /** A chip lands on an empty square or replaces a belt. */
    fun chipFits(item: TrayItem, c: Cell): Boolean {
        if (isPlaced(item)) return false
        val ex = tiles[c]
        return ex == null || ex.kind == Kind.BELT
    }

    /** While a chip is dragged: (x, y) in board pixels, or null when the finger is off the board. */
    fun chipHover(item: TrayItem, x: Float?, y: Float?) {
        chipGhost = if (x == null || y == null) null else ChipGhost(item, board.cellAt(x, y))
    }

    /** Drops a chip at (x, y) in board pixels; returns whether it was placed. */
    fun chipDrop(item: TrayItem, x: Float, y: Float): Boolean {
        chipGhost = null
        val c = board.cellAt(x, y)
        if (locked() || !chipFits(item, c)) return false
        val tile = chipTile(item, c)
        val ok = edit {
            if (item.kind == Kind.SPLIT || item.kind == Kind.MERGE) placePiece(tiles, c, item.kind) else { tiles[c] = tile; true }
        }
        if (ok) haptic(Haptic.DROP)
        return ok
    }

    fun chipLabel(item: TrayItem): String = when (item.kind) {
        Kind.SPLIT -> "splitter"
        Kind.MERGE -> "converger"
        Kind.SRC -> if (board.level.inputs.size == 1) "input" else "input ${item.id + 1}"
        Kind.OUT -> "output ${LETTERS[item.id]}"
        else -> "bypass"
    }
}
