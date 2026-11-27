package com.schwii.divvyrune.sound

import com.schwii.divvyrune.engine.Cycle
import com.schwii.divvyrune.engine.Graph
import com.schwii.divvyrune.engine.Kind
import com.schwii.divvyrune.engine.Level
import com.schwii.divvyrune.engine.Sim
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The instruments the synth plays. */
enum class Inst { PLUCK, TICK, BELL, KALIMBA, MARIMBA, GLASS, FELT, HAT, KNOCK }

/** One sound to start, [delay] seconds after its tick: [gain] before the mix, [pan] from -1 (left) to 1 (right). */
class Note(val inst: Inst, val freq: Float, val gain: Float, val pan: Float, val decay: Float, val delay: Float = 0f)

/**
 * Where the effects are heading: overall level, the lowpass cutoff (Hz), how much goes to the room and the echo, the
 * echo's feedback and time (s), and the flange's mix and sweep rate (Hz). The synth glides to each.
 */
data class Mix(
    val master: Float = 0f, val cutoff: Float = 18000f, val reverb: Float = 0f, val echo: Float = 0f,
    val echoFeedback: Float = 0.3f, val echoTime: Float = 0.5f, val flange: Float = 0f, val flangeRate: Float = 0.11f,
)

/** What sets off a sound: a marble appearing at an input, leaving a splitter, entering a converger, or waiting. */
enum class Role { APPEAR, SPLIT, JOIN, WAIT }

/** Where a role's pitch comes from: the marble's own, which input / branch / converger side, height on the board, or the root. */
enum class PitchFrom { MARBLE, WHICH, BOARD, ROOT }

/** How one role sounds: its own instrument, level, octave, ring (s) and pitch source. */
class Part(val inst: Inst, val vol: Float, val oct: Int, val decay: Float, val from: PitchFrom)

/**
 * The sound, as tuned in the sound lab ("Brett's rune"). Scales are semitones above the root; degrees count notes of
 * the scale from the root, kept between [LOW] and [HIGH].
 */
object Recipe {
    const val ROOT = 48                          // C, sounding an octave up
    val SOLVED = intArrayOf(0, 2, 4, 7, 9)       // major pentatonic
    val OPEN = intArrayOf(0, 2, 3, 7, 8)         // hirajoshi, until the build is right
    const val SPLIT_STEP = 3                     // a splitter fans its marbles 3 notes up and down
    const val INPUT_SPREAD = 5                   // input 2 starts 5 notes above input 1
    const val LOW = -8
    const val HIGH = 8
    const val REVERB = 0.25f
    const val SOFTNESS = 0.05f
    const val MAX_VOICES = 10
    const val HUMANIZE = 0.002f
    const val MASTER = 0.75f
    const val DRIFT = 1f
    const val TIDE = 0f
    const val SPACE = 1f
    const val WEIGHT = 0f
    val PARTS = mapOf(
        Role.APPEAR to Part(Inst.TICK, 0.28f, 0, 0.45f, PitchFrom.WHICH),
        Role.SPLIT to Part(Inst.PLUCK, 0.47f, 0, 0.9f, PitchFrom.MARBLE),
        Role.JOIN to Part(Inst.PLUCK, 0.31f, 0, 0.6f, PitchFrom.BOARD),
        Role.WAIT to Part(Inst.BELL, 0.76f, 0, 1.69f, PitchFrom.ROOT),
    )
    /** The order roles come in as a build plays, one more each pass of its loop. */
    val LAYERS = listOf(Role.SPLIT, Role.JOIN, Role.APPEAR, Role.WAIT)
}

/** A voice the rune adds to a role, fading in and out on its own tide. */
class Layer(val role: Role, val inst: Inst)

/**
 * A rune: one slow tide. It turns at (the build's loop) × [prime] × an irrational number, so no two tides ever line up.
 * [glyph] is which rune it shows; a held tide stays where it is.
 */
class Tide(val key: String, val glyph: Int, val prime: Int, val period: Double, var angle: Double, var held: Boolean) {
    /** How far it turns each tick (radians). */
    val step = 2 * PI / (period * Score.TIDE_SCALE)
    val value get() = sin(angle) * Recipe.DRIFT
    /** Where it is [alpha] of the way to the next tick: held, where it's held. */
    fun angleAt(alpha: Float) = if (held) angle else angle + step * alpha
}

