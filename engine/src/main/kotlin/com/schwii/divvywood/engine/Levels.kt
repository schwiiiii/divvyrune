package com.schwii.divvywood.engine

/** A target fraction n/d of the input. */
data class Frac(val n: Int, val d: Int) {
    val q get() = Q.of(n.toLong(), d.toLong())
    override fun toString() = "$n/$d"
}

/**
 * One level: the input sends one item every [t] ticks; [out] holds each final output's target (null means no target);
 * [dump] is how many bypass points the tray offers. [name] is a stable id for saves and tests; players see numbers.
 */
data class Level(
    val name: String,
    val t: Int,
    val out: List<Frac?>,
    val dump: Int,
    val no3: Boolean = false,
    val sandbox: Boolean = false,
)

const val LETTERS = "ABC"

/** The level with this [name]; names are stable, so saves and tests refer to levels by name. */
fun levelNamed(name: String): Level = LEVELS.first { it.name == name }

val LEVELS = listOf(
    Level("Halves", 2, listOf(Frac(1, 2), Frac(1, 2)), 0),
    Level("Quarter back", 2, listOf(Frac(1, 4), Frac(3, 4)), 0),
    Level("Three ways", 2, listOf(Frac(1, 3), Frac(1, 3), Frac(1, 3)), 0),
    Level("Sixth", 2, listOf(Frac(1, 6), Frac(5, 6)), 0),
    Level("Eighths", 2, listOf(Frac(3, 8), Frac(5, 8)), 0),
    Level("Ninths", 2, listOf(Frac(1, 9), Frac(2, 9), Frac(2, 3)), 0),
    Level("One two three", 2, listOf(Frac(1, 6), Frac(1, 3), Frac(1, 2)), 0),
    Level("Twelfths", 2, listOf(Frac(5, 12), Frac(7, 12)), 0),
    Level("Thirds by halves", 2, listOf(Frac(1, 3), Frac(2, 3)), 0, no3 = true),
    Level("Fifth", 2, listOf(Frac(1, 5), Frac(4, 5)), 0),
    Level("Seventh", 2, listOf(Frac(1, 7)), 1),
    Level("One two four", 2, listOf(Frac(1, 7), Frac(2, 7), Frac(4, 7)), 0),
    Level("Tenth", 2, listOf(Frac(3, 10), Frac(7, 10)), 0),
    Level("Full belt", 1, listOf(Frac(2, 9), Frac(7, 9)), 0),
    Level("Fifths by halves", 2, listOf(Frac(2, 5), Frac(3, 5)), 0, no3 = true),
    Level("Seventeenth", 2, listOf(Frac(1, 17)), 2),
    Level("Thirtieth", 2, listOf(Frac(1, 30)), 3),
    Level("Sandbox", 2, listOf(null, null, null), 4, sandbox = true),
)

/** The name of the generated level; its target changes, so it is not in [LEVELS]. */
const val RANDOM = "Random"

/**
 * Every target the random level can ask for: p/q with 3 ≤ q ≤ [RANDOM_MAX_D], in lowest terms.
 * Each is solvable: split into N = 2^a·3^b ≥ q equal shares, send N − q back to a converger at the input,
 * p of the rest to A and the others to a bypass. The input splitter then carries N/2q ≤ 2/3 of a belt.
 */
val RANDOM_TARGETS: List<Frac> = (3..RANDOM_MAX_D).flatMap { q -> (1 until q).filter { gcd(it, q) == 1 }.map { Frac(it, q) } }

const val RANDOM_MAX_D = 24

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/** A target picked evenly by denominator, then numerator; never [not]. */
fun randomTarget(rnd: kotlin.random.Random = kotlin.random.Random.Default, not: Frac? = null): Frac {
    while (true) {
        val q = rnd.nextInt(3, RANDOM_MAX_D + 1)
        val ps = (1 until q).filter { gcd(it, q) == 1 }
        val f = Frac(ps[rnd.nextInt(ps.size)], q)
        if (f != not) return f
    }
}

/** The random level for [target]: one final output and three bypasses, at the usual input rate. */
fun randomLevel(target: Frac) = Level(RANDOM, 2, listOf(target), 3)
