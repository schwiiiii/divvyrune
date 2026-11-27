package com.schwii.divvyrune.sound

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * A small software synth: the voices [Note]s ask for, through a stereo bus with a room (a Freeverb-style reverb), an
 * echo, a lowpass, a flange, and a gentle compressor. Pure Kotlin, so it renders the same in tests as on a phone.
 * [play] may be called from any thread; [render] runs on the audio thread.
 */
class Synth(val rate: Int) {
    private class Pending(val note: Note, val at: Long)
    private val queue = ConcurrentLinkedQueue<Pending>()
    @Volatile private var target = Mix()
    @Volatile var rendered = 0L; private set

    private val voices = ArrayList<Voice>()
    private val pending = ArrayList<Pending>()

    /** Starts [notes] as soon as the next block renders (plus each one's own delay), heading the effects for [mix]. */
    fun play(notes: List<Note>, mix: Mix) {
        val now = rendered
        for (n in notes) queue.add(Pending(n, now + (n.delay * rate).toLong()))
        target = mix
    }

    /** Lets the effects head for [mix] without new notes. */
    fun steer(mix: Mix) { target = mix }

    // ---------- the bus ----------
    private var master = 0f; private var cutoff = 18000f; private var rev = 0f; private var echo = 0f
    private var echoFb = 0.3f; private var echoTime = 0.5f; private var flange = 0f; private var flRate = 0.11f; private var lift = 0f
    // low lift: the bass alone, saturated into overtones a small speaker can play, the bass itself taken back out
    private val liftLpL = Biquad().apply { lowpass(250f, 0.7f, rate) }; private val liftLpR = Biquad().apply { lowpass(250f, 0.7f, rate) }
    private val liftHpL = Biquad().apply { highpass(220f, 0.7f, rate) }; private val liftHpR = Biquad().apply { highpass(220f, 0.7f, rate) }
    private val lpL = Biquad(); private val lpR = Biquad()
    private val room = Room(rate)
    private val echoL = FloatArray(rate * 4 + 2); private val echoR = FloatArray(rate * 4 + 2)
    private var echoW = 0
    private var echoSamples = 0.5f * rate
    private var echoLpL = 0f; private var echoLpR = 0f
    private val flL = FloatArray(rate / 40 + 2); private val flR = FloatArray(rate / 40 + 2)
    private var flW = 0
    private var flPhase = 0.0
    private var comp = 0f
    private var hpL = 0f; private var hpR = 0f
    private val HP = 1 - exp(-2 * PI.toFloat() * 35f / rate)
    private var quiet = 0L

    private val busL = FloatArray(BLOCK); private val busR = FloatArray(BLOCK)

    /** Fills [out] with [frames] interleaved stereo frames. */
    fun render(out: FloatArray, frames: Int) {
        var off = 0
        while (off < frames) {
            val n = min(BLOCK, frames - off)
            block(out, off, n)
            off += n
        }
    }

    private fun glide(cur: Float, to: Float, tau: Float, n: Int) = cur + (to - cur) * (1 - exp(-n.toFloat() / (rate * tau)))

