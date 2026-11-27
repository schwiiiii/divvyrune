package com.schwii.divvyrune.sound

import com.schwii.divvyrune.engine.Cycle
import com.schwii.divvyrune.engine.Graph
import com.schwii.divvyrune.engine.Kind
import com.schwii.divvyrune.engine.Level
import com.schwii.divvyrune.engine.Sim
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.floor
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
 * echo's feedback and time (s), the flange's mix and sweep rate (Hz), and the low lift (overtones that let a phone's
 * small speaker suggest the bass). The synth glides to each.
 */
data class Mix(
    val master: Float = 0f, val cutoff: Float = 18000f, val reverb: Float = 0f, val echo: Float = 0f,
    val echoFeedback: Float = 0.3f, val echoTime: Float = 0.5f, val flange: Float = 0f, val flangeRate: Float = 0.11f,
    val lift: Float = 0f,
)

/** What sets off a sound: a marble appearing at an input, leaving a splitter, entering a converger, leaving the build, or waiting. */
enum class Role { APPEAR, SPLIT, JOIN, VANISH, WAIT }

/** Where a role's pitch comes from: the marble's own, which input / branch / converger side / way out, height on the board, or the root. */
enum class PitchFrom { MARBLE, WHICH, BOARD, ROOT }

/** How one role sounds: its own instrument, level, octave, ring (s) and pitch source. */
class Part(val inst: Inst, val vol: Float, val oct: Int, val decay: Float, val from: PitchFrom)

/** A sound that played this tick, for the board to flash: the piece that made it, the marble (if any), how loud. */
class Flash(val node: Int, val marble: Int, val role: Role, val gain: Float)

/** The chord playing: its notes as scale degrees, whether its way out is right yet, and a name ("IV", "Vsus"). */
class Chord(val tones: IntArray, val right: Boolean, val label: String)

/**
 * The sound, as tuned in the sound lab ("Brett's chords"). Degrees count notes of the level's mode from its root;
 * marbles carry an index into the chord's notes, kept within [LOW]..[HIGH] scale degrees' worth.
 */
object Recipe {
    const val ROOT = 48                          // C, sounding an octave up; each level moves it round the circle of fifths
    const val LOW = -8
    const val HIGH = 8
    const val REVERB = 0.75f
    const val SOFTNESS = 0.1f
    const val MAX_VOICES = 10
    const val HUMANIZE = 0.002f
    const val MASTER = 0.5f
    const val DRIFT = 1f
    const val TIDE = 0f
    const val SPACE = 1f
    const val WEIGHT = 0f
    const val LIFT = 0.25f
    /** Pitched notes below this many Hz move up an octave at a time, so a phone's speaker can play them. */
    const val FLOOR = 200f
    val PARTS = mapOf(
        Role.APPEAR to Part(Inst.TICK, 0.28f, 0, 0.45f, PitchFrom.WHICH),
        Role.SPLIT to Part(Inst.GLASS, 0.1f, 0, 1.97f, PitchFrom.MARBLE),
        Role.JOIN to Part(Inst.PLUCK, 0.31f, 1, 0.6f, PitchFrom.BOARD),
        Role.VANISH to Part(Inst.KALIMBA, 0.19f, 0, 1.6f, PitchFrom.MARBLE),
        Role.WAIT to Part(Inst.BELL, 0.76f, 0, 1.69f, PitchFrom.ROOT),
    )
    /** The order roles come in as a build plays. A marble leaving is always heard. */
    val LAYERS = listOf(Role.SPLIT, Role.JOIN, Role.APPEAR, Role.WAIT)
    /** The modes, bright to dark, walked across the levels. */
    val MODES = listOf(
        intArrayOf(0, 2, 4, 6, 7, 9, 11),   // Lydian
        intArrayOf(0, 2, 4, 5, 7, 9, 11),   // Ionian
        intArrayOf(0, 2, 4, 5, 7, 9, 10),   // Mixolydian
        intArrayOf(0, 2, 3, 5, 7, 9, 10),   // Dorian
        intArrayOf(0, 2, 3, 5, 7, 8, 10),   // Aeolian
    )
    /** A path's length (mod 7) to a chord: I, IV, V, vi, ii, iii, vii. */
    val FUNC = intArrayOf(0, 3, 4, 5, 1, 2, 6)
    val ROMAN = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
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
 * Turns the live simulation into notes. Each tick it reads what the marbles did (appeared, split, joined, left, waited)
 * and plays it through the build's harmony, rhythm and rune.
 *
 * Harmony: each level has its key (the circle of fifths, level by level) and mode (Lydian darkening to Aeolian across
 * the levels). Each way out owns a chord, its root from the length of the path into it (two ways out never share
 * one), and the build plays one chord per pass of its loop. A way out that isn't right yet hangs on a sus chord; once
 * every way out is right, a bell chord rings and the room and echo open. Convergers colour the chords with a 7th.
 * Marbles carry an index into the chord's notes: a 2-way splitter keeps it or moves it up two chord notes, a 3-way
 * moves it down one, keeps it, or moves it up one. Every other pitch is held to the chord.
 *
 * Rhythm: each piece plays at its own rate. A splitter sounds once a round (as its turn wraps to its first exit), a
 * converger once per turn of its first input, inputs only on the loop's accents (spread evenly through it), and ways
 * out every time a marble leaves. Off-beats are softer; a marble that has come a long way rings longer. Sounds come in
 * one at a time as the build starts (a marble leaving always sounds).
 *
 * Behind it all are the tides: filter, swell, stereo spread, flange, echo, and the ensemble's voices coming and going,
 * all from the build: the ensemble from its pieces, the periods from the primes in the level's rates, its piece counts
 * and its loop, their starting points from how long it takes to settle.
 */
class Score(private val random: Random = Random.Default) {
    private val tone = HashMap<Int, Int>()   // each marble's index into the chord's notes
    private val born = HashMap<Int, Int>()   // the tick each marble appeared
    private var sim: Sim? = null
    private var graph: Graph? = null
    private var cycle: Cycle? = null
    private var levelKey = ""
    private var nodeX = DoubleArray(0)
    private var nodeY = DoubleArray(0)
    private var x0 = 0.0
    private var x1 = 1.0
    private var y1 = 0.0
    private var clock = 0L
    private var exits = IntArray(0)
    private var prog = IntArray(0)
    private var splitters = 0
    private var convergers = 0
    private var mode = Recipe.MODES[1]
    private var key = 0

