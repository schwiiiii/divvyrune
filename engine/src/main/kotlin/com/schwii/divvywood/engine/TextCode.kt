package com.schwii.divvywood.engine

/*
 * Text grid: one code per cell, rows top to bottom.
 * I/C + the side it outputs to (I may end in 2 or 3 for inputs 2, 3) · S + the side its input comes from
 * a belt is two sides: fed from, feeding · ".." is an empty cell. Sides: T R B L.
 * One-line form: commas between cells, "/" between rows.
 * Codes from before outputs were the build's sides may hold O (bypass) and F (final output) cells: they read as empty,
 * so the belts that fed them lead out of the build.
 */

private const val SIDE = "RBLT"
private val SIDE_OF = mapOf('R' to 0, 'B' to 1, 'L' to 2, 'T' to 3)

private fun gridRows(tiles: Map<Cell, Tile>): List<List<String>> {
    val x0 = tiles.keys.minOf { it.x }; val x1 = tiles.keys.maxOf { it.x }
    val y0 = tiles.keys.minOf { it.y }; val y1 = tiles.keys.maxOf { it.y }
    return (y0..y1).map { y ->
        (x0..x1).map { x ->
            val c = Cell(x, y)
            val t = tiles[c]
            when (t?.kind) {
                null -> ".."
                Kind.BELT -> "${SIDE[t.from]}${SIDE[t.out]}"
                Kind.SRC -> "I${SIDE[t.out]}${if (t.id > 0) t.id + 1 else ""}"
                Kind.SPLIT -> "S${SIDE[t.inSide]}"
                Kind.MERGE -> "C${SIDE[t.out]}"
                Kind.OUT -> ".."
            }
        }
    }
}

/** One row per line, cells separated by single spaces. */
fun encodeGrid(tiles: Map<Cell, Tile>) = if (tiles.isEmpty()) "" else gridRows(tiles).joinToString("\n") { it.joinToString(" ") }

/** The whole build on one line: commas between cells, "/" between rows. */
fun encodeLine(tiles: Map<Cell, Tile>) = if (tiles.isEmpty()) "" else gridRows(tiles).joinToString("/") { it.joinToString(",") }

class Decoded(val tiles: Tiles?, val error: String?)

private val BELT_CODE = Regex("^([TRBL])([TRBL])$")
private val PIECE_CODE = Regex("^([ISCOF])([TRBL])([1-3]?)$")

fun decodeGrid(text: String): Decoded {
    val tiles: Tiles = linkedMapOf()
    val pendingIn = mutableListOf<Pair<Cell, Int>>()
    val rows = text.split(Regex("\r?\n|/")).map { it.trim() }.filter { it.isNotEmpty() }
    if (rows.isEmpty()) return Decoded(null, "The text is empty.")
    for ((y, row) in rows.withIndex()) {
        val cells = row.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        for ((x, raw) in cells.withIndex()) {
            val c = raw.uppercase()
            val at = Cell(x, y)
            val where = "Row ${y + 1}, cell ${x + 1}"
            if (c == "..") continue
            val belt = BELT_CODE.find(c)
            val piece = PIECE_CODE.find(c)
            if (belt != null) {
                val (f, o) = belt.destructured
                if (f == o) return Decoded(null, "$where: a belt can't be fed from and feed the same side (\"$raw\").")
                tiles[at] = Tile(Kind.BELT, from = SIDE_OF.getValue(f[0]), out = SIDE_OF.getValue(o[0]))
            } else if (piece != null && (piece.groupValues[3].isEmpty() || piece.groupValues[1] == "F" || piece.groupValues[1] == "I")) {
                val (k, s, num) = piece.destructured
                val d = SIDE_OF.getValue(s[0])
                when (k) {
                    "I" -> if (num.isNotEmpty()) tiles[at] = Tile(Kind.SRC, out = d, id = num.toInt() - 1) else pendingIn += at to d
                    "S" -> tiles[at] = Tile(Kind.SPLIT, inSide = d)
                    "C" -> tiles[at] = Tile(Kind.MERGE, out = d)
                    else -> Unit   // an old bypass or final output: the build's sides take their place
                }
            } else {
                return Decoded(null, "$where: \"$raw\" isn't a cell code.")
            }
        }
    }
    // inputs number the same way: an unnumbered I takes the first free number
    val ins = tiles.values.filter { it.kind == Kind.SRC }
    val usedIn = ins.map { it.id }.toMutableSet()
    if (usedIn.size != ins.size) return Decoded(null, "Two inputs have the same number.")
    for ((at, d) in pendingIn) {
        var id = 0
        while (id in usedIn) id++
        usedIn += id
        tiles[at] = Tile(Kind.SRC, out = d, id = id)
    }
    return Decoded(tiles, null)
}

/** Can [level] hold these tiles? Returns an error message, or null. */
fun fitsLevel(tiles: Map<Cell, Tile>, level: Level): String? {
    val ins = tiles.values.filter { it.kind == Kind.SRC }
    fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"
    if (ins.any { it.id >= level.inputs.size }) return "This level has ${plural(level.inputs.size, "input")}; the text uses ${ins.maxOf { it.id } + 1}."
    return null
}
