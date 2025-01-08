package kyo.scheduler.util

import java.util.Random

/** Fast pseudo-random number generator for scheduler operations.
  *
  * Uses a thread-hashed seed array and xorshift algorithm for quick random generation. Designed for scheduler operations like work stealing
  * and load balancing where speed is prioritized over perfect randomness or thread isolation.
  *
  * The design deliberately trades thread isolation for simplicity and performance. A shared seed array with 32 slots provides sufficient
  * randomness for scheduler decisions while minimizing memory overhead. Thread conflicts in the same array slot may impact randomness but
  * do not affect correctness of the scheduling algorithms.
  */
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
