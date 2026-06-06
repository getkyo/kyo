package kyo.test.prop

import kyo.Chunk
import kyo.Maybe.Present
import kyo.test.TestResult
import kyo.test.prop.PropertyTestBase
import kyo.test.prop.internal.Seed
import kyo.test.prop.internal.Tree
import kyo.test.runner.TestRunner
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ── Deterministic fixture gens ──────────────────────────────────────────────
// A generator that always samples 100, carrying the full halving shrink tree.
// Because sampling is constant, the minimal counterexample reached by the runner is
// independent of the seed, so the new map/flatMap-shrink fix can be proven with an exact value.

object IntegratedShrinkGens:
    val bigConst: Gen[Int] = new Gen[Int]:
        def sample(seed: Seed, size: Int): Tree[Int] =
            Tree.unfold(100)(Gen.shrinkInt)
end IntegratedShrinkGens

/** map over bigConst then property v < 5. shrinkInt(100)=50,25,12,6,3,1,0; mapped (+1): walking the halving tree depth-first taking the
  * first still-failing child reaches source 6 (mapped 7), whose children (3,1,0 -> 4,2,1) all pass. Minimal mapped counterexample = 7.
  *
  * Under the OLD flat Gen, map dropped shrinking (shrink == Chunk.empty), so the counterexample would have stayed 101.
  */
class MapShrinkSuite extends PropertyTestBase[Any]:
    forAll(IntegratedShrinkGens.bigConst.map(_ + 1)) { v => assert(v < 5) }
end MapShrinkSuite

/** flatMap over bigConst into a leaf gen, property v < 5. Outer shrinks (50,25,...) propagate through the rose-tree bind; minimal failing
  * source on the halving path is 6, so the minimal counterexample value is 6.
  *
  * Under the OLD flat Gen, flatMap dropped shrinking, leaving the counterexample at 100.
  */
class FlatMapShrinkSuite extends PropertyTestBase[Any]:
    forAll(IntegratedShrinkGens.bigConst.flatMap(n => Gen.const(n))) { v => assert(v < 5) }
end FlatMapShrinkSuite

/** frequency picks the only sub-gen (bigConst); its shrinks now propagate (was Chunk.empty). Minimal counterexample = 6. */
class FrequencyShrinkSuite extends PropertyTestBase[Any]:
    forAll(Gen.frequency((1, IntegratedShrinkGens.bigConst))) { v => assert(v < 5) }
end FrequencyShrinkSuite

/** filter still shrinks: bigConst filtered to even-or-odd keeps every value here (predicate always true for the halving path's pruning),
  * property v < 5. Minimal counterexample = 6 (same halving path; filter prunes nothing here).
  */
class FilterShrinkSuite extends PropertyTestBase[Any]:
    forAll(IntegratedShrinkGens.bigConst.filter(_ >= 0)) { v => assert(v < 5) }
end FilterShrinkSuite

case class Wrapped(n: Int) derives CanEqual

/** Derived product gen shrinks its field. Field gen is bigConst; property n < 5. Minimal Wrapped.n = 6. */
class DeriveShrinkSuite extends PropertyTestBase[Any]:
    private given Gen[Int] = IntegratedShrinkGens.bigConst
    forAll(Gen.derive[Wrapped]) { w => assert(w.n < 5) }
end DeriveShrinkSuite

/** Tests proving integrated shrinking now propagates through map/flatMap/frequency/filter/derive, and that sampling + shrinking are
  * deterministic for a fixed seed.
  *
  * ScalaTest bootstrap: tests the PropertyTest DSL via TestRunner.runToFuture (cannot self-host).
  */
class IntegratedShrinkTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    private def shrunkInt(report: kyo.test.TestReport): Int =
        val allResults = report.suiteReports.flatMap(_.leafResults)
        assert(allResults.nonEmpty, "Expected a leaf result")
        val (_, result) = allResults.head
        result match
            case f: TestResult.Failed =>
                f.cause match
                    case Present(e: PropertyFailedException) =>
                        e.shrunkValue match
                            case n: Int => n
                            case other  => fail(s"Expected Int shrunkValue, got: $other")
                    case other => fail(s"Expected Present(PropertyFailedException), got: $other")
            case other => fail(s"Expected Failed, got $other")
        end match
    end shrunkInt

    "map now propagates shrinking: minimal counterexample is 7 (was unshrunk 101)" in {
        TestRunner.runToFuture(classOf[MapShrinkSuite]).map { report =>
            assert(shrunkInt(report) == 7, "map should shrink to the minimal counterexample 7")
            succeed
        }
    }

    "flatMap now propagates shrinking: minimal counterexample is 6 (was unshrunk 100)" in {
        TestRunner.runToFuture(classOf[FlatMapShrinkSuite]).map { report =>
            assert(shrunkInt(report) == 6, "flatMap should shrink to the minimal counterexample 6")
            succeed
        }
    }

    "frequency now propagates shrinking: minimal counterexample is 6 (was Chunk.empty)" in {
        TestRunner.runToFuture(classOf[FrequencyShrinkSuite]).map { report =>
            assert(shrunkInt(report) == 6, "frequency should shrink to the minimal counterexample 6")
            succeed
        }
    }

    "filter still propagates shrinking: minimal counterexample is 6" in {
        TestRunner.runToFuture(classOf[FilterShrinkSuite]).map { report =>
            assert(shrunkInt(report) == 6, "filter should shrink to the minimal counterexample 6")
            succeed
        }
    }

    "derived product gen still propagates shrinking: minimal field is 6" in {
        TestRunner.runToFuture(classOf[DeriveShrinkSuite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected a leaf result")
            val (_, result) = allResults.head
            result match
                case f: TestResult.Failed =>
                    f.cause match
                        case Present(e: PropertyFailedException) =>
                            e.shrunkValue match
                                case w: Wrapped => assert(w.n == 6, s"Expected minimal Wrapped(6), got $w")
                                case other      => fail(s"Expected Wrapped shrunkValue, got: $other")
                        case other => fail(s"Expected Present(PropertyFailedException), got: $other")
                case other => fail(s"Expected Failed, got $other")
            end match
            succeed
        }
    }

    // ── Determinism ──────────────────────────────────────────────────────────

    "same seed yields the same sample value" in {
        val a = Gen.int.sample(Seed(123L), 50).value
        val b = Gen.int.sample(Seed(123L), 50).value
        assert(a == b, s"Same seed should sample the same value: $a vs $b")
        Future.successful(succeed)
    }

    "same seed yields the same shrink path" in {
        val pathA = Gen.int.sample(Seed(123L), 50).shrinks().map(_.value).take(10).toList
        val pathB = Gen.int.sample(Seed(123L), 50).shrinks().map(_.value).take(10).toList
        assert(pathA == pathB, s"Same seed should yield the same shrink children: $pathA vs $pathB")
        Future.successful(succeed)
    }

    "flatMap re-sampling during shrink expansion is reproducible" in {
        val g      = Gen.int.flatMap(n => Gen.list(Gen.int).map(xs => (n, xs)))
        val first  = g.sample(Seed(555L), 30)
        val second = g.sample(Seed(555L), 30)
        assert(first.value == second.value, s"flatMap sample should be deterministic: ${first.value} vs ${second.value}")
        val s1 = first.shrinks().map(_.value).take(5).toList
        val s2 = second.shrinks().map(_.value).take(5).toList
        assert(s1 == s2, s"flatMap shrink expansion should be reproducible: $s1 vs $s2")
        Future.successful(succeed)
    }

end IntegratedShrinkTest
