package com.schwii.divvywood.engine

/** Moves or rotates several tiles together; refused (null, nothing changed) when one would land on a tile outside the group. */
private fun relocate(tiles: Map<Cell, Tile>, cells: List<Cell>, place: (Cell) -> Cell): List<Pair<Cell, Cell>>? {
    val set = cells.toSet()
    return cells.map { c ->
        val nc = place(c)
        if (nc in tiles && nc !in set) return null
        c to nc
    }
}

private fun applyMoves(tiles: Tiles, moved: List<Pair<Cell, Cell>>, change: (Tile) -> Tile = { it }): List<Cell> {
    val vals = moved.map { tiles.getValue(it.first) }
    moved.forEach { tiles.remove(it.first) }
    moved.forEachIndexed { i, (_, nc) -> tiles[nc] = change(vals[i]) }
    return moved.map { it.second }
}

/** Returns the group's new cells, or null when refused. */
fun moveGroup(tiles: Tiles, cells: List<Cell>, dx: Int, dy: Int): List<Cell>? =
    relocate(tiles, cells) { Cell(it.x + dx, it.y + dy) }?.let { applyMoves(tiles, it) }

/** 90 degrees clockwise about the middle of the group's bounding box. */
fun rotateGroup(tiles: Tiles, cells: List<Cell>): List<Cell>? {
    val x0 = cells.minOf { it.x }; val x1 = cells.maxOf { it.x }
    val y0 = cells.minOf { it.y }; val y1 = cells.maxOf { it.y }
    val w = x1 - x0; val h = y1 - y0
    val sx = (w - h) / 2; val sy = (h - w) / 2   // Kotlin's / truncates toward zero, like Math.trunc
    val moved = relocate(tiles, cells) { Cell(x0 + (y1 - it.y) + sx, y0 + (it.x - x0) + sy) } ?: return null
    return applyMoves(tiles, moved) { t ->
        t.copy(
            from = if (t.from == NONE) NONE else rotDir(t.from),
            out = if (t.out == NONE) NONE else rotDir(t.out),
            inSide = if (t.inSide == NONE) NONE else rotDir(t.inSide),
        )
    }
}