    var tides: List<Tide> = emptyList(); private set
    var ensemble: List<Layer> = emptyList(); private set
    /** Every way out was right at the last loop's start. */
    var solved = false; private set
    var chord: Chord? = null; private set
    var mix = Mix(); private set
    /** The sounds that played on the last tick, for the board to flash. */
    var flashes: List<Flash> = emptyList(); private set

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
     * One tick: [sim] has just stepped. Level [number] of [count] sets the key and mode; [exitRight] says whether the way
     * out at a node is right now; [tickSeconds] is how long a tick lasts at the current speed. Returns the notes to play;
     * [mix] has the effects' targets and [flashes] what sounded.
     */
    fun tick(
        sim: Sim, graph: Graph, level: Level, cycle: Cycle?, number: Int, count: Int, exitRight: (Int) -> Boolean,
        wire: Boolean, tickSeconds: Float, maxVoices: Int = Recipe.MAX_VOICES,
    ): List<Note> {
        if (sim !== this.sim) { tone.clear(); born.clear(); this.sim = sim }
        val lk = "${level.name}/$number/$count"
        if (graph !== this.graph || cycle != this.cycle || lk != levelKey) { levelKey = lk; reseed(graph, level, cycle, number, count) }
        clock++
        for (t in tides) if (!t.held) t.angle = (t.angle + t.step) % (2 * PI)

        val tk = sim.tick
        val settling = cycle == null || tk <= cycle.start
        val step = if (settling) (tk - 1).mod(16) else (tk - cycle!!.start - 1) % cycle.length
        val bar = if (settling) 16 else cycle!!.length
        val pass = if (settling) -1 else (tk - cycle!!.start - 1) / cycle.length
        val out = ArrayList<Note>()
        val v = DoubleArray(BG.size) { tides.getOrNull(it)?.value ?: 0.0 }

        // the loop's start: the next chord, and whether the build resolves
        if (step == 0 || chord == null) {
            val right = exits.map { exitRight(it) }
            val all = exits.isNotEmpty() && right.all { it }
            if (all && !solved) bloom(out)
            solved = all
            val e = v[4]
            echoTime = min(3.5, (if (e > 0.15) 3.0 else if (e < -0.15) 2.0 else 1.5) * tickSeconds).toFloat()
            chord = if (exits.isEmpty()) Chord(intArrayOf(0, 2, 4), false, "I") else {
                val i = max(0, pass).mod(exits.size)
                val root = prog[i]
                val ok = right[i]
                val tones = if (ok) intArrayOf(root, root + 2, root + 4) + (if (convergers > 0) intArrayOf(root + 6) else IntArray(0))
                    else intArrayOf(root, root + 3, root + 4)
                Chord(tones, ok, Recipe.ROMAN[root] + if (!ok) "sus" else if (convergers > 0) "7" else "")
            }
            pad(out, chord!!, bar * tickSeconds)
        }

        // what the marbles did this tick
        val hits = max(1, min(bar - 1, exits.size + splitters))
        val accent = step * hits % bar < hits
        val raw = ArrayList<Raw>()
        sim.n.forEachIndexed { i, sn ->
            val it = sn.item ?: return@forEachIndexed
            if (it.mt != tk || it.pf == i) return@forEachIndexed
            val from = sim.n[it.pf]
            var t: Int
            if (from.kind == Kind.SRC) {
                t = wrapTone(from.id * 2)
                born[it.id] = tk
                if (accent) raw += Raw(Role.APPEAR, pitch(Role.APPEAR, t, from.id, it.pf), it.pf, it.id)
            } else {
                t = tone[it.id] ?: 0
                if (from.kind == Kind.SPLIT) {
                    val k = from.succ.size
                    val b = from.succ.indexOf(i)
                    t = splitMove(t, k, b)
                    if (b == k - 1) raw += Raw(Role.SPLIT, pitch(Role.SPLIT, t, b, it.pf), it.pf, it.id)   // once a round
                }
            }
            tone[it.id] = t
            if (sn.kind == Kind.MERGE && sn.pred.indexOf(it.pf) == 0) raw += Raw(Role.JOIN, pitch(Role.JOIN, t, 0, i), i, it.id)
        }
        for (g in sim.ghosts) {
            val from = sim.n[g.from]
            val ex = exits.indexOf(g.to)
            if (g.item == null) {
                // straight from an input to a way out
                val t = wrapTone(from.id * 2)
                if (accent) raw += Raw(Role.APPEAR, pitch(Role.APPEAR, t, from.id, g.from), g.from, g.id)
                raw += Raw(Role.VANISH, pitch(Role.VANISH, t, ex, g.to), g.to, g.id)
                continue
            }
            var t = tone.remove(g.id) ?: 0
            born.remove(g.id)?.let { bornLeaving[g.id] = it }
            if (from.kind == Kind.SPLIT) {
                val k = from.succ.size
                val b = from.succ.indexOf(g.to)
                t = splitMove(t, k, b)
                if (b == k - 1) raw += Raw(Role.SPLIT, pitch(Role.SPLIT, t, b, g.from), g.from, g.id)
            }
            raw += Raw(Role.VANISH, pitch(Role.VANISH, t, ex, g.to), g.to, g.id)
        }
        sim.waiting.forEachIndexed { i, w ->
            if (!w || i >= sim.n.size) return@forEachIndexed
            val item = sim.n[i].item
            raw += Raw(Role.WAIT, pitch(Role.WAIT, item?.let { tone[it.id] } ?: 0, 0, i), i, item?.id ?: -1)
        }

        // the mix: sounds come in one at a time; repeats merge; only the loudest few sound
        val order = Recipe.LAYERS.filter { (it != Role.SPLIT || splitters > 0) && (it != Role.JOIN || convergers > 0) }
        val layersIn = if (settling) 0 else (tk - cycle!!.start - 1) / min(cycle.length, 8)
        val merged = LinkedHashMap<Long, Merge>()
        for (r in raw) {
            if (r.role != Role.VANISH && order.indexOf(r.role) > layersIn) continue
            val key = r.role.ordinal.toLong() * 10000 + r.deg + 5000
            val m = merged.getOrPut(key) { Merge(r.role, r.deg) }
            m.count++; m.x += nodeX.getOrElse(r.node) { 0.0 }; m.nodes += r.node; if (r.marble >= 0) m.marbles += r.marble
        }
        // ways out first (a marble leaving is always heard), then the loudest
        val picked = merged.values.sortedWith(compareByDescending<Merge> { it.role == Role.VANISH }.thenByDescending { it.gain }).take(maxVoices)
        val norm = 1 / sqrt(max(1.0, picked.size / 2.0))
        val spread = 0.55 + 0.45 * v[2]
        val hush = if (settling) 0.6 else 1.0
        val acc = if (accent) 1.25 else 0.6
        val ring = 0.7 + 0.9 * Recipe.WEIGHT
        val fl = ArrayList<Flash>()
        for (m in picked) {
            val part = Recipe.PARTS.getValue(m.role)
            val x = m.x / m.count
            val pan = ((x - x0) / max(1.0, x1 - x0) * 2 - 1) * 0.6 * spread
            val jitter = random.nextDouble() * Recipe.HUMANIZE
            val vel = 0.88 + random.nextDouble() * 0.24
            val freq = freqOf(m.deg, part.oct)
            // a marble that has come a long way rings longer
            val first = m.marbles.firstOrNull()
            val age = first?.let { id -> tk - (born[id] ?: bornLeaving[id] ?: tk) } ?: 0
            val far = if (first != null) (0.5 + age / 10.0).coerceIn(0.5, 2.2) else 1.0
            val g = m.gain * norm * vel * 0.5 * hush * acc
            for ((inst, lg) in layersFor(m.role, part.inst, wire)) {
                out += Note(inst, lifted(freq, inst), (g * lg).toFloat(), pan.toFloat(), (part.decay * ring * far).toFloat(), jitter.toFloat())
            }
            for (n in m.nodes.distinct()) fl += Flash(n, -1, m.role, g.toFloat())
            for (id in m.marbles) fl += Flash(-1, id, m.role, g.toFloat())
        }
        flashes = fl
        bornLeaving.clear()

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
            lift = Recipe.LIFT * 1.2f,
        )
        return out
    }

