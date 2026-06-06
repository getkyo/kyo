package kyo.test.prop

import kyo.Chunk
import kyo.Frame
import kyo.test.prop.internal.GenDerive
import kyo.test.prop.internal.Seed
import kyo.test.prop.internal.Tree

/** Generator for values of type A used in property-based tests.
  *
  * A `Gen[A]` samples a rose `Tree[A]` from a pure splittable `Seed` and a size hint. The tree carries the generated value together with
  * the full lazy tree of its shrink candidates, so the combinators (`map`, `flatMap`, `filter`) propagate shrinking automatically: the bug
  * where `map`/`flatMap` silently dropped shrinking is fixed structurally by the `Tree` combinators rather than per-combinator.
  *
  * Note: function-typed generators (e.g., Gen[Int => Int]) are not supported.
  *
  * @tparam A
  *   the type of values produced by this generator
  * @see
  *   [[kyo.test.prop.Shrink]] exposes the shrink algorithms used by built-in Gen instances
  * @see
  *   [[kyo.test.prop.PropertyTest]] the base class that uses Gen via forAll
  * @see
  *   [[kyo.test.prop.PropertyFailedException]] thrown when a forAll property fails after shrinking
  */
trait Gen[A]:
    self =>

    /** Sample a rose tree of a value and its shrink candidates.
      *
      * @param seed
      *   the pure splittable seed
      * @param size
      *   a hint about the complexity/magnitude of the generated value (higher = larger/more complex)
      * @return
      *   a `Tree[A]` whose root is the generated value and whose subtrees are the (lazy, deterministic) shrink candidates
      */
    def sample(seed: Seed, size: Int): Tree[A]

    /** Transform every generated value with `f`. Shrinking is preserved: the source tree's shrink structure is mapped through `f`. */
    def map[B](f: A => B): Gen[B] =
        new Gen[B]:
            def sample(seed: Seed, size: Int): Tree[B] = self.sample(seed, size).map(f)

    /** Chain this generator with a function that produces a new generator from each sampled value.
      *
      * The seed is split so the outer and inner generators draw from independent streams; this makes re-sampling the inner generator during
      * lazy shrink-tree expansion reproducible. Shrinking propagates through the rose-tree bind: first the source is simplified, then the
      * result of `f`.
      *
      * Non-minimality caveat: monadic `flatMap` shrinking is NOT guaranteed minimal. The inner generator is re-sampled per outer shrink,
      * and the dependency between the two components blocks component-wise minimization. When the components are INDEPENDENT, prefer
      * [[kyo.test.prop.Gen.zipWith]] or [[kyo.test.prop.Gen.zip]], whose applicative shrinking minimizes each component independently.
      * Use `flatMap` only when the inner generator genuinely depends on the outer value.
      */
    def flatMap[B](f: A => Gen[B]): Gen[B] =
        new Gen[B]:
            def sample(seed: Seed, size: Int): Tree[B] =
                val (s1, s2) = Seed.split(seed)
                self.sample(s1, size).flatMap(a => f(a).sample(s2, size))

    /** Retry sampling until the predicate holds, up to a default budget of 1000 attempts, then prune the tree's shrink candidates by the
      * predicate. Delegates to `filter(p, 1000)`.
      *
      * @throws GenFilterExhaustedException
      *   if the budget is exhausted without producing an accepted value
      */
    def filter(p: A => Boolean)(using frame: Frame): Gen[A] = filter(p, 1000)

    /** Retry sampling until the predicate holds, up to `budget` attempts, then prune the tree's shrink candidates by the predicate.
      *
      * Each attempt advances the seed via `Seed.next` so successive attempts draw independent values. On exhaustion, throws
      * `GenFilterExhaustedException` carrying the budget and the number of attempts made (which equals `budget` on full exhaustion). The
      * success path preserves the full shrink tree, pruned so only candidates satisfying `p` are offered.
      *
      * @param p
      *   the predicate that accepted values must satisfy
      * @param budget
      *   the maximum number of sampling attempts before throwing
      * @throws GenFilterExhaustedException
      *   if `budget` attempts are made without producing an accepted value; the exception carries `.budget` and `.attempts` for
      *   programmatic inspection
      */
    def filter(p: A => Boolean, budget: Int)(using frame: Frame): Gen[A] =
        require(budget > 0, "Gen.filter budget must be positive")
        new Gen[A]:
            def sample(seed: Seed, size: Int): Tree[A] =
                var current  = seed
                var attempts = 0
                var tree     = self.sample(current, size)
                attempts += 1
                while !p(tree.value) && attempts < budget do
                    val (_, nextSeed) = Seed.next(current)
                    current = nextSeed
                    tree = self.sample(current, size)
                    attempts += 1
                end while
                if !p(tree.value) then
                    throw new GenFilterExhaustedException(budget, attempts)
                Tree(tree.value, () => tree.filter(p))
            end sample
        end new
    end filter

    /** Draw `count` root values deterministically from `seed` at `size`, returning them as a `Chunk[A]`.
      *
      * A pure introspection helper: lets callers inspect a generator's distribution (e.g. verify edge bias, debug a custom generator)
      * without running a property test. Each draw uses an independently split seed (mirroring the `executeForAll` seed topology) so
      * successive draws are independent. Pure and reproducible: the same `(seed, size, count)` triple always produces the same `Chunk`.
      *
      * @param seed
      *   the starting seed (a plain `Long`; the internal `Seed` type never appears in this signature)
      * @param size
      *   the size hint passed to each draw; negative values are clamped to 0 by the generators
      * @param count
      *   the number of root values to draw; `count <= 0` returns `Chunk.empty`
      * @return
      *   a `Chunk[A]` of exactly `count` root values (or `Chunk.empty` when `count <= 0`)
      */
    def samples(seed: Long, size: Int, count: Int): Chunk[A] =
        if count <= 0 then Chunk.empty[A]
        else
            var current = Seed(seed)
            var i       = 0
            val acc     = new scala.collection.mutable.ArrayBuffer[A](count)
            while i < count do
                val (drawSeed, nextCurrent) = Seed.split(current)
                acc += self.sample(drawSeed, size).value
                current = nextCurrent
                i += 1
            end while
            Chunk.from(acc)
        end if
    end samples

    /** Draw `count` samples and tally them into labelled buckets using `label`, returning a `Map[String, Int]` from label to count.
      *
      * A pure distribution-introspection tool: lets callers verify that a generator produces values in the expected proportions without
      * running a property test. The counts in the returned map sum to `count`. Pure and reproducible from `(seed, size, count)`.
      *
      * @param seed
      *   the starting seed (a plain `Long`)
      * @param size
      *   the size hint for each draw; negative values are clamped by the generators
      * @param count
      *   the total number of samples to draw and tally; `count <= 0` returns an empty map
      * @param label
      *   assigns a bucket name to each sampled value
      * @return
      *   a `Map[String, Int]` where each key is a label and its value is the number of samples assigned that label; counts sum to `count`
      */
    def classify(seed: Long, size: Int, count: Int)(label: A => String): Map[String, Int] =
        samples(seed, size, count).foldLeft(Map.empty[String, Int]) { (m, v) =>
            val key = label(v)
            m.updated(key, m.getOrElse(key, 0) + 1)
        }

