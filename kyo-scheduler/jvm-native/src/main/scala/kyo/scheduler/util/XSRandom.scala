package kyo.scheduler.util

import java.util.Random

/** Fast pseudo-random number generator for scheduler operations.
  *
  * Uses a thread-hashed seed array and xorshift algorithm for quick random generation. Designed for scheduler operations like work stealing
  * and load balancing where speed is prioritized over perfect randomness.
  *
  * The implementation includes the thread index in the xorshift operations to better handle thread slot conflicts. While multiple threads
  * may still share the same seed slot, their random sequences will differ due to the thread-specific mixing in the xorshift steps. This
  * provides better statistical properties while maintaining the performance characteristics needed for scheduling operations.
  */
private[kyo] object XSRandom extends Random {

    // Size is core * 32 to reduce false sharing by spacing seeds across cache lines
    private val seeds = List.fill(Runtime.getRuntime().availableProcessors() * 32)(31L).toArray

    override def next(nbits: Int): Int = {
        val id  = Thread.currentThread().hashCode()
        val idx = (id & 31).toInt

        var x = seeds(idx)

        // Mix in thread hash to differentiate sequences even if
        // multiple threads land on the same slot
        x ^= id

        // Regular xorshift
        x ^= (x << 21)
        x ^= (x >>> 35)
        x ^= (x << 4)
        seeds(idx) = x

        x &= ((1L << nbits) - 1)
        x.asInstanceOf[Int]
    }
}