    private var echoTime = 0.5f
    private val bornLeaving = HashMap<Int, Int>()

    private class Raw(val role: Role, val deg: Int, val node: Int, val marble: Int)
    private class Merge(val role: Role, val deg: Int) {
        var count = 0
        var x = 0.0
        val nodes = ArrayList<Int>(2)
        val marbles = ArrayList<Int>(2)
        val gain get() = Recipe.PARTS.getValue(role).vol * (1 + 0.25 * log2(count.toDouble()))
    }

    /** The solved chord: four soft bells, a little spread. */
    private fun bloom(out: MutableList<Note>) {
        intArrayOf(0, 2, 4, 7).forEachIndexed { i, d ->
            out += Note(Inst.BELL, freqOf(d, 1), 0.12f, ((i - 1.5) * 0.3).toFloat(), 2.4f, i * 0.07f)
        }
    }

    /** The chord, softly, under the bar. */
    private fun pad(out: MutableList<Note>, c: Chord, seconds: Float) {
        c.tones.forEachIndexed { i, d ->
            out += Note(Inst.FELT, lifted(freqOf(d, -1), Inst.FELT), if (i == 0) 0.09f else 0.06f, (i - 1) * 0.25f, min(3.5f, seconds), i * 0.02f)
        }
    }

    /** A pitched note under [Recipe.FLOOR] moves up an octave at a time. */
    private fun lifted(f: Float, inst: Inst): Float {
        if (inst == Inst.TICK || inst == Inst.HAT) return f
        var x = f
        while (x < Recipe.FLOOR) x *= 2
        return x
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

    /** A role's pitch as a scale degree: a marble's chord note, or anything else held to the chord. */
    private fun pitch(role: Role, marble: Int, which: Int, node: Int): Int = when (Recipe.PARTS.getValue(role).from) {
        PitchFrom.MARBLE -> degOfTone(marble)
        PitchFrom.WHICH -> snap(max(0, which) * 2)
        PitchFrom.BOARD -> snap((y1 - nodeY.getOrElse(node) { y1 }).roundToInt())
        PitchFrom.ROOT -> snap(0)
    }

    private fun tones() = chord?.tones ?: intArrayOf(0, 2, 4)

    /** A 2-way splitter keeps a marble's chord note or moves it up two; a 3-way moves it down one, keeps it, or up one. */
    private fun splitMove(t: Int, k: Int, b: Int) = wrapTone(t + if (k == 2) (if (b == 0) 0 else 2) else b - 1)

    /** Chord-note indices span [Recipe.LOW]..[Recipe.HIGH] scale degrees' worth for the chord playing. */
    private fun wrapTone(i: Int): Int {
        val n = tones().size
        val lo = floor(Recipe.LOW * n / 7.0).toInt()
        val hi = ceil(Recipe.HIGH * n / 7.0).toInt()
        return Math.floorMod(i - lo, hi - lo + 1) + lo
    }

    private fun degOfTone(i: Int): Int { val t = tones(); return t[Math.floorMod(i, t.size)] + 7 * Math.floorDiv(i, t.size) }

    /** The nearest note of the chord to degree [d]. */
    private fun snap(d: Int): Int {
        val set = tones().map { Math.floorMod(it, 7) }
        for (o in intArrayOf(0, 1, -1, 2, -2, 3, -3)) if (Math.floorMod(d + o, 7) in set) return d + o
        return d
    }

    /** The frequency of degree [d] of this level's mode, [oct] octaves up. */
    fun freqOf(d: Int, oct: Int): Float {
        val o = Math.floorDiv(d, 7)
        val i = Math.floorMod(d, 7)
        return (440.0 * 2.0.pow((Recipe.ROOT + key + 12 * (o + oct) + mode[i] - 69) / 12.0) * 2).toFloat()
    }

    /** The rune comes from the build: its harmony, its ensemble, its tides' primes and periods, where they start. */
    private fun reseed(graph: Graph, level: Level, cycle: Cycle?, number: Int, count: Int) {
        this.graph = graph; this.cycle = cycle
        val nodes = graph.nodes
        nodeX = DoubleArray(nodes.size) { nodes[it].frame.cx(nodes[it].local) }
        nodeY = DoubleArray(nodes.size) { nodes[it].frame.cy(nodes[it].local) }
        x0 = nodeX.minOrNull() ?: 0.0; x1 = nodeX.maxOrNull() ?: 1.0; y1 = nodeY.maxOrNull() ?: 0.0
        splitters = nodes.count { it.kind == Kind.SPLIT }
        convergers = nodes.count { it.kind == Kind.MERGE }
        val nI = level.inputs.size
        val nO = level.out.size
        val len = cycle?.length ?: 0
        val start = cycle?.start ?: 0

        // the level's key and mode
        val s = (number - 1).mod(12) * 7 % 12
        key = if (s > 6) s - 12 else s
        mode = Recipe.MODES[min(Recipe.MODES.size - 1, max(0, (number - 1) * Recipe.MODES.size / max(1, count)))]

        // the chords: one per way out, from the length of the path into it
        val hops = IntArray(nodes.size) { -1 }
        val q = ArrayDeque<Int>()
        nodes.indices.filter { nodes[it].kind == Kind.SRC }.forEach { hops[it] = 0; q += it }
        while (q.isNotEmpty()) { val i = q.removeFirst(); for (j in nodes[i].succ) if (hops[j] < 0) { hops[j] = hops[i] + 1; q += j } }
        exits = graph.exits.toIntArray()
        val used = ArrayList<Int>()
        prog = IntArray(exits.size) { e ->
            var k = max(0, hops[exits[e]]) % 7
            while (Recipe.FUNC[k] in used) k = (k + 1) % 7
            Recipe.FUNC[k].also { used += it }
        }
        chord = null

        // more splitters bring more melody voices; convergers bring voices for the joins; inputs bring percussion
        val layers = ArrayList<Layer>()
        fun pick(role: Role, k: Int, off: Int) {
            val pool = POOLS.getValue(role).filter { it != Recipe.PARTS.getValue(role).inst }
            for (i in 0 until k) layers += Layer(role, pool[(off + i) % pool.size])
        }
        pick(Role.SPLIT, min(3, 1 + splitters / 2), splitters)
        pick(Role.JOIN, min(2, convergers), convergers)
        pick(Role.APPEAR, min(2, nI), nO + splitters)
        ensemble = layers

        val primes = runePrimes(level, splitters, convergers, len)
        val keys = BG + layers.map { "${it.role.name.lowercase()}:${it.inst.name.lowercase()}" }
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

        private fun frac(x: Double) = x - floor(x)
        private fun smooth(a: Double, b: Double, x: Double): Double { val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
    }
}
