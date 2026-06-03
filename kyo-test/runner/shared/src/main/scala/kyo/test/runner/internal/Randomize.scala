package kyo.test.runner.internal

import kyo.Chunk
import scala.reflect.ClassTag

/** Deterministic shuffle using a seeded PRNG.
  *
  * The same `seed` applied to the same `items` always produces the same output order (Fisher-Yates / Knuth shuffle with
  * `java.util.Random`).
  */
object Randomize:

    /** Shuffle items deterministically using the given seed.
      *
      * @param items
      *   the input collection; returned unchanged if it has 0 or 1 elements
      * @param seed
      *   the PRNG seed; the same seed always produces the same permutation
      * @return
      *   a new Chunk with elements in the shuffled order
      */
    def shuffle[A: ClassTag](items: Chunk[A], seed: Long): Chunk[A] =
        if items.size <= 1 then return items
        val arr = items.toArray
        val rng = new java.util.Random(seed)
        var i   = arr.length - 1
        while i > 0 do
            val j   = rng.nextInt(i + 1)
            val tmp = arr(i)
            arr(i) = arr(j)
            arr(j) = tmp
            i -= 1
        end while
        Chunk.from(arr)
    end shuffle

end Randomize
