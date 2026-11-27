package com.schwii.divvyrune.sound

import com.schwii.divvyrune.engine.CycleFinder
import com.schwii.divvyrune.engine.Graph
import com.schwii.divvyrune.engine.Level
import com.schwii.divvyrune.engine.Sim
import com.schwii.divvyrune.engine.buildGraph
import com.schwii.divvyrune.engine.decodeGrid
import com.schwii.divvyrune.engine.levelNamed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

class SoundTest {
    private val ninths = ".. BT .. .. .. ..\n.. RT BL .. .. ..\n.. BR CT6 BR LR LR\n.. RT SB7 SL7 LB ..\nRL BL BT CB6 TL ..\n.. RT X TL .. ..\n.. .. IT .. .. .."
    private val seventeenth = ".. .. .. .. .. .. IB ..\n.. BR LR LR LR LB TB ..\n.. BT RB RL BL TB TB ..\n.. CT3 CL3 BL BT TB TB ..\nBR CT5 SB5 SL7 LT CR5 X LB\nRT RL SR7 CL5 RB SR7 TL TB\n.. RB ST6 RT X TL .. TB\n.. TB TR LR CR6 LR LR CR6\n.. TB .. .. .. .. .. .."

    /** A level's build, its sim, and a score, run a tick at a time. */
    private inner class Run(code: String, val level: Level, seed: Int = 1) {
        val graph: Graph = buildGraph(decodeGrid(code).tiles!!, level)
        val sim = Sim(graph, level)
        val cycles = CycleFinder(sim).also { it.observe() }
        val score = Score(Random(seed))
        val number = com.schwii.divvyrune.engine.LEVELS.indexOf(level) + 1
        fun tick(right: Boolean, voices: Int = Recipe.MAX_VOICES): List<Note> {
            sim.step(); cycles.observe()
            return score.tick(sim, graph, level, cycles.cycle, number, 17, { right }, wire = false, tickSeconds = 1 / 3f, maxVoices = voices)
        }
    }

    /** The semitone above C a frequency sits on. */
    private fun pitchClass(f: Float): Int = Math.floorMod((12 * log2(f / 130.8128)).roundToInt(), 12)

    @Test fun `a build makes notes from what its marbles do`() {
        val r = Run(ninths, levelNamed("Ninths"))
        val notes = (1..200).flatMap { r.tick(true) }
        assertTrue(notes.isNotEmpty())
        assertTrue("splits ring glass", notes.any { it.inst == Inst.GLASS })
        assertTrue("ways out play kalimba", notes.any { it.inst == Inst.KALIMBA })
        assertTrue("inputs tick", notes.any { it.inst == Inst.TICK })
        notes.forEach { assertTrue(it.gain > 0 && it.gain < 1 && it.freq > 20 && it.freq < 20000 && abs(it.pan) <= 1) }
    }

    @Test fun `every marble leaving is heard, from the first`() {
        for ((code, name) in listOf(ninths to "Ninths", seventeenth to "Seventeenth")) {
            val r = Run(code, levelNamed(name))
            repeat(300) {
                r.tick(false)
                val leaving = r.sim.ghosts.map { it.id }.toSet()
                val heard = r.score.flashes.filter { it.role == Role.VANISH && it.marble >= 0 }.map { it.marble }.toSet()
                assertEquals("$name tick ${r.sim.tick}", leaving, heard)
            }
        }
    }

    @Test fun `notes stay in the level's key and mode, and nothing pitched falls under the floor`() {
        val level = levelNamed("Ninths")
        val r = Run(ninths, level)
        val number = r.number
        val key = Math.floorMod((number - 1) * 7, 12)
        val mode = Recipe.MODES[(number - 1) * 5 / 17]
        val notes = (1..300).flatMap { r.tick(true) }
        notes.forEach { assertTrue("${pitchClass(it.freq)} in the mode on $key", Math.floorMod(pitchClass(it.freq) - key, 12) in mode) }
        notes.filter { it.inst != Inst.TICK && it.inst != Inst.HAT }.forEach { assertTrue("${it.freq} Hz", it.freq >= Recipe.FLOOR) }
    }