end Gen

object Gen:

    /** Always produces the same value; shrinks to nothing. */
    def const[A](a: A): Gen[A] = new Gen[A]:
        def sample(seed: Seed, size: Int): Tree[A] = Tree.leaf(a)

    /** Generates integers uniform in [-size, size] (clamped to [-clampedSize, clampedSize], clampedSize = max(0, size)) with occasional
      * boundary-edge injections, including the type boundaries Int.MinValue and Int.MaxValue (which fall OUTSIDE [-size, size]). Shrinks
      * toward zero. At size 0 only the in-band trivial value 0 is producible (no out-of-band boundary injected).
      *
      * The producible set is [-size, size] UNION {Int.MinValue, Int.MaxValue} at size > 0. Callers that assume a strict [-size, size] bound
      * must accept the type boundaries that edge bias injects.
      */
    def int: Gen[Int] = new Gen[Int]:
        def sample(seed: Seed, size: Int): Tree[Int] =
            val clampedSize = math.max(0, size)
            val edges =
                if clampedSize == 0 then Chunk(0)
                else Chunk(0, 1, -1, clampedSize, -clampedSize, Int.MinValue, Int.MaxValue)
            edgeBiased(seed, clampedSize, edges)(shrinkInt) { drawSeed =>
                val (v, _) = Seed.nextInt(drawSeed, 2 * clampedSize + 1)
                v - clampedSize
            }
        end sample

    /** Generates longs uniform in [-size, size] (clamped to [-clampedSize, clampedSize], clampedSize = max(0, size)) with occasional
      * boundary-edge injections, including the type boundaries Long.MinValue and Long.MaxValue (which fall OUTSIDE [-size, size]). Shrinks
      * toward zero. At size 0 only the in-band trivial value 0L is producible.
      *
      * Same as int: the producible set is [-size, size] UNION {Long.MinValue, Long.MaxValue} at size > 0.
      */
    def long: Gen[Long] = new Gen[Long]:
        def sample(seed: Seed, size: Int): Tree[Long] =
            val clampedSize = math.max(0, size)
            val edges =
                if clampedSize == 0 then Chunk(0L)
                else Chunk(0L, 1L, -1L, clampedSize.toLong, -clampedSize.toLong, Long.MinValue, Long.MaxValue)
            edgeBiased(seed, clampedSize, edges)(shrinkLong) { drawSeed =>
                val range    = 2L * clampedSize + 1L
                val (raw, _) = Seed.next(drawSeed)
                val pos      = if raw < 0 then -(raw + 1) else raw // abs without overflow risk
                (pos % range) - clampedSize
            }
        end sample

    /** Generates doubles whose non-edge body is magnitude-bounded by size, with occasional special-value edge injections:
      * {0.0, -0.0, 1.0, -1.0, NaN, +Infinity, -Infinity, Double.MinValue, Double.MaxValue}. NaN and the infinities ARE producible samples
      * (deliberate edge coverage), so a property over Gen.double that assumes finiteness must guard it. Shrinks toward 0.0 and integral
      * values (see [[kyo.test.prop.Shrink.double]]). At size 0 the body produces a small fractional double (no longer the forced 0.0
      * collapse) and only the in-band trivial edge {0.0} is injected.
      */
    def double: Gen[Double] = new Gen[Double]:
        def sample(seed: Seed, size: Int): Tree[Double] =
            val clampedSize = math.max(0, size)
            val edges =
                if clampedSize == 0 then Chunk(0.0)
                else
                    Chunk(
                        0.0,
                        -0.0,
                        1.0,
                        -1.0,
                        Double.NaN,
                        Double.PositiveInfinity,
                        Double.NegativeInfinity,
                        Double.MinValue,
                        Double.MaxValue
                    )
            edgeBiased(seed, clampedSize, edges)(shrinkDouble) { drawSeed =>
                val (d, _) = Seed.nextDouble(drawSeed) // d in [0.0, 1.0)
                if clampedSize == 0 then d // size-0 fix: a small fractional double, not forced 0.0
                else d * 2.0 * clampedSize - clampedSize
            }
        end sample

    /** Generates random alphanumeric strings of length in [0, clampedSize] where clampedSize = max(0, size), with occasional edge bias
      * toward the empty string and the maximum-length string (both in-band: length stays within [0, clampedSize]); shrinks toward empty.
      */
    def string: Gen[String] = new Gen[String]:
        private val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private def draw(seed: Seed, len: Int): String =
            val sb      = new StringBuilder(len)
            var current = seed
            var i       = 0
            while i < len do
                val (idx, next) = Seed.nextInt(current, chars.length)
                sb.append(chars.charAt(idx))
                current = next
                i += 1
            end while
            sb.toString
        end draw
        def sample(seed: Seed, size: Int): Tree[String] =
            val clampedSize = math.max(0, size)
            // In-band edges: empty string, and a max-length string drawn deterministically from the seed.
            val maxLenStr = draw(seed, clampedSize)
            val edges     = if clampedSize == 0 then Chunk("") else Chunk("", maxLenStr)
            edgeBiased(seed, clampedSize, edges)(shrinkString) { drawSeed =>
                val (len, s1) = Seed.nextInt(drawSeed, clampedSize + 1)
                draw(s1, len)
            }
        end sample

    /** Generates booleans; shrinks true toward false. */
    def boolean: Gen[Boolean] = new Gen[Boolean]:
        def sample(seed: Seed, size: Int): Tree[Boolean] =
            val (b, _) = Seed.nextBoolean(seed)
            if b then Tree(true, () => LazyList(Tree.leaf(false))) else Tree.leaf(false)

    // Given instances for the primitive built-ins, so `Gen.derive[A]` can resolve a field of these types:
    // the derivation macro summons `Gen[FieldType]` for each field, and an ordinary case class normally has
    // primitive fields. These mirror the `int`/`long`/`double`/`string`/`boolean` generators above.
    given Gen[Int]     = int
    given Gen[Long]    = long
    given Gen[Double]  = double
    given Gen[String]  = string
    given Gen[Boolean] = boolean

    /** Generates chunks of varying length in [0, clampedSize] where clampedSize = max(0, size), with elements from g, biased occasionally
      * toward the empty and singleton collections (both in-band: length stays within [0, clampedSize]).
      */
    def list[A](g: Gen[A]): Gen[Chunk[A]] = new Gen[Chunk[A]]:
        def sample(seed: Seed, size: Int): Tree[Chunk[A]] =
            val clampedSize = math.max(0, size)
            val (n, s1)     = edgeBiasedLength(seed, clampedSize)
            var current     = s1
            val elems       = Array.newBuilder[Tree[A]]
            var i           = 0
            while i < n do
                val (es, next) = Seed.split(current)
                elems += g.sample(es, clampedSize)
                current = next
                i += 1
            end while
            listTree(Chunk.from(elems.result()))
        end sample

    /** Generates chunks of exactly n elements from g; shrinks element-wise only (length is fixed). */
    def listOfN[A](n: Int, g: Gen[A]): Gen[Chunk[A]] = new Gen[Chunk[A]]:
        def sample(seed: Seed, size: Int): Tree[Chunk[A]] =
            val clampedSize = math.max(0, size)
            var current     = seed
            val elems       = Array.newBuilder[Tree[A]]
            var i           = 0
            while i < n do
                val (es, next) = Seed.split(current)
                elems += g.sample(es, clampedSize)
                current = next
                i += 1
            end while
            fixedLengthTree(Chunk.from(elems.result()))
        end sample

    /** Generates maps by sampling key-value pairs; map size is in [0, clampedSize] where clampedSize = max(0, size), biased occasionally
      * toward the empty and singleton maps (both in-band: map size stays within [0, clampedSize]).
      */
    def map[K, V](kg: Gen[K], vg: Gen[V]): Gen[Map[K, V]] = new Gen[Map[K, V]]:
        def sample(seed: Seed, size: Int): Tree[Map[K, V]] =
            val clampedSize = math.max(0, size)
            val (n, s1)     = edgeBiasedLength(seed, clampedSize)
            var current     = s1
            val pairs       = Array.newBuilder[(K, Tree[V])]
            var i           = 0
            while i < n do
                val (ks, afterK) = Seed.split(current)
                val (vs, next)   = Seed.split(afterK)
                pairs += ((kg.sample(ks, clampedSize).value, vg.sample(vs, clampedSize)))
                current = next
                i += 1
            end while
            mapTree(Chunk.from(pairs.result()))
        end sample

    // ── P1 edge-case bias (shared, pure, reproducible) ───────────────────

    /** Inject an edge value with a small, size-decaying probability, otherwise return the size-driven uniform body.
      *
      * Splits the seed once into a decision stream and a draw stream. With probability approximately 1-in-(8 + clampedSize) (decaying as
      * size grows, never reaching 1.0), an edge value is chosen from `edges` (indexed by a draw off the decision stream) and unfolded via
      * the SAME per-type `shrink` function the body would use, so the injected value shrinks toward the trivial value exactly as a non-edge
      * sample. Otherwise the size-driven uniform `body(drawSeed)` is sampled. Pure and deterministic: a fixed (seed, size) always yields
      * the same tree (no mutable shared RNG). At size 0 callers pass the in-band trivial edge set only, so the result is equivalent to the
      * non-edge body.
      *
      * @param seed
      *   the seed to split for the decision and draw streams
      * @param clampedSize
      *   the clamped size (>= 0); governs the injection probability decay
      * @param edges
      *   the edge values to inject; must be non-empty
      * @param shrink
      *   the per-type shrink step the injected edge unfolds with (identical to the body's shrink machinery)
      * @param body
      *   the size-driven uniform sampler producing the non-edge root value from the draw seed
      */
    private[prop] def edgeBiased[A](seed: Seed, clampedSize: Int, edges: Chunk[A])(shrink: A => Iterable[A])(
        body: Seed => A
    ): Tree[A] =
        val (decisionSeed, drawSeed) = Seed.split(seed)
        // Decaying threshold: base 1-in-(8+clampedSize), shrinking as size grows; bounded so it never reaches 1.0.
        val scale      = 8 + clampedSize
        val (d, dNext) = Seed.nextInt(decisionSeed, scale)
        val inject     = edges.nonEmpty && d == 0
        if inject then
            val (ei, _) = Seed.nextInt(dNext, edges.size)
            Tree.unfold(edges(ei))(shrink)
        else
            Tree.unfold(body(drawSeed))(shrink)
        end if
    end edgeBiased

    /** Choose a collection length in [0, clampedSize], biased occasionally toward 0 (empty) and 1 (singleton); both in-band. Returns the
      * chosen length and the advanced seed so the element fill continues deterministically. At size 0 only length 0 is producible.
      */
    private[prop] def edgeBiasedLength(seed: Seed, clampedSize: Int): (Int, Seed) =
        if clampedSize == 0 then Seed.nextInt(seed, clampedSize + 1)
        else
            val (decisionSeed, drawSeed) = Seed.split(seed)
            val scale                    = 8 + clampedSize
            val (d, dNext)               = Seed.nextInt(decisionSeed, scale)
            if d == 0 then
                // inject empty or singleton (singleton only when clampedSize >= 1, always true in this branch)
                val (which, _) = Seed.nextInt(dNext, 2)
                (which, drawSeed) // 0 -> empty, 1 -> singleton; element fill uses drawSeed
            else Seed.nextInt(drawSeed, clampedSize + 1)
            end if
        end if
    end edgeBiasedLength

    /** Uniformly samples one of the provided choices.
      *
      * Shrinks toward earlier (simpler) choices: a value at index k offers indices 0, 1, ..., k-1 as shrink candidates (in ascending
      * order, simplest first), so the first choice (index 0) is treated as the base/simplest case and is tried first by the greedy shrink
      * walk. The earliest choice has no shrinks. Each earlier-choice candidate is a leaf (no further shrink subtree) because the choices
      * are raw values, not generators.
      */
    def oneOf[A](choices: A*): Gen[A] =
        require(choices.nonEmpty, "Gen.oneOf requires at least one choice")
        new Gen[A]:
            def sample(seed: Seed, size: Int): Tree[A] =
                val (idx, _) = Seed.nextInt(seed, choices.size)
                Tree(choices(idx), () => LazyList.from(0 until idx).map(j => Tree.leaf(choices(j))))
        end new
    end oneOf

    /** Weighted sampling: each entry is (weight, generator). The chosen sub-generator's tree is returned so its shrinks propagate.
      *
      * Shrinks toward earlier (lower-index) entries: a value chosen from entry k prepends entries 0, 1, ..., k-1 as additional shrink
      * candidates (in ascending index order, simplest first) before the chosen sub-generator's own shrinks. This means the first entry
      * (index 0) is the base/simplest case and is tried first by the greedy shrink walk. When the chosen index is already 0 (including
      * single-entry frequency), no earlier entries are prepended and only the sub-generator's own shrinks are offered (the behavior is
      * unchanged from before this improvement for that case).
      */
    def frequency[A](choices: (Int, Gen[A])*): Gen[A] =
        require(choices.nonEmpty, "Gen.frequency requires at least one choice")
        require(choices.forall(_._1 > 0), "Gen.frequency requires all weights to be positive")
        val totalWeight = choices.map(_._1).sum
        // prefix-sum table for binary search
        val prefixSums = choices.scanLeft(0)((acc, c) => acc + c._1).tail.toArray
        val gens       = choices.map(_._2).toArray
        new Gen[A]:
            def sample(seed: Seed, size: Int): Tree[A] =
                val (chosenSeed, crossSeed) = Seed.split(seed)
                val (r, s1)                 = Seed.nextInt(chosenSeed, totalWeight)
                var idx                     = 0
                while idx < prefixSums.length - 1 && r >= prefixSums(idx) do idx += 1
                val chosen = gens(idx).sample(s1, size)
                if idx == 0 then chosen
                else
                    // Build lazy earlier-entry trees from independent crossSeed splits (one per earlier entry, deterministic).
                    var crossCurrent = crossSeed
                    val earlier      = new Array[() => Tree[A]](idx)
                    var j            = 0
                    while j < idx do
                        val (jSeed, nextCross) = Seed.split(crossCurrent)
                        val jIdx               = j // capture for the closure
                        earlier(jIdx) = () => gens(jIdx).sample(jSeed, size)
                        crossCurrent = nextCross
                        j += 1
                    end while
                    Tree(chosen.value, () => LazyList.from(earlier.indices).map(j => earlier(j)()) #::: chosen.shrinks())
                end if
            end sample
        end new
    end frequency

    /** Derive a generator for A using scala.deriving.Mirror.
      *
      * Supports case classes (Product mirrors) and sealed traits (Sum mirrors). For each field in a case class, summons Gen[FieldType]
      * implicitly and zips the field trees into a product tree. For sealed traits, picks a subtype uniformly at random; the chosen
      * subtype's own shrinks propagate, and earlier (lower-index) subtypes are prepended as additional shrink candidates so the shrink
      * walk tends toward the simplest (index-0) subtype.
      */
    inline def derive[A]: Gen[A] = ${ GenDerive.deriveImpl[A] }

    // ── Applicative composition (public) ─────────────────────────────────

    /** Applicatively combine two generators with `f`.
      *
      * Samples `ga` and `gb` from independently split seeds and combines the two root values with `f`. Shrinking is applicative: the two
      * component shrink trees are interleaved via `Tree.zipWith`, so each component minimizes independently (one component shrinks while the
      * other is held fixed). This is the canonical applicative primitive; [[zip]] is `zipWith(ga, gb)((_, _))`.
      *
      * Prefer `zipWith`/`zip` over [[Gen.flatMap]] when the two components are INDEPENDENT: the applicative path reaches the component-wise
      * minimal counterexample, which the monadic `flatMap` path cannot guarantee.
      *
      * @param ga
      *   the first generator
      * @param gb
      *   the second generator
      * @param f
      *   the combining function applied to the two generated values
      * @tparam A
      *   type produced by `ga`
      * @tparam B
      *   type produced by `gb`
      * @tparam C
      *   the combined result type
      */
    def zipWith[A, B, C](ga: Gen[A], gb: Gen[B])(f: (A, B) => C): Gen[C] = new Gen[C]:
        def sample(seed: Seed, size: Int): Tree[C] =
            val (s1, s2) = Seed.split(seed)
            ga.sample(s1, size).zipWith(gb.sample(s2, size))(f)

    /** Applicatively combine two generators into a tuple.
      *
      * Shrinking minimizes both components independently (see [[zipWith]]). Promoted to public so generators can be composed directly;
      * backs `forAll` arity 2.
      *
      * Both components shrink independently via the applicative interleave in [[zipWith]]: a failing `(a, b)` shrinks `a` toward its
      * minimal while holding `b` fixed, then shrinks `b` toward its minimal while holding `a` fixed. This reaches the component-wise
      * minimum, which the monadic [[Gen.flatMap]] path cannot guarantee.
      *
      * @param ga
      *   the first generator
      * @param gb
      *   the second generator
      * @tparam A
      *   type produced by `ga`
      * @tparam B
      *   type produced by `gb`
      */
    def zip[A, B](ga: Gen[A], gb: Gen[B]): Gen[(A, B)] =
        zipWith(ga, gb)((a, b) => (a, b))

    /** Applicatively combine three generators into a tuple.
      *
      * Shrinking minimizes all three components independently; backs `forAll` arity 3. Uses a nested `zipWith` fold to thread the three
      * component trees: the first two components are combined, then the intermediate pair is combined with the third. The seed-split
      * topology is preserved from the prior monadic bodies so the root values are byte-identical for any given seed (INV-004).
      *
      * @param ga
      *   the first generator
      * @param gb
      *   the second generator
      * @param gc
      *   the third generator
      * @tparam A
      *   type produced by `ga`
      * @tparam B
      *   type produced by `gb`
      * @tparam C
      *   type produced by `gc`
      */
    def zip3[A, B, C](ga: Gen[A], gb: Gen[B], gc: Gen[C]): Gen[(A, B, C)] = new Gen[(A, B, C)]:
        def sample(seed: Seed, size: Int): Tree[(A, B, C)] =
            val (s1, rest) = Seed.split(seed)
            val (s2, s3)   = Seed.split(rest)
            ga.sample(s1, size)
                .zipWith(gb.sample(s2, size))((a, b) => (a, b))
                .zipWith(gc.sample(s3, size))((ab, c) => (ab._1, ab._2, c))
        end sample

    /** Applicatively combine four generators into a tuple.
      *
      * Shrinking minimizes all four components independently; backs `forAll` arity 4. Uses a nested `zipWith` fold to thread the four
      * component trees. The seed-split topology is preserved from the prior monadic bodies so the root values are byte-identical for any
      * given seed (INV-004).
      *
      * @param ga
      *   the first generator
      * @param gb
      *   the second generator
      * @param gc
      *   the third generator
      * @param gd
      *   the fourth generator
      * @tparam A
      *   type produced by `ga`
      * @tparam B
      *   type produced by `gb`
      * @tparam C
      *   type produced by `gc`
      * @tparam D
      *   type produced by `gd`
      */
    def zip4[A, B, C, D](ga: Gen[A], gb: Gen[B], gc: Gen[C], gd: Gen[D]): Gen[(A, B, C, D)] = new Gen[(A, B, C, D)]:
        def sample(seed: Seed, size: Int): Tree[(A, B, C, D)] =
            val (s1, r1) = Seed.split(seed)
            val (s2, r2) = Seed.split(r1)
            val (s3, s4) = Seed.split(r2)
            ga.sample(s1, size)
                .zipWith(gb.sample(s2, size))((a, b) => (a, b))
                .zipWith(gc.sample(s3, size))((ab, c) => (ab._1, ab._2, c))
                .zipWith(gd.sample(s4, size))((abc, d) => (abc._1, abc._2, abc._3, d))
        end sample

    // ── Tree builders for collections ────────────────────────────────────

    /** Build a variable-length list tree: each node's value is the chunk of element-values; shrinks drop one element at a time and shrink
      * each element through its own subtree. Reuses the `shrinkList` two-phase strategy.
      */
    private[prop] def listTree[A](elems: Chunk[Tree[A]]): Tree[Chunk[A]] =
        Tree(
            elems.map(_.value),
            () => LazyList.from(listShrinkSteps(elems, allowDrop = true)).map(listTree)
        )

    /** Build a fixed-length list tree: element-wise shrinking only; the length is fixed. */
    private[prop] def fixedLengthTree[A](elems: Chunk[Tree[A]]): Tree[Chunk[A]] =
        Tree(
            elems.map(_.value),
            () => LazyList.from(listShrinkSteps(elems, allowDrop = false)).map(fixedLengthTree)
        )

    /** One shrink step over a chunk of element trees.
      *
      *   - drop phase (when allowed): drop one element at a time, from the end first.
      *   - element phase: replace one element with one of its immediate shrink subtrees.
      */
    private def listShrinkSteps[A](elems: Chunk[Tree[A]], allowDrop: Boolean): Iterator[Chunk[Tree[A]]] =
        val dropPhase =
            if allowDrop then
                elems.indices.reverse.iterator.map(i => elems.take(i) ++ elems.drop(i + 1))
            else Iterator.empty
        val elemPhase = elems.indices.iterator.flatMap { i =>
            elems(i).shrinks().iterator.map(child => elems.take(i) ++ Chunk(child) ++ elems.drop(i + 1))
        }
        dropPhase ++ elemPhase
    end listShrinkSteps

    /** Build a map tree from key/value-tree pairs. Shrinks drop one entry at a time then shrink each entry's value through its subtree. */
    private[prop] def mapTree[K, V](pairs: Chunk[(K, Tree[V])]): Tree[Map[K, V]] =
        Tree(
            Map.from(pairs.iterator.map { case (k, vt) => (k, vt.value) }),
            () => LazyList.from(mapShrinkSteps(pairs)).map(mapTree)
        )

    private def mapShrinkSteps[K, V](pairs: Chunk[(K, Tree[V])]): Iterator[Chunk[(K, Tree[V])]] =
        val dropPhase = pairs.indices.reverse.iterator.map(i => pairs.take(i) ++ pairs.drop(i + 1))
        val valuePhase = pairs.indices.iterator.flatMap { i =>
            val (k, vt) = pairs(i)
            vt.shrinks().iterator.map(child => pairs.take(i) ++ Chunk((k, child)) ++ pairs.drop(i + 1))
        }
        dropPhase ++ valuePhase
    end mapShrinkSteps

    // ── Shrink algorithms ────────────────────────────────────────────────

    private[prop] def shrinkInt(v: Int): Chunk[Int] =
        if v == 0 then Chunk.empty
        else if v > 0 then
            Chunk.from(Iterator.iterate(v)(x => x / 2).drop(1).takeWhile(_ > 0) ++ Iterator.single(0))
        else
            // Negative: try positive mirror first (skipped for Int.MinValue: -Int.MinValue overflows back to Int.MinValue),
            // then halve toward 0.
            val mirror =
                if v == Int.MinValue then Iterator.empty
                else Iterator.single(-v)
            Chunk.from(
                mirror ++
                    Iterator.iterate(v)(x => x / 2).drop(1).takeWhile(_ < 0) ++ Iterator.single(0)
            )

    private[prop] def shrinkLong(v: Long): Chunk[Long] =
        if v == 0L then Chunk.empty
        else if v > 0L then
            Chunk.from(Iterator.iterate(v)(x => x / 2L).drop(1).takeWhile(_ > 0L) ++ Iterator.single(0L))
        else
            // Negative: try positive mirror first (skipped for Long.MinValue: -Long.MinValue overflows back to Long.MinValue),
            // then halve toward 0.
            val mirror =
                if v == Long.MinValue then Iterator.empty[Long]
                else Iterator.single(-v)
            Chunk.from(
                mirror ++
                    Iterator.iterate(v)(x => x / 2L).drop(1).takeWhile(_ < 0L) ++ Iterator.single(0L)
            )

    private[prop] def shrinkDouble(v: Double): Chunk[Double] =
        // halvingSequence(x): x/2, x/4, ... stopping before 0.0 (0.0 is appended explicitly by callers).
        def halvingSequence(x: Double): Iterator[Double] =
            Iterator.iterate(x)(_ / 2.0).drop(1).takeWhile(_ > 0.0)
        val candidates: Chunk[Double] =
            if v == 0.0 then Chunk.empty // covers +0.0 and -0.0 (0.0 == -0.0)
            else if v.isNaN then Chunk(0.0)
            else if v.isInfinite && v > 0 then Chunk.from(Iterator(0.0, Double.MaxValue) ++ halvingSequence(Double.MaxValue))
            else if v.isInfinite && v < 0 then Chunk.from(Iterator(0.0, -Double.MaxValue) ++ halvingSequence(Double.MaxValue).map(_ * -1.0))
            else
                val abs              = math.abs(v)
                val sign             = if v < 0 then -1.0 else 1.0
                val integralNeighbor = v.toLong.toDouble // truncation toward 0: 2.7 -> 2.0, -3.1 -> -3.0
                val mirror           = if v < 0 then Iterator.single(-v) else Iterator.empty[Double]
                val integral =
                    if integralNeighbor != v && integralNeighbor != 0.0 then Iterator.single(integralNeighbor) else Iterator.empty[Double]
                Chunk.from(mirror ++ integral ++ halvingSequence(abs).map(_ * sign) ++ Iterator.single(0.0))
        // Finiteness guard: never emit a non-finite candidate (halving/truncation of a finite value stays finite; this is a safety net).
        candidates.filter(java.lang.Double.isFinite)
    end shrinkDouble

    private[prop] def shrinkString(v: String): Chunk[String] =
        if v.isEmpty then Chunk.empty
        else
            Chunk.from(v.indices.reverse.iterator.map(i => v.patch(i, "", 1)) ++ Iterator.single(""))

    /** Two-phase chunk shrink over plain values: drop one element at a time (end first), then shrink each element via `elemShrink`.
      *
      * Retained for the `Shrink.list` public API; the rose-tree path uses `listShrinkSteps` over element trees directly.
      */
    private[prop] def shrinkList[A](chunk: Chunk[A], elemShrink: A => Chunk[A]): Chunk[Chunk[A]] =
        val dropPhase = chunk.indices.reverse.iterator.map { i =>
            chunk.take(i) ++ chunk.drop(i + 1)
        }
        val shrinkPhase = chunk.indices.iterator.flatMap { i =>
            elemShrink(chunk(i)).iterator.map(e => chunk.take(i) ++ Chunk(e) ++ chunk.drop(i + 1))
        }
        Chunk.from(dropPhase ++ shrinkPhase)
    end shrinkList

end Gen

/** Thrown when `Gen.filter` exhausts its retry budget without producing an accepted value.
  *
  * Carries the budget and the number of attempts made so callers can react programmatically without parsing the message string. On full
  * exhaustion `attempts == budget`: every slot in the budget was consumed. The message names the budget, the attempt count, and suggests
  * constructing values via `map` rather than filtering a narrow predicate.
  *
  * @param budget
  *   the maximum number of sampling attempts that were allowed
  * @param attempts
  *   the number of sampling attempts actually made; equals `budget` on full exhaustion
  */
final class GenFilterExhaustedException(val budget: Int, val attempts: Int)(using Frame)
    extends kyo.KyoException(
        s"Gen.filter exhausted its retry budget of $budget after $attempts attempts without an accepted value. Prefer constructing valid values directly (e.g. Gen.int.map(_.abs)) over filtering a narrow predicate."
    )