/**
 * Turns the live simulation into notes. Each tick it reads what the marbles did (appeared, split, joined, waited),
 * gives each marble a pitch that splitters fan out, and mixes the result through the rune.
 *
 * The rune has two layers. In front is the game: until every way out is right the build plays in [Recipe.OPEN], dry and
 * close; once it is right it resolves to [Recipe.SOLVED] on the next loop's first step, with a bell chord, and the room
 * and echo open. Behind are the tides: filter, swell, stereo spread, flange, echo, and the ensemble's voices coming
 * and going. All of it comes from the build: the ensemble from its pieces, the tides' periods from the primes in the
 * level's rates, its piece counts and its loop, their starting points from how long it takes to settle.
 */
class Score(private val random: Random = Random.Default) {
    private val deg = HashMap<Int, Int>()
    private var sim: Sim? = null
    private var graph: Graph? = null
    private var cycle: Cycle? = null
    private var nodeX = DoubleArray(0)
    private var nodeY = DoubleArray(0)
    private var x0 = 0.0
    private var x1 = 1.0
    private var y1 = 0.0
    private var clock = 0L

    var tides: List<Tide> = emptyList(); private set
    var ensemble: List<Layer> = emptyList(); private set
    /** The build is playing as solved: it was right at the last loop's first step. */
    var solved = false; private set
    var mix = Mix(); private set

    private var holds = mapOf<String, Double>()
    /** Called with every held rune and where it's held, when that changes. */
    var onHolds: ((Map<String, Double>) -> Unit)? = null

    /** Holds the runes in [h] where they were held (a level's saved holds); every other rune turns. */
    fun setHolds(h: Map<String, Double>) {
        holds = h
        for (t in tides) {
            val a = h[t.key]
            t.held = a != null
            if (a != null) t.angle = a
        }
    }

    /** Holds rune [key] where it is now, or lets it turn again. */
    fun toggle(key: String) {
        val t = tides.firstOrNull { it.key == key } ?: return
        t.held = !t.held
        holds = if (t.held) holds + (key to t.angle) else holds - key
        onHolds?.invoke(holds)
    }

    /** How strongly a layer is playing now, 0 to 1. */
    fun layerGain(i: Int): Float = tides.getOrNull(BG.size + i)?.let { smooth(0.32, 0.68, 0.5 + 0.5 * it.value).toFloat() } ?: 0f

