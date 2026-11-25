package com.schwii.divvyrune.engine

import java.math.BigInteger

/** An exact fraction, always in lowest terms with a positive denominator. */
class Q private constructor(val n: BigInteger, val d: BigInteger) : Comparable<Q> {
    operator fun plus(o: Q) = of(n * o.d + o.n * d, d * o.d)
    operator fun minus(o: Q) = of(n * o.d - o.n * d, d * o.d)
    operator fun times(o: Q) = of(n * o.n, d * o.d)
    operator fun div(o: Q) = of(n * o.d, d * o.n)
    val isZero get() = n.signum() == 0
    fun toDouble() = n.toDouble() / d.toDouble()
    override fun compareTo(other: Q) = (n * other.d).compareTo(other.n * d)
    override fun equals(other: Any?) = other is Q && n == other.n && d == other.d
    override fun hashCode() = 31 * n.hashCode() + d.hashCode()
    override fun toString() = if (d == BigInteger.ONE) n.toString() else "$n/$d"

    companion object {
        fun of(n: BigInteger, d: BigInteger = BigInteger.ONE): Q {
            require(d.signum() != 0) { "zero denominator" }
            var nn = n
            var dd = d
            if (dd.signum() < 0) { nn = nn.negate(); dd = dd.negate() }
            val g = nn.gcd(dd).takeIf { it.signum() != 0 } ?: BigInteger.ONE
            return Q(nn / g, dd / g)
        }
        fun of(n: Long, d: Long = 1) = of(BigInteger.valueOf(n), BigInteger.valueOf(d))
        val ZERO = of(0)
        val ONE = of(1)
    }
}
