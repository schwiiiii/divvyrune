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

/** Something in the tray: a splitter or converger (unlimited), or the level's input, outputs and bypasses (one each). */
data class TrayItem(val kind: Kind, val id: Int = -1) {
    val limited get() = kind == Kind.SRC || kind == Kind.OUT || kind == Kind.DUMP
}

/** Pieces lifted by Move, shifted by (dx, dy) cells from where they sit. */
data class MoveState(val cells: List<Cell>, val dx: Int = 0, val dy: Int = 0) {
    val shifted get() = dx != 0 || dy != 0
}

/** The hold menu: the pieces it acts on, and the cell it opened over. */
data class MenuState(val cells: List<Cell>, val anchor: Cell)

/** A tray chip being dragged over the board. */
data class ChipGhost(val item: TrayItem, val cell: Cell)

/**
 * The building controls, as in the prototype. Coordinates are board-local pixels; one finger only (the board
 * handles two-finger pan and zoom itself).
 * - tap a piece: its readout (in Select mode: add or remove it)
 * - hold a piece: the Move / Rotate / Delete menu, for the whole selection if the piece is selected
 * - drag: draw a belt, placed on release if valid (in Select mode: a selection box)
 * - after Move: the next drag carries the pieces by the finger's offset; release drops them where they fit
 */
class Editor(val board: BoardState, private val haptic: (Haptic) -> Unit = {}) {
    var selection by mutableStateOf(setOf<Cell>()); private set
    var selectMode by mutableStateOf(false); private set
    var move by mutableStateOf<MoveState?>(null); private set
    var menu by mutableStateOf<MenuState?>(null); private set
    var readout by mutableStateOf<Cell?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var chipGhost by mutableStateOf<ChipGhost?>(null); private set
    var canUndo by mutableStateOf(false); private set

    /** The gesture in progress. */
    sealed interface Gesture
    class Tap(val x: Float, val y: Float, val cell: Cell, val t: Long) : Gesture
    class Draw(val path: MutableList<Cell>) : Gesture
    class Box(val a: Cell, var b: Cell) : Gesture
    class Moving(val c0: Cell, val dx0: Int, val dy0: Int) : Gesture
    object Held : Gesture
    var gesture: Gesture? = null; private set

    private val undoStack = ArrayDeque<Map<Cell, Tile>>()
    private val tiles get() = board.tiles

