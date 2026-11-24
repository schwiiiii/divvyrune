package com.schwii.divvywood.engine

/*
 * Text grid: one code per cell, rows top to bottom.
 * I/C + the side it outputs to (I may end in 2 or 3 for inputs 2, 3) · S + the side its input comes from
 * S and C end in a digit 0–7 for their other sides, read as binary going clockwise from the marked side: the last bit
 * is the next side clockwise, then the opposite side, then the one before. 1 is open: ST3 takes in at the top and
 * sends out right and down, its left side closed; ST7 has every side open, and so has a plain ST.
 * a belt is two sides: fed from, feeding · X is a bridge, its lanes' directions taken from what joins it · ".." is an
 * empty cell. Sides: T R B L.
 * Inputs sit just outside the machine, in the row or column they feed; a code that puts one elsewhere moves it there.
 * One-line form: commas between cells, "/" between rows.
 * Codes from before outputs were the build's sides may hold O (bypass) and F (final output) cells: they read as empty,
 * so the belts that fed them lead out of the build.
 */

private const val SIDE = "RBLT"
private val SIDE_OF = mapOf('R' to 0, 'B' to 1, 'L' to 2, 'T' to 3)

/** The open sides other than [mark], clockwise from it, as a digit 0–7. */
private fun openDigit(t: Tile, mark: Int) = (0..2).sumOf { k -> if (t.isOpen((mark + 1 + k) % 4)) 1 shl k else 0 }

/** The closed-side mask for digit [v] read clockwise from [mark]. */
private fun closedMask(v: Int, mark: Int) = (0..2).sumOf { k -> if (v and (1 shl k) == 0) 1 shl ((mark + 1 + k) % 4) else 0 }

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
                Kind.SPLIT -> "S${SIDE[t.inSide]}${openDigit(t, t.inSide)}"
                Kind.MERGE -> "C${SIDE[t.out]}${openDigit(t, t.out)}"
                Kind.OUT -> ".."
                Kind.BRIDGE -> "X"
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
private val PIECE_CODE = Regex("^([ISCOF])([TRBL])([0-7]?)$")

fun decodeGrid(text: String): Decoded {
    val tiles: Tiles = linkedMapOf()
    val pendingIn = mutableListOf<Pair<Cell, Int>>()
    val bridges = mutableListOf<Cell>()
    val rows = text.split(Regex("\r?\n|/")).map { it.trim() }.filter { it.isNotEmpty() }
    if (rows.isEmpty()) return Decoded(null, "The text is empty.")
    for ((y, row) in rows.withIndex()) {
        val cells = row.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        for ((x, raw) in cells.withIndex()) {
            val c = raw.uppercase()
            val at = Cell(x, y)
            val where = "Row ${y + 1}, cell ${x + 1}"
            if (c == "..") continue
            if (c == "X") { bridges += at; continue }
            val belt = BELT_CODE.find(c)
            val piece = PIECE_CODE.find(c)
            if (belt != null) {
                val (f, o) = belt.destructured
                if (f == o) return Decoded(null, "$where: a belt can't be fed from and feed the same side (\"$raw\").")
                tiles[at] = Tile(Kind.BELT, from = SIDE_OF.getValue(f[0]), out = SIDE_OF.getValue(o[0]))
            } else if (piece != null && piece.groupValues[3].let { it.isEmpty() || it[0] in '1'..'3' || piece.groupValues[1] == "S" || piece.groupValues[1] == "C" }) {
                val (k, s, num) = piece.destructured
                val d = SIDE_OF.getValue(s[0])
                when (k) {
                    "I" -> if (num.isNotEmpty()) tiles[at] = Tile(Kind.SRC, out = d, id = num.toInt() - 1) else pendingIn += at to d
                    "S" -> tiles[at] = Tile(Kind.SPLIT, inSide = d, closed = if (num.isEmpty()) 0 else closedMask(num.toInt(), d))
                    "C" -> tiles[at] = Tile(Kind.MERGE, out = d, closed = if (num.isEmpty()) 0 else closedMask(num.toInt(), d))
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
    inferBridges(tiles, bridges)
    settleInputs(tiles)
    return Decoded(tiles, null)
}

/** Can [level] hold these tiles? Returns an error message, or null. */
fun fitsLevel(tiles: Map<Cell, Tile>, level: Level): String? {
    val ins = tiles.values.filter { it.kind == Kind.SRC }
    fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"
    if (ins.any { it.id >= level.inputs.size }) return "This level has ${plural(level.inputs.size, "input")}; the text uses ${ins.maxOf { it.id } + 1}."
    return null
}

/**
 * Gives each bridge at [cells] its lanes' directions from what joins it. On each axis a lane runs through any bridges
 * in a row to the first other cell at each end: it runs away from an end that sends into it, else toward an end that
 * takes from it; right and down when neither says.
 */
private fun inferBridges(tiles: Tiles, cells: List<Cell>) {
    val set = cells.toSet()
    fun lane(c: Cell, fwd: Int): Int {
        val back = opp(fwd)
        var f = c.step(fwd); while (f in set) f = f.step(fwd)
        var b = c.step(back); while (b in set) b = b.step(back)
        return when {
            sends(tiles[b], fwd) -> fwd
            sends(tiles[f], back) -> back
            accepts(tiles[f], back) -> fwd
            accepts(tiles[b], fwd) -> back
            else -> fwd
        }
    }
    val dirs = cells.associateWith { c -> lane(c, 0) to lane(c, 1) }
    for ((c, d) in dirs) tiles[c] = Tile(Kind.BRIDGE, out = d.first, inSide = d.second)
}
