package com.schwii.divvyrune.wood

import android.graphics.Bitmap
import android.graphics.Color

/** Seeded random numbers, identical to the prototype's generator so grain lands the same way. */
fun rand(seed: Int): () -> Double {
    var s = seed
    return {
        s += 0x6D2B79F5
        var t = s
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        (t xor (t ushr 14)).toUInt().toDouble() / 4294967296.0
    }
}

/** Smooth value noise with period [n]; sampling at multiples of n / size makes a texture tile without a seam. */
private fun valueNoise(seed: Int, n: Int): (Double, Double) -> Double {
    val r = rand(seed)
    val g = DoubleArray(n * n) { r() }
    fun at(x: Int, y: Int) = g[((y % n + n) % n) * n + ((x % n + n) % n)]
    fun sm(t: Double) = t * t * (3 - 2 * t)
    return { x, y ->
        val xi = Math.floor(x).toInt()
        val yi = Math.floor(y).toInt()
        val xf = sm(x - xi); val yf = sm(y - yi)
        val a = at(xi, yi); val b = at(xi + 1, yi); val c = at(xi, yi + 1); val d = at(xi + 1, yi + 1)
        a + (b - a) * xf + (c - a) * yf + (a - b - c + d) * xf * yf
    }
}

/** A 384 × 384 tileable plank of wood: warped growth rings over fine grain. */
fun woodTexture(base: IntArray, dark: IntArray, seed: Int, rings: Int): Bitmap {
    val w = 384; val h = 384
    val n1 = valueNoise(seed, 8); val n2 = valueNoise(seed + 7, 64)
    val px = IntArray(w * h)
    for (j in 0 until h) for (i in 0 until w) {
        val warp = n1(i * 8.0 / w, j * 24.0 / h) * 3 + n1(i * 16.0 / w, j * 48.0 / h) * 0.6
        val ring = 0.5 + 0.5 * Math.sin((j * 2.0 * rings / h + warp) * Math.PI)
        val v = minOf(1.0, Math.pow(ring, 4.0) * 0.75 + n2(i * 192.0 / w, j * 64.0 / h) * 0.22)
        fun ch(k: Int) = (base[k] + (dark[k] - base[k]) * v).toInt()
        px[j * w + i] = Color.rgb(ch(0), ch(1), ch(2))
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

/** The three woods, generated once per process. */
object Woods {
    val table: Bitmap by lazy { woodTexture(intArrayOf(222, 216, 204), intArrayOf(210, 203, 190), 12, 6) }
    val maple: Bitmap by lazy { woodTexture(intArrayOf(240, 226, 198), intArrayOf(222, 200, 164), 4, 17) }
    val walnut: Bitmap by lazy { woodTexture(intArrayOf(132, 92, 64), intArrayOf(98, 66, 44), 6, 17) }
}

/** Colours shared by the board and the panels. */
object Palette {
    /** The colour for each look: wood, wireframe, runes. */
    private fun pick(wood: Int, wire: Int, rune: Int) = when (Look.style) { Style.WOOD -> wood; Style.WIRE -> wire; Style.RUNE -> rune }
    val page get() = pick(Color.rgb(0xeb, 0xe5, 0xda), Color.rgb(0x14, 0x16, 0x1a), Color.rgb(0x14, 0x16, 0x1f))
    val panel get() = pick(Color.rgb(0xf6, 0xf2, 0xea), Color.rgb(0x1c, 0x1f, 0x24), Color.rgb(0x1b, 0x1e, 0x29))
    val ink get() = pick(Color.rgb(0x3b, 0x2c, 0x20), Color.rgb(0xed, 0xef, 0xf2), Color.rgb(0xe4, 0xec, 0xf7))
    val dim get() = pick(Color.rgb(0x7d, 0x6a, 0x58), Color.rgb(0x9a, 0xa0, 0xa8), Color.rgb(0x8c, 0x96, 0xab))
    val bad get() = pick(Color.rgb(0xc0, 0x54, 0x4a), Color.rgb(0xff, 0x6b, 0x5e), Color.rgb(0xff, 0x6b, 0x5e))
    val beltPreview get() = pick(Color.rgb(0xb8, 0x93, 0x5f), Color.rgb(0xed, 0xef, 0xf2), Color.rgb(0xbf, 0xe0, 0xff))
    val stainSrc = Color.rgb(0xd4, 0xa2, 0x4f)
    val stainOut = Color.rgb(0x8f, 0xae, 0x86)
    val stainDump = Color.rgb(0xb8, 0xb0, 0xa3)
    val stainBad = Color.rgb(0xd9, 0x8e, 0x82)

    /** The prototype's rgba(r,g,b,a) as an Android colour int. */
    fun rgba(r: Int, g: Int, b: Int, a: Double) = Color.argb((a * 255).toInt().coerceIn(0, 255), r, g, b)
}

/** A stable per-cell seed, so each block keeps its own grain offset. */
fun hashCell(x: Int, y: Int): Int {
    var h = -2128831035
    for (c in "$x,$y") h = (h xor c.code) * 16777619
    return h
}