    /**
     * One tick: [sim] has just stepped. [exitsRight] is whether every way out meets its target right now; [tickSeconds]
     * is how long a tick lasts at the current speed. Returns the notes to play; [mix] has the effects' targets.
     */
    fun tick(sim: Sim, graph: Graph, level: Level, cycle: Cycle?, exitsRight: Boolean, wire: Boolean, tickSeconds: Float,
             maxVoices: Int = Recipe.MAX_VOICES): List<Note> {
        if (sim !== this.sim) { deg.clear(); this.sim = sim }
        if (graph !== this.graph || cycle != this.cycle) reseed(graph, level, cycle)
        clock++
        for (t in tides) if (!t.held) t.angle = (t.angle + t.step) % (2 * PI)

        val tk = sim.tick
        val settling = cycle == null || tk <= cycle.start
        val boundary = if (settling) tk % 16 == 1 else (tk - cycle!!.start - 1) % cycle.length == 0
        val out = ArrayList<Note>()
        val v = DoubleArray(BG.size) { tides.getOrNull(it)?.value ?: 0.0 }
        if (boundary) {
            if (exitsRight && !solved) bloom(out)
            solved = exitsRight
            val e = v[4]
            val ticks = if (e > 0.15) 3.0 else if (e < -0.15) 2.0 else 1.5
            echoTime = min(3.5, ticks * tickSeconds).toFloat()
        }

        // what the marbles did this tick
        val raw = ArrayList<Raw>()
        sim.n.forEachIndexed { i, sn ->
            val it = sn.item ?: return@forEachIndexed
            if (it.mt != tk || it.pf == i) return@forEachIndexed
            val from = sim.n[it.pf]
            var d: Int
            if (from.kind == Kind.SRC) {
                d = wrap(from.id * Recipe.INPUT_SPREAD)
                raw += Raw(Role.APPEAR, pitch(Role.APPEAR, d, from.id, it.pf), it.pf)
            } else {
                d = deg[it.id] ?: 0
                if (from.kind == Kind.SPLIT) {
                    val b = from.succ.indexOf(i)
                    d = wrap(d + splitOffset(from.succ.size, b))
                    raw += Raw(Role.SPLIT, pitch(Role.SPLIT, d, b, it.pf), it.pf)
                }
            }
            deg[it.id] = d
            if (sn.kind == Kind.MERGE) raw += Raw(Role.JOIN, pitch(Role.JOIN, d, sn.pred.indexOf(it.pf), i), i)
        }
        for (g in sim.ghosts) {
            val from = sim.n[g.from]
            if (g.item == null) { raw += Raw(Role.APPEAR, pitch(Role.APPEAR, wrap(from.id * Recipe.INPUT_SPREAD), from.id, g.from), g.from); continue }
            val d0 = deg.remove(g.id) ?: 0
            if (from.kind == Kind.SPLIT) {
                val b = from.succ.indexOf(g.to)
                val d = wrap(d0 + splitOffset(from.succ.size, b))
                raw += Raw(Role.SPLIT, pitch(Role.SPLIT, d, b, g.from), g.from)
            }
        }
        sim.waiting.forEachIndexed { i, w ->
            if (w && i < sim.n.size) raw += Raw(Role.WAIT, pitch(Role.WAIT, sim.n[i].item?.let { deg[it.id] } ?: 0, 0, i), i)
        }

        // the mix: roles come in one per pass of the loop; repeats merge; only the loudest few sound
        val pass = if (settling) -1 else (tk - cycle!!.start - 1) / cycle.length
        val merged = LinkedHashMap<Long, Merge>()
        for (r in raw) {
            if (Recipe.LAYERS.indexOf(r.role) > max(0, pass)) continue
            val key = r.role.ordinal.toLong() * 1000 + r.deg + 500
            val m = merged.getOrPut(key) { Merge(r.role, r.deg) }
            m.count++; m.x += nodeX.getOrElse(r.node) { 0.0 }
        }
        val picked = merged.values.sortedByDescending { it.gain }.take(maxVoices)
        val norm = 1 / sqrt(max(1.0, picked.size / 2.0))
        val spread = 0.55 + 0.45 * v[2]
        val hush = if (settling) 0.6 else 1.0
        val ring = 0.7 + 0.9 * Recipe.WEIGHT
        for (m in picked) {
            val part = Recipe.PARTS.getValue(m.role)
            val x = m.x / m.count
            val pan = ((x - x0) / max(1.0, x1 - x0) * 2 - 1) * 0.6 * spread
            val jitter = random.nextDouble() * Recipe.HUMANIZE
            val vel = 0.88 + random.nextDouble() * 0.24
            val freq = freqOf(m.deg, part.oct)
            for ((inst, lg) in layersFor(m.role, part.inst, wire)) {
                out += Note(inst, freq, (m.gain * norm * vel * 0.5 * hush * lg).toFloat(), pan.toFloat(), (part.decay * ring).toFloat(), jitter.toFloat())
            }
        }

        // where the effects head
        val sp = Recipe.SPACE * 2
        val light = (1.25 - 0.5 * Recipe.WEIGHT) * (if (wire) 1.35 else 1.0) * (1 + 0.5 * v[0])
        val room = (if (solved) 1.5 else 0.6) * sp + if (wire) 0.2 else 0.0
        mix = Mix(
            master = (Recipe.MASTER * (1 + 0.18 * v[1])).toFloat(),
            cutoff = min(18000.0, (14000 * (1.0 - Recipe.SOFTNESS).pow(1.6) + 1400) * (if (settling) 0.35 else 1.0) * light).toFloat(),
            reverb = (min(1.0, Recipe.REVERB * room) * 0.9).toFloat(),
            echo = ((if (solved) 0.3 else 0.05) * sp).toFloat(),
            echoFeedback = (0.28 + 0.2 * v[4]).toFloat(),
            echoTime = echoTime,
            flange = (0.4 * max(0.0, v[3])).toFloat(),
            flangeRate = (0.08 + 0.06 * (1 + v[3])).toFloat(),
        )
        return out
    }