    private fun block(out: FloatArray, off: Int, n: Int) {
        val start = rendered
        while (true) pending += queue.poll() ?: break
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.at < start + n) { voices += Voice.of(p.note, rate, max(0, (p.at - start).toInt())); it.remove() }
        }
        while (voices.size > MAX_VOICES) voices.removeAt(0)
        val m = target
        master = glide(master, m.master, 0.3f, n)
        cutoff = glide(cutoff, m.cutoff, 0.5f, n)
        rev = glide(rev, m.reverb, 1.2f, n)
        echo = glide(echo, m.echo, 1.2f, n)
        echoFb = glide(echoFb, m.echoFeedback, 0.5f, n)
        echoTime = glide(echoTime, m.echoTime, 0.6f, n)
        flange = glide(flange, m.flange, 0.5f, n)
        flRate = glide(flRate, m.flangeRate, 0.5f, n)
        lift = glide(lift, m.lift, 0.05f, n)

        busL.fill(0f, 0, n); busR.fill(0f, 0, n)
        val vi = voices.iterator()
        while (vi.hasNext()) if (!vi.next().render(busL, busR, n)) vi.remove()

        // asleep: nothing playing and every tail long gone
        if (voices.isEmpty() && pending.isEmpty()) quiet += n else quiet = 0
        if (quiet > rate * 8L) { out.fill(0f, off * 2, (off + n) * 2); rendered += n; return }

        lpL.lowpass(cutoff.coerceIn(40f, rate * 0.45f), 0.5f, rate); lpR.copy(lpL)
        val echoTarget = (echoTime * rate).coerceIn(1f, echoL.size - 2f)
        val flDepth = 0.0022f * rate; val flBase = 0.003f * rate
        val lpA = 1 - exp(-2 * PI.toFloat() * 2400f / rate)
        for (i in 0 until n) {
            val dl = busL[i]; val dr = busR[i]
            // room
            room.process(dl * rev, dr * rev)
            // echo: a darkened repeat that feeds back
            echoSamples += (echoTarget - echoSamples) * 0.0005f
            val eL = readFrac(echoL, echoW, echoSamples); val eR = readFrac(echoR, echoW, echoSamples)
            echoLpL += (eL - echoLpL) * lpA; echoLpR += (eR - echoLpR) * lpA
            echoL[echoW] = dl * echo + echoLpL * echoFb; echoR[echoW] = dr * echo + echoLpR * echoFb
            echoW = (echoW + 1) % echoL.size
            // master: everything through the lowpass
            var l = (dl + room.outL + echoLpL) * master
            var r = (dr + room.outR + echoLpR) * master
            l = lpL.process(l); r = lpR.process(r)
            // flange: a short swept delay with feedback
            flPhase += flRate / rate
            if (flPhase > 1) flPhase -= 1
            val d = flBase + flDepth * sin(2 * PI * flPhase).toFloat()
            val fL = readFrac(flL, flW, d); val fR = readFrac(flR, flW, d)
            flL[flW] = l + fL * 0.5f; flR[flW] = r + fR * 0.5f
            flW = (flW + 1) % flL.size
            l += fL * flange; r += fR * flange
            l += liftHpL.process(tanh(6 * liftLpL.process(l - fL * flange))) * lift
            r += liftHpR.process(tanh(6 * liftLpR.process(r - fR * flange))) * lift
            // compressor, then a soft ceiling
            val peak = max(abs(l), abs(r))
            comp += (peak - comp) * if (peak > comp) ATTACK else RELEASE
            val g = if (comp > THRESHOLD) (THRESHOLD * (comp / THRESHOLD).pow(0.25f)) / comp else 1f
            // a gentle low cut under everything, then out
            l *= g * MAKEUP; r *= g * MAKEUP
            hpL += HP * (l - hpL); hpR += HP * (r - hpR)
            out[(off + i) * 2] = tanh(l - hpL)
            out[(off + i) * 2 + 1] = tanh(r - hpR)
        }
        rendered += n
    }

    private val ATTACK = 1 - exp(-1f / (0.004f * rate))
    private val RELEASE = 1 - exp(-1f / (0.2f * rate))

    /** Reads [buf], written up to [w], [delay] samples back, between samples. */
    private fun readFrac(buf: FloatArray, w: Int, delay: Float): Float {
        val p = w - delay
        val i = kotlin.math.floor(p).toInt()
        val f = p - i
        val a = buf[Math.floorMod(i, buf.size)]; val b = buf[Math.floorMod(i + 1, buf.size)]
        return a + (b - a) * f
    }

    companion object {
        const val BLOCK = 256
        const val MAX_VOICES = 48
        private const val THRESHOLD = 0.1f
        private const val MAKEUP = 2.2f
    }
}

/** An RBJ biquad filter. */
internal class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    private fun set(type: Int, f: Float, q: Float, rate: Int) {
        val w = 2 * PI * f.coerceIn(10f, rate * 0.45f) / rate
        val cw = cos(w).toFloat(); val alpha = (sin(w) / (2 * q)).toFloat()
        val a0 = 1 + alpha
        when (type) {
            0 -> { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2 }
            1 -> { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2 }
            else -> { b0 = alpha; b1 = 0f; b2 = -alpha }
        }
        b0 /= a0; b1 /= a0; b2 /= a0; a1 = -2 * cw / a0; a2 = (1 - alpha) / a0
    }
    fun lowpass(f: Float, q: Float, rate: Int) = set(0, f, q, rate)
    fun highpass(f: Float, q: Float, rate: Int) = set(1, f, q, rate)
    fun bandpass(f: Float, q: Float, rate: Int) = set(2, f, q, rate)
    fun copy(o: Biquad) { b0 = o.b0; b1 = o.b1; b2 = o.b2; a1 = o.a1; a2 = o.a2 }
    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = if (abs(y) < 1e-20f) 0f else y
        return y1
    }
}

