package kyo.test.prop.internal

/** A pure, splittable pseudo-random seed implementing the SplitMix64 algorithm.
  *
  * Integrated shrinking needs deterministic, lazy shrink trees. `flatMap` expansion re-runs the bind function and re-samples the inner
  * generator while the lazy tree is traversed, so a mutable RNG (whose state would be consumed unpredictably during traversal) cannot be
  * used. A pure splittable seed solves this: `next` advances the stream deterministically, and `split` derives two independent streams so
  * an outer and an inner generator never share draws.
  *
  * `kyo.Random` exposes only the stateful `Sync` effect (no pure splittable PRNG), so SplitMix64 is implemented here as the right pure
  * primitive. The top-level seed is drawn at the boundary (from the configured run seed) and threaded purely through sampling.
  *
  * SplitMix64 is the standard small splittable generator (Steele, Lea & Flood 2014; also `java.util.SplittableRandom`'s core).
  */
opaque type Seed = Long

object Seed:

    private val Gamma: Long = 0x9e3779b97f4a7c15L

    /** Construct a seed from a raw Long. */
    def apply(value: Long): Seed = value

    private def mix64(z0: Long): Long =
        var z = z0
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
        z ^ (z >>> 31)
    end mix64

    private def mixGamma(z0: Long): Long =
        var z = z0
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L
        z = z ^ (z >>> 33)
        val g = z | 1L
        val n = java.lang.Long.bitCount(g ^ (g >>> 1))
        if n >= 24 then g else g ^ 0xaaaaaaaaaaaaaaaaL
    end mixGamma

    /** Produce a pseudo-random draw together with the advanced seed. */
    def next(s: Seed): (Long, Seed) =
        val next = s + Gamma
        (mix64(next), next)

    /** Split into two independent seed streams. The left stream continues this one's progression; the right is a fresh, statistically
      * independent stream derived from the current draw.
      */
    def split(s: Seed): (Seed, Seed) =
        val s1            = s + Gamma
        val s2            = s1 + Gamma
        val rightSeed     = mix64(s1)
        val rightGammaSrc = mixGamma(s2)
        // Right stream uses an independently-mixed value as its origin; left advances past both consumed steps.
        (s2, rightSeed ^ rightGammaSrc)
    end split

    /** Draw a non-negative Int strictly below `bound` (bound must be positive), with the advanced seed. */
    def nextInt(s: Seed, bound: Int): (Int, Seed) =
        val (raw, s1) = next(s)
        // Reduce the magnitude as a Long before narrowing, so the result is always in [0, bound).
        val pos = raw >>> 1
        ((pos % bound.toLong).toInt, s1)
    end nextInt

    /** Draw a Double in [0.0, 1.0) with the advanced seed. */
    def nextDouble(s: Seed): (Double, Seed) =
        val (raw, s1) = next(s)
        ((raw >>> 11).toDouble / (1L << 53).toDouble, s1)

    /** Draw a Boolean with the advanced seed. */
    def nextBoolean(s: Seed): (Boolean, Seed) =
        val (raw, s1) = next(s)
        ((raw & 1L) == 1L, s1)

end Seed
