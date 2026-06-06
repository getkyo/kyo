package kyo.test.prop

import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ScalaTest bootstrap: kyo-test-prop has no KyoTestPlugin (would be circular); only ScalaTest is available here.

/** Regression tests for generator SEED INDEPENDENCE (the ZIO test/Gen #9101 class).
  *
  * The shared failure mode: one generator consuming the random seed FREEZES a sibling or earlier-drawn value, so it stays constant across
  * samples. ZIO #9101 is the canonical instance ; a `uuid` drawn before a `fromIterable` in a for-comprehension stays the same for every
  * sample, because the composition reused one seed instead of splitting it. It also surfaced through derived generators (a `fromIterable`
  * field freezing the whole derived value).
  *
  * kyo-test's `Gen` splits the seed on every composition (`flatMap` splits into independent outer/inner streams; `zipWith` splits into
  * `s1`/`s2`; derivation samples each field from a split seed), so a sub-generator can never freeze another. These tests pin that property
  * across composition order, `flatMap` branches, `zip`, and derived product generators. They sample the same generator many times and
  * assert the value under test actually VARIES (a frozen value collapses to a single distinct sample) and that composed components are not
  * locked equal. A failure here means a composition reused a seed ; the exact #9101 defect.
  */
class GenSeedIndependenceTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = ExecutionContext.global

    private val size  = 100
    private val count = 200

    "a value drawn BEFORE a seed-consuming generator still varies (ZIO #9101)" in {
        // for { x <- Gen.long; _ <- Gen.list(Gen.int) } yield x  -- the list generator must not freeze x.
        val g =
            for
                x <- Gen.long
                _ <- Gen.list(Gen.int)
            yield x
        val xs = g.samples(42L, size, count).distinct
        assert(xs.size > 1, s"x froze: only ${xs.size} distinct value(s) across $count samples (first: ${xs.headMaybe})")
        Future.successful(succeed)
    }

    "a value drawn AFTER a seed-consuming generator still varies (order independence)" in {
        // for { _ <- Gen.list(Gen.int); x <- Gen.long } yield x  -- order must not matter.
        val g =
            for
                _ <- Gen.list(Gen.int)
                x <- Gen.long
            yield x
        val xs = g.samples(42L, size, count).distinct
        assert(xs.size > 1, s"x froze: only ${xs.size} distinct value(s) across $count samples (first: ${xs.headMaybe})")
        Future.successful(succeed)
    }

    "flatMap branches draw independent randomness (the inner value is not locked to the outer)" in {
        val g     = Gen.int.flatMap(a => Gen.int.map(b => (a, b)))
        val pairs = g.samples(42L, size, count)
        assert(pairs.exists { case (a, b) => a != b }, s"flatMap branches are locked: every (a, b) had a == b across $count samples")
        assert(pairs.map(_._2).distinct.size > 1, s"flatMap inner value froze across $count samples")
        Future.successful(succeed)
    }

    "zip components are independent (not locked equal) and each varies" in {
        val g     = Gen.zip(Gen.long, Gen.long)
        val pairs = g.samples(42L, size, count)
        assert(pairs.exists { case (a, b) => a != b }, s"zip components are locked: every (a, b) had a == b across $count samples")
        assert(pairs.map(_._1).distinct.size > 1, s"zip left component froze across $count samples")
        assert(pairs.map(_._2).distinct.size > 1, s"zip right component froze across $count samples")
        Future.successful(succeed)
    }

    "a derived product generator's fields vary independently (ZIO #9101 derived case)" in {
        val g    = Gen.derive[TwoInts]
        val vals = g.samples(42L, size, count)
        assert(vals.map(_.a).distinct.size > 1, s"derived field 'a' froze across $count samples")
        assert(vals.map(_.b).distinct.size > 1, s"derived field 'b' froze across $count samples")
        assert(vals.exists(v => v.a != v.b), s"derived fields are locked: every value had a == b across $count samples")
        Future.successful(succeed)
    }

end GenSeedIndependenceTest

/** Top-level product type for the derived-generator independence test (Mirror-derivable, fields have a `given Gen[Int]`). */
case class TwoInts(a: Int, b: Int)