/** A Freeverb-style room: eight damped combs and four allpasses per side. */
internal class Room(rate: Int) {
    private val s = rate / 44100f
    private val combsL = COMBS.map { Comb((it * s).toInt()) }
    private val combsR = COMBS.map { Comb(((it + 23) * s).toInt()) }
    private val apsL = APS.map { Allpass((it * s).toInt()) }
    private val apsR = APS.map { Allpass(((it + 23) * s).toInt()) }
    var outL = 0f; private set
    var outR = 0f; private set

    fun process(l: Float, r: Float) {
        val inp = (l + r) * 0.015f
        var a = 0f; var b = 0f
        for (c in combsL) a += c.process(inp)
        for (c in combsR) b += c.process(inp)
        for (p in apsL) a = p.process(a)
        for (p in apsR) b = p.process(b)
        outL = a * 1.5f; outR = b * 1.5f
    }

    private class Comb(n: Int) {
        private val buf = FloatArray(maxOf(1, n)); private var i = 0; private var store = 0f
        fun process(x: Float): Float {
            val y = buf[i]
            store = y * 0.75f + store * 0.25f
            buf[i] = x + store * 0.84f
            i = (i + 1) % buf.size
            return y
        }
    }
    private class Allpass(n: Int) {
        private val buf = FloatArray(maxOf(1, n)); private var i = 0
        fun process(x: Float): Float {
            val b = buf[i]
            buf[i] = x + b * 0.5f
            i = (i + 1) % buf.size
            return b - x
        }
    }

    companion object {
        private val COMBS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val APS = intArrayOf(556, 441, 341, 225)
    }
}

/** One sounding note. Returns false from [render] once it has died away. */
internal abstract class Voice(protected val rate: Int, pan: Float, private var wait: Int) {
    private val gl: Float
    private val gr: Float
    protected var t = 0   // samples since the note began
    init {
        val x = ((pan.coerceIn(-1f, 1f) + 1) / 2) * (PI.toFloat() / 2)
        gl = cos(x); gr = sin(x)
    }

    fun render(l: FloatArray, r: FloatArray, n: Int): Boolean {
        var i = 0
        if (wait > 0) { val w = min(wait, n); wait -= w; i = w; if (wait > 0) return true }
        while (i < n) {
            val s = next()
            l[i] += s * gl; r[i] += s * gr
            t++; i++
        }
        return t < length
    }

    protected abstract val length: Int
    protected abstract fun next(): Float

    /** An envelope: a quick rise over [atk] s to [peak], then an exponential fall to silence over [dec] s. */
    protected class Env(val peak: Float, atk: Float, dec: Float, rate: Int) {
        private val a = maxOf(1, (atk * rate).toInt())
        private val k = exp(-9.21f / (dec * rate))
        private var v = peak
        val length = a + (dec * rate).toInt()
        fun at(t: Int): Float = if (t < a) peak * t / a else { val y = v; v *= k; y }
    }

    companion object {
        private val TABLE = FloatArray(4097) { sin(2 * PI * it / 4096).toFloat() }
        /** sin(2π·[p]) for a phase in cycles. */
        fun sine(p: Double): Float {
            val x = (p - kotlin.math.floor(p)) * 4096
            val i = x.toInt()
            val f = (x - i).toFloat()
            return TABLE[i] + (TABLE[i + 1] - TABLE[i]) * f
        }
        fun tri(p: Double): Float { val x = p - kotlin.math.floor(p); return (4 * abs(x - 0.5) - 1).toFloat() }
        private var seed = 0x2545F491
        fun noise(): Float { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5); return seed / 2147483648f }

