package kyo.concurrent.scheduler

import java.util.Random

object XSRandom extends Random {
  private[this] var seed: Long = System.nanoTime()
  override def next(nbits: Int): Int = {
    var x = seed + Thread.currentThread().getId
    x ^= (x << 21)
    x ^= (x >>> 35)
    x ^= (x << 4)
    seed = x
    x &= ((1L << nbits) - 1)
    x.asInstanceOf[Int]
  }
}
