package kyo.scheduler.util

import java.util.Random

private[kyo] object XSRandom extends Random {
    private val seeds = List.fill(32)(31L).toArray
    override def next(nbits: Int): Int = {
        val idx = (Thread.currentThread().hashCode() & 31).toInt
        var x   = seeds(idx)
        x ^= (x << 21)
        x ^= (x >>> 35)
        x ^= (x << 4)
        seeds(idx) = x
        x &= ((1L << nbits) - 1)
        x.asInstanceOf[Int]
    }
}