        fun of(n: Note, rate: Int, wait: Int): Voice = when (n.inst) {
            Inst.GLASS -> Partials(n, rate, wait, listOf(P(1.0, 1f, 0.004f, 1f), P(2.76, 0.18f, 0.003f, 0.35f)))
            Inst.MARIMBA -> Partials(n, rate, wait, listOf(P(1.0, 1f, 0.003f, 1f), P(4.0, 0.35f, 0.002f, min(0.09f, n.decay * 0.3f) / n.decay)))
            Inst.KALIMBA -> Partials(n, rate, wait, listOf(P(1.0, 1f, 0.003f, 1f), P(5.4, 0.25f, 0.002f, 0.15f), P(2.0, 0.12f, 0.002f, 0.5f, tri = true)))
            Inst.FELT -> Felt(n, rate, wait)
            Inst.PLUCK -> Pluck(n, rate, wait)
            Inst.TICK -> Noise(n, rate, wait, hat = false)
            Inst.HAT -> Noise(n, rate, wait, hat = true)
            Inst.BELL -> Bell(n, rate, wait)
            Inst.KNOCK -> Knock(n, rate, wait)
        }
    }

    /** A partial: [ratio] × the note's frequency, at [gain] of its peak, with its own attack and a share of its ring. */
    class P(val ratio: Double, val gain: Float, val atk: Float, val decay: Float, val tri: Boolean = false)

    private class Partials(n: Note, rate: Int, wait: Int, ps: List<P>) : Voice(rate, n.pan, wait) {
        private val steps = DoubleArray(ps.size) { n.freq * ps[it].ratio / rate }
        private val phase = DoubleArray(ps.size)
        private val tri = BooleanArray(ps.size) { ps[it].tri }
        private val envs = ps.map { Env(n.gain * it.gain, it.atk, n.decay * it.decay, rate) }
        override val length = envs.maxOf { it.length }
        override fun next(): Float {
            var s = 0f
            for (j in steps.indices) {
                if (t < envs[j].length) s += (if (tri[j]) tri(phase[j]) else sine(phase[j])) * envs[j].at(t)
                phase[j] += steps[j]
            }
            return s
        }
    }

    private class Felt(n: Note, rate: Int, wait: Int) : Voice(rate, n.pan, wait) {
        private val step = n.freq.toDouble() / rate
        private var ph = 0.0
        private val a = 1 - exp(-2 * PI.toFloat() * n.freq * 2.5f / rate)
        private var y = 0f
        private val env = Env(n.gain * 1.2f, 0.018f, n.decay, rate)
        override val length = env.length
        override fun next(): Float { y += (tri(ph) - y) * a; ph += step; return y * env.at(t) }
    }

    private class Pluck(n: Note, rate: Int, wait: Int) : Voice(rate, n.pan, wait) {
        private val f = n.freq
        private val dt = f / rate
        private var ph = 0.0
        private val lp = Biquad()
        private val sweep = n.decay * 0.5f * rate
        private val env = Env(n.gain * 0.7f, 0.003f, n.decay, rate)
        override val length = env.length
        override fun next(): Float {
            if (t % 32 == 0) lp.lowpass(f * 7f * (1.1f / 7f).pow(min(1f, t / sweep)), 1.2f, rate)
            // a band-limited sawtooth (polyBLEP)
            var s = (2 * ph - 1).toFloat()
            val x = ph.toFloat(); val d = dt
            if (x < d) { val u = x / d; s -= u + u - u * u - 1 } else if (x > 1 - d) { val u = (x - 1) / d; s -= u * u + u + u + 1 }
            ph += dt; if (ph >= 1) ph -= 1
            return lp.process(s) * env.at(t)
        }
    }

    private class Noise(n: Note, rate: Int, wait: Int, hat: Boolean) : Voice(rate, n.pan, wait) {
        private val bp = Biquad().apply { if (hat) highpass(7000f, 0.707f, rate) else bandpass(min(n.freq * 4, 9000f), 9f, rate) }
        private val env = if (hat) Env(n.gain * 0.9f, 0.001f, (n.decay * 0.15f).coerceIn(0.025f, 0.09f), rate)
            else Env(n.gain * 3f, 0.001f, max(0.02f, n.decay), rate)
        override val length = env.length
        override fun next() = bp.process(noise()) * env.at(t)
    }

    private class Bell(n: Note, rate: Int, wait: Int) : Voice(rate, n.pan, wait) {
        private val f = n.freq.toDouble()
        private var cp = 0.0
        private var mp = 0.0
        private val fall = n.decay * 0.6 * rate
        private val env = Env(n.gain, 0.003f, n.decay, rate)
        override val length = env.length
        override fun next(): Float {
            val dev = f * 1.6 * (0.05 / 1.6).pow(min(1.0, t / fall))
            mp += f * 3.5 / rate
            cp += (f + dev * sine(mp)) / rate
            return sine(cp) * env.at(t)
        }
    }

    private class Knock(n: Note, rate: Int, wait: Int) : Voice(rate, n.pan, wait) {
        private val f = n.freq.toDouble()
        private var ph = 0.0
        private val sweep = 0.035 * rate
        private val env = Env(n.gain * 1.3f, 0.002f, n.decay, rate)
        override val length = env.length
        override fun next(): Float {
            ph += f * 2.2.pow(1 - min(1.0, t / sweep)) / rate
            return sine(ph) * env.at(t)
        }
    }
}