    private var echoTime = 0.5f

    private class Raw(val role: Role, val deg: Int, val node: Int)
    private class Merge(val role: Role, val deg: Int) {
        var count = 0
        var x = 0.0
        val gain get() = Recipe.PARTS.getValue(role).vol * (1 + 0.25 * log2(count.toDouble()))
    }

    /** The solved chord: four soft bells, a little spread. */
    private fun bloom(out: MutableList<Note>) {
        intArrayOf(0, 2, 4, 7).forEachIndexed { i, d ->
            out += Note(Inst.BELL, freqOf(d, 1, Recipe.SOLVED), 0.12f, ((i - 1.5) * 0.3).toFloat(), 2.4f, i * 0.07f)
        }
    }

    /** The instruments playing [role] now: its own, and the ensemble's voices for it as their tides allow. */
    fun layersFor(role: Role, base: Inst, wire: Boolean): List<Pair<Inst, Double>> {
        val out = ArrayList<Pair<Inst, Double>>()
        var most = 0.0
        ensemble.forEachIndexed { i, l ->
            if (l.role != role) return@forEachIndexed
            val g = layerGain(i).toDouble()
            most = max(most, g)
            if (g > 0.04) out += skin(l.inst, wire) to g * 0.8
        }
        out.add(0, skin(base, wire) to 1 - 0.55 * most)
        return out
    }

    private fun pitch(role: Role, marble: Int, which: Int, node: Int): Int = when (Recipe.PARTS.getValue(role).from) {
        PitchFrom.MARBLE -> marble
        PitchFrom.WHICH -> which * 2
        PitchFrom.BOARD -> (y1 - nodeY.getOrElse(node) { y1 }).roundToInt()
        PitchFrom.ROOT -> 0
    }

    private fun splitOffset(k: Int, b: Int): Int = when {
        k == 2 -> if (b == 0) Recipe.SPLIT_STEP else -Recipe.SPLIT_STEP
        b == 0 -> Recipe.SPLIT_STEP
        b == 1 -> 0
        else -> -Recipe.SPLIT_STEP
    }

    /** The frequency of scale degree [d], [oct] octaves up, in the scale the build is playing in. */
    fun freqOf(d: Int, oct: Int, scale: IntArray = if (solved) Recipe.SOLVED else Recipe.OPEN): Float {
        val n = scale.size
        val o = Math.floorDiv(d, n)
        val i = Math.floorMod(d, n)
        return (440.0 * 2.0.pow((Recipe.ROOT + 12 * (o + oct) + scale[i] - 69) / 12.0) * 2).toFloat()
    }

    /** The rune comes from the build: its ensemble, its tides' primes and periods, where they start. */
    private fun reseed(graph: Graph, level: Level, cycle: Cycle?) {
        this.graph = graph; this.cycle = cycle
        val nodes = graph.nodes
        nodeX = DoubleArray(nodes.size) { nodes[it].frame.cx(nodes[it].local) }
        nodeY = DoubleArray(nodes.size) { nodes[it].frame.cy(nodes[it].local) }
        x0 = nodeX.minOrNull() ?: 0.0; x1 = nodeX.maxOrNull() ?: 1.0; y1 = nodeY.maxOrNull() ?: 0.0
        val nS = nodes.count { it.kind == Kind.SPLIT }
        val nM = nodes.count { it.kind == Kind.MERGE }
        val nI = level.inputs.size
        val nO = level.out.size
        val len = cycle?.length ?: 0
        val start = cycle?.start ?: 0

        // more splitters bring more melody voices; convergers bring voices for the joins; inputs bring percussion
        val layers = ArrayList<Layer>()
        fun pick(role: Role, k: Int, off: Int) {
            val pool = POOLS.getValue(role).filter { it != Recipe.PARTS.getValue(role).inst }
            for (i in 0 until k) layers += Layer(role, pool[(off + i) % pool.size])
        }
        pick(Role.SPLIT, min(3, 1 + nS / 2), nS)
        pick(Role.JOIN, min(2, nM), nM)
        pick(Role.APPEAR, min(2, nI), nO + nS)
        ensemble = layers

        val primes = runePrimes(level, nS, nM, len)
        val keys = BG.map { it } + layers.map { "${it.role.name.lowercase()}:${it.inst.name.lowercase()}" }
        val old = tides.associateBy { it.key }
        val base = max(len, 12).toDouble()
        tides = keys.mapIndexed { i, key ->
            val p = primes[i]
            val period = base * p * IRR[i % IRR.size] / 2
            val glyph = if (i < BG.size) i else BG.size + (i - BG.size) % LAYER_GLYPHS
            val held = holds[key]
            val prev = old[key]
            val angle = held ?: prev?.angle ?: ((2 * PI * frac(start * PHI * (i + 1)) + 2 * PI * clock / (period * TIDE_SCALE)) % (2 * PI))
            Tide(key, glyph, p, period, angle, held != null)
        }
    }