    /** Movement beyond this many pixels turns a tap into a drag; holding this long opens the menu. */
    var slop = 8f
    val holdMs = 450L

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
    }

    fun undo() {
        val snap = undoStack.removeLastOrNull() ?: return
        tiles.clear(); tiles.putAll(snap)
        move = null; menu = null; readout = null
        canUndo = undoStack.isNotEmpty()
        afterEdit()
    }

    // ---------- pointer ----------

    fun down(x: Float, y: Float, t: Long) {
        val c = board.cellAt(x, y)
        if (menu != null) { menu = null; gesture = null; return }   // a tap anywhere closes the menu
        readout = null
        val m = move
        gesture = if (m != null) Moving(c, m.dx, m.dy) else Tap(x, y, c, t)
    }

    fun drag(x: Float, y: Float) {
        val c = board.cellAt(x, y)
        when (val g = gesture) {
            is Moving -> move = move?.copy(dx = g.dx0 + c.x - g.c0.x, dy = g.dy0 + c.y - g.c0.y)
            is Tap -> if (hypot(x - g.x, y - g.y) > slop) {
                gesture = if (selectMode) Box(g.cell, c) else Draw(mutableListOf(g.cell)).also { extendPath(it.path, c) }
            }
            is Box -> g.b = c
            is Draw -> extendPath(g.path, c)
            else -> Unit
        }
    }

    fun up(x: Float, y: Float) {
        val g = gesture
        gesture = null
        when (g) {
            is Moving -> tryDrop()
            is Box -> {
                val x0 = min(g.a.x, g.b.x); val x1 = max(g.a.x, g.b.x); val y0 = min(g.a.y, g.b.y); val y1 = max(g.a.y, g.b.y)
                selection = selection + tiles.keys.filter { it.x in x0..x1 && it.y in y0..y1 }
            }
            is Draw -> if (g.path.size > 1 && pathValid(tiles, g.path)) edit { commitPath(tiles, g.path); true }
            is Tap -> if (g.cell in tiles) {
                if (selectMode) selection = if (g.cell in selection) selection - g.cell else selection + g.cell
                else readout = g.cell
            }
            else -> Unit
        }
    }

    fun cancel() {
        val g = gesture
        if (g is Moving) move = move?.copy(dx = g.dx0, dy = g.dy0)
        gesture = null
    }

    /** Called every frame: a still finger on a piece for [holdMs] opens the menu. */
    fun tick(t: Long) {
        val g = gesture as? Tap ?: return
        if (t - g.t < holdMs || g.cell !in tiles) return
        gesture = Held
        haptic(Haptic.PICK)
        openMenu(g.cell)
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

    // ---------- menu ----------

    fun openMenu(at: Cell) {
        menu = MenuState(if (at in selection) selection.toList() else listOf(at), at)
    }

    fun closeMenu() { menu = null }

    /** Rotate: one piece turns a quarter; a group turns 90° clockwise as a unit, or not at all if there's no room. */
    fun menuRotate() {
        val m = menu ?: return
        if (m.cells.size == 1) { edit { tiles[m.cells[0]] = turnPiece(tiles.getValue(m.cells[0])); true }; return }
        var moved: List<Cell>? = null
        val ok = edit { moved = rotateGroup(tiles, m.cells); moved != null }
        if (!ok) { notice = "No room to rotate here"; return }
        selection = moved!!.toSet()
        menu = m.copy(cells = moved!!)
    }

    fun menuDelete() {
        val m = menu ?: return
        menu = null
        edit { m.cells.forEach { tiles.remove(it) }; true }
        selection = selection - m.cells.toSet()
    }

    fun menuMove() {
        val m = menu ?: return
        menu = null
        move = MoveState(m.cells)
    }

    fun cancelMove() { move = null }

    private fun tryDrop() {
        val m = move ?: return
        if (!m.shifted) return
        val wasSelected = m.cells.all { it in selection }   // checked before the edit tidies the selection
        var moved: List<Cell>? = null
        if (!edit { moved = moveGroup(tiles, m.cells, m.dx, m.dy); moved != null }) return   // blocked: stays lifted
        if (wasSelected) selection = moved!!.toSet()
        move = null
        haptic(Haptic.DROP)
    }

    /** True if a lifted piece would land on a tile outside the lifted group. */
    fun blockedAt(c: Cell): Boolean {
        val m = move ?: return false
        return c in tiles && c !in m.cells
    }

    // ---------- select mode ----------

    fun switchSelectMode(on: Boolean) {
        selectMode = on
        if (!on && move == null) selection = emptySet()
        menu = null
    }

    fun clearSelection() { selection = emptySet() }

    fun dismissNotice() { notice = null }

    /** Tapping a tray chip does nothing but say how to use it: chips only drag. */
    fun notifyDragOnly() { notice = "Drag it onto the board" }

    // ---------- tray ----------

    /** Splitters and convergers first: they are used most and must never scroll out of reach. */
    fun trayItems(): List<TrayItem> = buildList {
        add(TrayItem(Kind.SPLIT)); add(TrayItem(Kind.MERGE)); add(TrayItem(Kind.SRC))
        board.level.out.indices.forEach { add(TrayItem(Kind.OUT, it)) }
        repeat(board.level.dump) { add(TrayItem(Kind.DUMP, it)) }
    }

    fun isPlaced(item: TrayItem) = item.limited && tiles.values.any { it.kind == item.kind && (item.kind == Kind.SRC || it.id == item.id) }

    /** What a chip would put on the board at [c], facing the way it will be placed. */
    fun chipTile(item: TrayItem, c: Cell): Tile {
        val ex = tiles[c]
        return when (item.kind) {
            Kind.SPLIT, Kind.MERGE -> {
                val dir = if (ex?.kind == Kind.BELT) { if (item.kind == Kind.SPLIT) ex.from else ex.out } else inferDir(tiles, c, item.kind)
                if (item.kind == Kind.SPLIT) Tile(Kind.SPLIT, inSide = dir) else Tile(Kind.MERGE, out = dir)
            }
            Kind.SRC -> Tile(Kind.SRC, out = inferDir(tiles, c, Kind.SRC))
            else -> Tile(item.kind, id = item.id, inSide = inferDir(tiles, c, item.kind))
        }
    }

    fun chipFits(item: TrayItem, c: Cell): Boolean {
        if (isPlaced(item)) return false
        val ex = tiles[c]
        return if (item.kind == Kind.SPLIT || item.kind == Kind.MERGE) ex == null || ex.kind == Kind.BELT else ex == null
    }

    /** While a chip is dragged: (x, y) in board pixels, or null when the finger is off the board. */
    fun chipHover(item: TrayItem, x: Float?, y: Float?) {
        chipGhost = if (x == null || y == null) null else ChipGhost(item, board.cellAt(x, y))
    }

    /** Drops a chip at (x, y) in board pixels; returns whether it was placed. */
    fun chipDrop(item: TrayItem, x: Float, y: Float): Boolean {
        chipGhost = null
        val c = board.cellAt(x, y)
        if (!chipFits(item, c)) return false
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
        Kind.SRC -> "input"
        Kind.OUT -> "output ${LETTERS[item.id]}"
        else -> "bypass"
    }
}
