package com.schwii.divvywood.engine

/** A target fraction n/d of the input. */
data class Frac(val n: Int, val d: Int) {
    val q get() = Q.of(n.toLong(), d.toLong())
    override fun toString() = "$n/$d"
}

/**
 * One level: the input sends one item every [t] ticks; [out] holds each final output's target (null means no target);
 * [dump] is how many bypass points the tray offers.
 */
data class Level(
    val name: String,
    val t: Int,
    val out: List<Frac?>,
    val dump: Int,
    val hint: String,
    val no3: Boolean = false,
    val sandbox: Boolean = false,
)

const val LETTERS = "ABC"

val LEVELS = listOf(
    Level("Halves", 2, listOf(Frac(1, 2), Frac(1, 2)), 0,
        "Drag the input, both outputs and a splitter from the tray onto the grid. Drag a finger to draw a belt. Tap a piece for its numbers; hold it to move, rotate or delete."),
    Level("Quarter back", 2, listOf(Frac(1, 4), Frac(3, 4)), 0,
        "A converger joins up to three inputs into one, out of its arrow side. Draw from one piece into the next to point them at each other. Select gathers several pieces to move or rotate together."),
    Level("Three ways", 2, listOf(Frac(1, 3), Frac(1, 3), Frac(1, 3)), 0,
        "A splitter with three belts leaving it splits three ways."),
    Level("Sixth", 2, listOf(Frac(1, 6), Frac(5, 6)), 0, "Halves and thirds combine."),
    Level("Eighths", 2, listOf(Frac(3, 8), Frac(5, 8)), 0, "Three halvings make eighths."),
    Level("One two three", 2, listOf(Frac(1, 6), Frac(1, 3), Frac(1, 2)), 0, "Three outputs in the ratio 1 : 2 : 3."),
    Level("Thirds by halves", 2, listOf(Frac(1, 3), Frac(2, 3)), 0,
        "3-way splits are off. Send part of a split back into a converger upstream.", no3 = true),
    Level("Fifth", 2, listOf(Frac(1, 5), Frac(4, 5)), 0, "Loops reach denominators that halves and thirds cannot."),
    Level("Seventh", 2, listOf(Frac(1, 7)), 1, "Send everything output A does not need to the dump."),
    Level("Full belt", 1, listOf(Frac(2, 9), Frac(7, 9)), 0,
        "The input runs at full belt speed. A belt carries at most one item per tick, so a loop has no room here."),
    Level("Seventeenth", 2, listOf(Frac(1, 17)), 2, "One seventeenth to A. The rest is waste."),
    Level("Sandbox", 2, listOf(null, null, null), 4,
        "No targets. Build anything; Check reports the exact split.", sandbox = true),
)