    companion object {
        /** The background tides, in order: filter, swell, spread, flange, echo; the ensemble's voices follow. */
        val BG = listOf("filter", "swell", "spread", "flange", "echo")
        /** How many glyphs there are for the ensemble's voices, after the background tides' five. */
        const val LAYER_GLYPHS = 7
        internal const val TIDE_SCALE = 0.4   // 0.4 × 10^TIDE: Recipe.TIDE = 0, the quickest tides
        private val PHI = (1 + sqrt(5.0)) / 2
        private val IRR = doubleArrayOf(PI, PHI, sqrt(2.0), Math.E, sqrt(3.0), sqrt(5.0), PI * PI / 4, ln(2.0) * 3, sqrt(7.0), cbrt(2.0) * 2, ln(10.0), sqrt(11.0))
        private val POOLS = mapOf(
            Role.SPLIT to listOf(Inst.KALIMBA, Inst.BELL, Inst.MARIMBA, Inst.GLASS),
            Role.JOIN to listOf(Inst.MARIMBA, Inst.FELT, Inst.KALIMBA, Inst.BELL),
            Role.APPEAR to listOf(Inst.HAT, Inst.KNOCK, Inst.TICK),
        )
        private val WIRE = mapOf(Inst.MARIMBA to Inst.GLASS, Inst.FELT to Inst.BELL, Inst.KNOCK to Inst.TICK, Inst.KALIMBA to Inst.GLASS)

        /** The wireframe look swaps wooden sounds for glassy ones. */
        fun skin(i: Inst, wire: Boolean) = if (wire) WIRE[i] ?: i else i

        /**
         * The rune's numbers: the odd primes in the level's rates, then in its splitter and converger counts and its
         * loop length, then the odd primes from 5 up that aren't there yet.
         */
        fun runePrimes(level: Level, splitters: Int, convergers: Int, loop: Int): List<Int> {
            val out = ArrayList<Int>()
            fun add(n: Int) { for (q in primesOf(n)) if (q > 2 && q !in out) out += q }
            for (f in level.inputs + level.out.filterNotNull()) { if (f.n > 1) add(f.n); if (f.d > 1) add(f.d) }
            add(splitters); add(convergers); if (loop > 0) add(loop)
            var q = 5
            while (out.size < 64) { if (primesOf(q).singleOrNull() == q && q !in out) out += q; q += 2 }
            return out
        }

        fun primesOf(n: Int): List<Int> {
            val out = ArrayList<Int>()
            var m = abs(n)
            var p = 2
            while (p * p <= m) { while (m % p == 0) { if (p !in out) out += p; m /= p }; p++ }
            if (m > 1 && m !in out) out += m
            return out
        }

        private fun wrap(d: Int): Int { val span = Recipe.HIGH - Recipe.LOW + 1; return Math.floorMod(d - Recipe.LOW, span) + Recipe.LOW }
        private fun frac(x: Double) = x - kotlin.math.floor(x)
        private fun smooth(a: Double, b: Double, x: Double): Double { val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
    }
}
