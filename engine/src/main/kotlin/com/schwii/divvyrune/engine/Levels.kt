package com.schwii.divvyrune.engine

/** A fraction n/d: a rate as a share of one full belt (one marble per tick), or a share of something else. */
data class Frac(val n: Int, val d: Int) {
    val q get() = Q.of(n.toLong(), d.toLong())
    operator fun times(o: Frac) = (q * o.q).toFrac()
    override fun toString() = if (d == 1) "$n" else "$n/$d"
}

fun Q.toFrac() = Frac(n.toInt(), d.toInt())

/**
 * One level. Every rate is a share of one full belt: each input sends [inputs] marbles per tick, and each final
 * output's target [out] is the rate it must receive (null means no target). Splitters, convergers and belts are
 * unlimited. Marbles enter and leave on the sides of the build, one port per side, so a level has at most
 * [MAX_PORTS]: its inputs, its outputs and, when the outputs don't take everything, one more for the rest.
 * [name] is a stable id for saves and tests; players see numbers.
 */
data class Level(
    val name: String,
    val inputs: List<Frac>,
    val out: List<Frac?>,
    val no3: Boolean = false,
) {
    /** A one-input level in the older terms: one marble every [t] ticks, each target a share of that input. */
    constructor(name: String, t: Int, shares: List<Frac?>, no3: Boolean = false) :
        this(name, listOf(Frac(1, t)), shares.map { it?.times(Frac(1, t)) }, no3)

    /** All the inputs together, in marbles per tick. */
    val totalInput: Q get() = inputs.fold(Q.ZERO) { a, f -> a + f.q }

    /** What the outputs leave over, in marbles per tick: it leaves the build on a side of its own. */
    val rest: Q get() = out.filterNotNull().fold(totalInput) { a, f -> a - f.q }

    /** Sides the build needs: one per input, one per output, and one for the rest if there is any. */
    val ports: Int get() = inputs.size + out.size + if (rest > Q.ZERO) 1 else 0

    /**
     * Can a single piece meet this level? A belt passing one input straight out, a splitter dealing one input evenly
     * out of its sides, a converger joining every input into one output, or a bridge passing two straight through.
     * Such levels aren't puzzles: a one-piece machine only restates that piece.
     */
    val onePiece: Boolean get() {
        val leaving = out.filterNotNull().map { it.q } + if (rest > Q.ZERO) listOf(rest) else emptyList()
        if (out.any { it == null }) return false
        val ins = inputs.map { it.q }
        fun same(a: List<Q>, b: List<Q>) = a.sorted() == b.sorted()
        if (ins.size == 1) {
            val r = ins[0]
            return (if (no3) listOf(1, 2) else listOf(1, 2, 3)).any { k -> same(leaving, List(k) { r / Q.of(k.toLong()) }) }
        }
        return (rest.isZero && out.size == 1 && ins.size <= 3) || (ins.size == 2 && rest.isZero && same(leaving, ins))
    }

    /** Every rate leaving: each output's target, then the rest if there is any. */
    val leaving: List<Q> get() = out.filterNotNull().map { it.q } + if (rest > Q.ZERO) listOf(rest) else emptyList()

    /** What goes in and what comes out: "1 → 1/4 · 3/4", "1 + 1/2 → 3/4 · 3/4". */
    val ratios: String get() = inputs.joinToString(" + ") { "${it.q}" } + " → " + leaving.joinToString(" · ")

    /** Input [k]'s name on blocks and readouts: IN alone, IN1, IN2… when there are several. */
    fun inputLabel(k: Int) = if (inputs.size == 1) "IN" else "IN${k + 1}"
}

const val LETTERS = "ABC"

/** A build has four sides, each taking one input or letting one belt out. */
const val MAX_PORTS = 4

/** The level with this [name]; names are stable, so saves and tests refer to levels by name. */
fun levelNamed(name: String): Level = LEVELS.first { it.name == name }

val LEVELS = listOf(
    Level("Quarter back", 1, listOf(Frac(1, 4), Frac(3, 4))),
    Level("Sixth", 1, listOf(Frac(1, 6), Frac(5, 6))),
    Level("Eighths", 1, listOf(Frac(3, 8), Frac(5, 8))),
    Level("Two streams", listOf(Frac(1, 1), Frac(1, 2)), listOf(Frac(3, 4), Frac(3, 4))),
    Level("Ninths", 1, listOf(Frac(1, 9), Frac(2, 9), Frac(2, 3))),
    Level("One two three", 1, listOf(Frac(1, 6), Frac(1, 3), Frac(1, 2))),
    Level("Twelfths", 1, listOf(Frac(5, 12), Frac(7, 12))),
    Level("Swap", listOf(Frac(2, 3), Frac(1, 1)), listOf(Frac(5, 6), Frac(5, 6))),
    Level("Thirds by halves", 1, listOf(Frac(1, 3), Frac(2, 3)), no3 = true),
    Level("Fifth", 1, listOf(Frac(1, 5), Frac(4, 5))),
    Level("Seventh", 1, listOf(Frac(1, 7))),
    Level("One two four", 1, listOf(Frac(1, 7), Frac(2, 7), Frac(4, 7))),
    Level("Tenth", 1, listOf(Frac(3, 10), Frac(7, 10))),
    Level("Full belt", 1, listOf(Frac(2, 9), Frac(7, 9))),
    Level("Fifths by halves", 1, listOf(Frac(2, 5), Frac(3, 5)), no3 = true),
    Level("Seventeenth", 1, listOf(Frac(1, 17))),
    Level("Thirtieth", 1, listOf(Frac(1, 30))),
)

/**
 * Every target "Surprise me" in the level builder can pick: p/q with 3 ≤ q ≤ [RANDOM_MAX_D], in lowest terms.
 * Each is solvable with the input at a full belt: split it in two, then split each half into N = 2^a·3^b ≥ q equal
 * shares, sending N − q back to a converger at that half, p of the rest to A and the others out of the build's side
 * for the rest. Each half's converger then carries N/2q ≤ 2/3 of a belt.
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