    @Test fun `each way out has its own chord, an unfinished one hangs on a sus, and right ones resolve on the loop's start`() {
        val r = Run(ninths, levelNamed("Ninths"))
        val labels = LinkedHashSet<String>()
        repeat(120) { r.tick(false); labels += r.score.chord!!.label }
        assertTrue(labels.toString(), labels.size >= 3 && labels.all { it.endsWith("sus") })
        assertTrue(!r.score.solved)
        val c = r.cycles.cycle!!
        var resolvedAt = -1
        var bloom = emptyList<Note>()
        repeat(c.length + 1) {
            val n = r.tick(true)
            if (r.score.solved && resolvedAt < 0) { resolvedAt = r.sim.tick; bloom = n.filter { it.inst == Inst.BELL && it.gain == 0.12f } }
        }
        assertEquals(0, (resolvedAt - c.start - 1) % c.length)
        assertEquals(4, bloom.size)
        assertTrue(r.score.chord!!.right && !r.score.chord!!.label.endsWith("sus"))
        assertTrue("the room opens", r.score.mix.reverb > 0.5f && r.score.mix.echo > 0.5f)
    }

    @Test fun `a splitter sounds once a round`() {
        val r = Run(ninths, levelNamed("Ninths"))
        repeat(200) { r.tick(true) }
        var rounds = 0; var heard = 0
        repeat(90) {
            r.tick(true)
            val tk = r.sim.tick
            r.sim.n.forEachIndexed { i, sn ->
                val it = sn.item ?: return@forEachIndexed
                val from = r.sim.n[it.pf]
                if (it.mt == tk && it.pf != i && from.kind == com.schwii.divvyrune.engine.Kind.SPLIT && from.succ.indexOf(i) == from.succ.size - 1) rounds++
            }
            r.sim.ghosts.forEach { g -> val from = r.sim.n[g.from]; if (g.item != null && from.kind == com.schwii.divvyrune.engine.Kind.SPLIT && from.succ.indexOf(g.to) == from.succ.size - 1) rounds++ }
            heard += r.score.flashes.count { it.role == Role.SPLIT && it.marble >= 0 }
        }
        assertTrue(rounds > 0)
        assertEquals(rounds, heard)
    }

    @Test fun `the rune comes from the build`() {
        val a = Run(seventeenth, levelNamed("Seventeenth")).apply { repeat(5) { tick(false) } }
        val b = Run(seventeenth, levelNamed("Seventeenth"), seed = 9).apply { repeat(5) { tick(false) } }
        val c = Run(ninths, levelNamed("Ninths")).apply { repeat(5) { tick(false) } }
        assertEquals(17, a.score.tides.first().prime)
        assertEquals(3, c.score.tides.first().prime)
        assertEquals(a.score.tides.map { it.key to it.period }, b.score.tides.map { it.key to it.period })
        assertNotEquals(a.score.tides.map { it.key }, c.score.tides.map { it.key })
        assertTrue(a.score.ensemble.isNotEmpty())
    }

    @Test fun `a held rune stays where it is, and stays held when the rune changes`() {
        val r = Run(ninths, levelNamed("Ninths"))
        repeat(30) { r.tick(false) }
        var saved = emptyMap<String, Double>()
        r.score.onHolds = { saved = it }
        r.score.toggle("filter")
        val at = r.score.tides.first { it.key == "filter" }.angle
        val swell = r.score.tides.first { it.key == "swell" }.angle
        repeat(50) { r.tick(false) }
        assertEquals(at, r.score.tides.first { it.key == "filter" }.angle, 0.0)
        assertNotEquals(swell, r.score.tides.first { it.key == "swell" }.angle)
        assertEquals(setOf("filter"), saved.keys)
        val again = Run(ninths, levelNamed("Ninths"))
        again.score.setHolds(saved)
        repeat(5) { again.tick(false) }
        val t = again.score.tides.first { it.key == "filter" }
        assertTrue(t.held)
        assertEquals(at, t.angle, 0.0)
        again.score.toggle("filter")
        repeat(5) { again.tick(false) }
        assertNotEquals(at, again.score.tides.first { it.key == "filter" }.angle)
    }

    @Test fun `busy speeds play fewer notes`() {
        val r = Run(seventeenth, levelNamed("Seventeenth"))
        repeat(300) {
            val n = r.tick(true, voices = 3)
            assertTrue(n.size <= 3 * 4 + 4 + 4)   // three sounds with their layers, the solved chord, the chord pad
        }
    }

    @Test fun `every instrument renders clean and dies away`() {
        val s = Synth(48000)
        val notes = Inst.entries.mapIndexed { i, inst -> Note(inst, 261.6f * 2f.pow(i / 12f), 0.3f, (i - 4) / 5f, 0.8f, i * 0.05f) }
        s.play(notes, Mix(master = 0.75f, reverb = 0.5f, echo = 0.4f, flange = 0.3f))
        val out = FloatArray(48000 * 2 * 3)
        s.render(out, 48000 * 3)
        assertTrue(out.all { it.isFinite() && abs(it) <= 1f })
        val first = out.copyOfRange(0, 48000)
        assertTrue("audible", sqrt(first.map { it * it }.average()) > 0.01)
        val later = FloatArray(48000 * 2 * 12)
        s.render(later, 48000 * 12)
        assertTrue("asleep after the tails", later.copyOfRange(later.size - 4800, later.size).all { it == 0f })
    }

    /** Plays a build through the synth as the game would. With DIVVYRUNE_WAV set, writes it there to listen to. */
    @Test fun `a build plays through the synth without clipping`() {
        val rate = 48000
        val r = Run(ninths, levelNamed("Ninths"))
        val synth = Synth(rate)
        val tick = rate / 3   // ½ speed: 3 ticks a second
        val seconds = System.getenv("DIVVYRUNE_WAV")?.let { 90 } ?: 20
        val pcm = FloatArray(rate * seconds * 2)
        var at = 0
        var k = 0
        while (at + tick <= rate * seconds) {
            // unfinished for the first half, then right
            val notes = r.tick(right = k++ * tick > rate * seconds / 2)
            synth.play(notes, r.score.mix)
            val buf = FloatArray(tick * 2)
            synth.render(buf, tick)
            buf.copyInto(pcm, at * 2)
            at += tick
        }
        assertTrue(pcm.all { it.isFinite() && abs(it) <= 1f })
        val loud = pcm.count { abs(it) > 0.95f }
        assertTrue("barely clips: $loud", loud < pcm.size / 1000)
        val rms = sqrt(pcm.map { it * it }.average())
        println("rms ${"%.3f".format(rms)} peak ${"%.3f".format(pcm.maxOf { abs(it) })}")
        assertTrue(rms > 0.01)
        System.getenv("DIVVYRUNE_WAV")?.let { writeWav(File(it), pcm, rate) }
    }

    private fun writeWav(f: File, pcm: FloatArray, rate: Int) {
        DataOutputStream(f.outputStream().buffered()).use { o ->
            fun i32(v: Int) { o.writeByte(v); o.writeByte(v shr 8); o.writeByte(v shr 16); o.writeByte(v shr 24) }
            fun i16(v: Int) { o.writeByte(v); o.writeByte(v shr 8) }
            o.writeBytes("RIFF"); i32(36 + pcm.size * 2); o.writeBytes("WAVEfmt "); i32(16); i16(1); i16(2); i32(rate); i32(rate * 4); i16(4); i16(16)
            o.writeBytes("data"); i32(pcm.size * 2)
            for (x in pcm) i16((x.coerceIn(-1f, 1f) * 32767).roundToInt())
        }
    }
}
