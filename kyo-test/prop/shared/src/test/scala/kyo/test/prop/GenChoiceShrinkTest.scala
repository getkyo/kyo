package kyo.test.prop

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

// ── Test fixtures ──────────────────────────────────────────────────────────────

/** Three-variant sealed trait used to test sum derivation shrinking toward the index-0 subtype. */
sealed trait Shape derives CanEqual
case object Dot                extends Shape // idx 0, simplest base case
case class Line(n: Int)        extends Shape // idx 1
case class Box(w: Int, h: Int) extends Shape // idx 2

/** Recursive sealed trait used to test that the size-0 -> idx-0 guard prevents stack overflow. */
sealed trait Nat derives CanEqual
case object Zero           extends Nat // idx 0, base case
case class Succ(prev: Nat) extends Nat // idx 1, recursive

/** Gen[Nat] defined with a lazy indirection to break the runtime initialization cycle.
  *
  * Gen.derive[Nat] emits code that references a Gen[Nat] given for the Succ field. Using a lazy
  * wrapper that delegates to a separately-initialized val means the wrapper object is constructed
  * eagerly (as the given) but the inner derived generator is only instantiated on the first `sample`
  * call, after the given itself has been assigned.
  */
object NatGen:
    // The given must be in scope at the Gen.derive[Nat] macro-expansion site so that the derived
    // Succ generator's field array references this given rather than triggering further derivation.
    // The lazy val avoids the eager initialization cycle: genDerived is only forced on the first
    // sample() call, by which point the given val natGen is already fully initialized.
    private lazy val genDerived: Gen[Nat] = Gen.derive[Nat]
    given natGen: Gen[Nat] = new Gen[Nat]:
        def sample(seed: Seed, size: Int): Tree[Nat] = genDerived.sample(seed, size)
end NatGen

/** Fixture suite whose property always fails (for every Shape subtype).
  *
  * Using an always-false property means Line and Box also fail on first sample. When the first
  * sampled value is Line or Box, the greedy shrink walk traverses the cross-subtype candidates
  * prepended by sumTree (earlier-index subtypes in ascending index order, simplest first). Dot is the idx-0 candidate and
  * also fails the always-false property, so the walk accepts it and then finds no further shrinks
  * (Dot has empty earlier array). This exercises the cross-subtype shrink path from a non-base
  * subtype to Dot.
  */
class SumShrinkSuite extends PropertyTestBase[Any]:
    private given Gen[Int] = IntegratedShrinkGens.bigConst
    // Always-false: every Shape fails so a first sample of Line or Box triggers cross-subtype shrink to Dot.
    forAll(Gen.derive[Shape]) { _ => assert(false, "always-false probe: forces cross-subtype shrink to idx-0") }
end SumShrinkSuite

/** Fixture suite whose property always fails (for every Nat value).
  *
  * Using an always-false property means Succ(...) chains also fail on first sample. When the first
  * sampled value is a Succ chain, the greedy shrink walk traverses the cross-subtype candidates
  * (Zero, the idx-0 base case) prepended by sumTree, then recurses. Zero has an empty earlier
  * array so the walk stops. This exercises the cross-subtype shrink path from Succ to Zero.
  */
class NatShrinkSuite extends PropertyTestBase[Any]:
    import NatGen.given
    // Always-false: every Nat fails so a first sample of Succ(...) triggers cross-subtype shrink to Zero.
    forAll(NatGen.natGen) { _ => assert(false, "always-false probe: forces cross-subtype shrink to idx-0 Zero") }
end NatShrinkSuite

/** Tests for oneOf / frequency / deriveSum shrink-toward-earlier behavior. */
class GenChoiceShrinkTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // ── oneOf ──────────────────────────────────────────────────────────────

    "oneOf shrinks a later choice toward earlier choices" in {
        // Find a seed where the root is "c" (idx 2), then verify shrinks are List("b", "a").
        val g    = Gen.oneOf("a", "b", "c")
        val seed = (0 to 200).map(i => Seed(i.toLong)).find(s => g.sample(s, 10).value == "c")
        assert(seed.isDefined, "Expected to find a seed producing root 'c' in 0..200")
        val tree = g.sample(seed.get, 10)
        assert(tree.value == "c")
        val shrinkValues = tree.shrinks().map(_.value).toList
        assert(shrinkValues == List("a", "b"), s"Expected List(a, b) but got $shrinkValues")
        Future.successful(succeed)
    }

    "oneOf earliest choice has no shrinks" in {
        // Find a seed where the root is "a" (idx 0), then verify no shrinks.
        val g    = Gen.oneOf("a", "b", "c")
        val seed = (0 to 200).map(i => Seed(i.toLong)).find(s => g.sample(s, 10).value == "a")
        assert(seed.isDefined, "Expected to find a seed producing root 'a' in 0..200")
        val tree = g.sample(seed.get, 10)
        assert(tree.value == "a")
        val shrinkValues = tree.shrinks().toList
        assert(shrinkValues.isEmpty, s"Expected empty shrinks for earliest choice, got $shrinkValues")
        Future.successful(succeed)
    }

    "oneOf value is always one of the choices (regression)" in {
        val choices = Set(1, 2, 3)
        val g       = Gen.oneOf(1, 2, 3)
        val violations = (0 to 1000).flatMap { i =>
            val tree = g.sample(Seed(i.toLong), 10)
            val root = tree.value
            // Collect root and first few shrink candidates
            val allVals = root :: tree.shrinks().map(_.value).take(5).toList
            allVals.filterNot(choices.contains)
        }
        assert(violations.isEmpty, s"Found values outside choices: $violations")
        Future.successful(succeed)
    }

    // ── frequency ──────────────────────────────────────────────────────────

    "frequency multi-entry shrinks toward earlier entries" in {
        // Three equal-weight entries; find a seed where the root is "third" (idx 2).
        val g = Gen.frequency(
            (1, Gen.const("first")),
            (1, Gen.const("second")),
            (1, Gen.const("third"))
        )
        val seed = (0 to 400).map(i => Seed(i.toLong)).find(s => g.sample(s, 10).value == "third")
        assert(seed.isDefined, "Expected to find a seed producing root 'third' in 0..400")
        val tree = g.sample(seed.get, 10)
        assert(tree.value == "third")
        val shrinkValues = tree.shrinks().map(_.value).take(4).toList
        // First two shrink candidates must be "first" then "second" (ascending index order, simplest first).
        assert(shrinkValues.size >= 2, s"Expected at least 2 shrink candidates, got $shrinkValues")
        assert(shrinkValues(0) == "first", s"Expected shrinkValues(0) == 'first', got ${shrinkValues(0)}")
        assert(shrinkValues(1) == "second", s"Expected shrinkValues(1) == 'second', got ${shrinkValues(1)}")
        Future.successful(succeed)
    }

    "single-entry frequency is identical to today (idx 0 fast-path)" in {
        // A single-entry frequency always hits idx 0, so no earlier entries are prepended.
        // The shrink list is purely the chosen sub-generator's own shrinks (same as before this improvement).
        val g            = Gen.frequency((1, IntegratedShrinkGens.bigConst))
        val tree         = g.sample(Seed(1L), 50)
        val shrinkValues = tree.shrinks().map(_.value).take(7).toList
        assert(shrinkValues == List(50, 25, 12, 6, 3, 1, 0), s"Expected List(50,25,12,6,3,1,0), got $shrinkValues")
        Future.successful(succeed)
    }

    // ── deriveSum ──────────────────────────────────────────────────────────

    "derived sum cross-subtype shrink: non-Dot root's shrinks include Dot (direct tree proof)" in {
        // Deterministic direct-tree test: scan seeds to find one where Gen.derive[Shape] produces
        // a non-Dot root (Line or Box). Then assert that the first 20 shrink candidates include
        // at least one Dot, proving sumTree prepends the idx-0 base case for non-base subtypes.
        // bigConst is given so Line/Box field values are deterministic (always 100) and the test
        // does not depend on specific Int shrink paths.
        given Gen[Int] = IntegratedShrinkGens.bigConst
        val g          = Gen.derive[Shape]
        // Scan seeds 0..500 for a non-Dot root. With three subtypes the non-Dot probability is ~2/3,
        // so a non-Dot root appears very frequently; 500 seeds is more than sufficient.
        val nonDotSeed = (0 to 500).map(i => Seed(i.toLong)).find { s =>
            val root = g.sample(s, 10).value
            !root.isInstanceOf[Dot.type]
        }
        assert(nonDotSeed.isDefined, "Expected to find a seed producing a non-Dot root in 0..500")
        val tree = g.sample(nonDotSeed.get, 10)
        assert(
            !tree.value.isInstanceOf[Dot.type],
            s"Expected a non-Dot root, got: ${tree.value}"
        )
        val shrinkValues = tree.shrinks().take(20).map(_.value).toList
        assert(
            shrinkValues.exists(_.isInstanceOf[Dot.type]),
            s"Expected at least one Dot candidate in the first 20 shrinks of ${tree.value}, got: $shrinkValues"
        )
        Future.successful(succeed)
    }

    "derived sum shrinks toward the earliest/simplest subtype (forAll-integrated)" in {
        TestRunner.runToFuture(classOf[SumShrinkSuite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected a leaf result from SumShrinkSuite")
            val (_, result) = allResults.head
            result match
                case f: TestResult.Failed =>
                    f.cause match
                        case Present(e: PropertyFailedException) =>
                            assert(
                                e.shrunkValue.isInstanceOf[Dot.type],
                                s"Expected shrunk value to be Dot (idx-0 subtype) but got: ${e.shrunkValue}"
                            )
                        case other => fail(s"Expected Present(PropertyFailedException), got: $other")
                case other => fail(s"Expected Failed, got $other")
            end match
            succeed
        }
    }

    "Nat cross-subtype shrink: Succ chain shrinks to Zero (forAll-integrated)" in {
        // An always-false property over Nat means any Succ(...) chain also fails and must shrink
        // to Zero via the cross-subtype prepend (Zero is the idx-0 candidate in every Succ tree).
        TestRunner.runToFuture(classOf[NatShrinkSuite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected a leaf result from NatShrinkSuite")
            val (_, result) = allResults.head
            result match
                case f: TestResult.Failed =>
                    f.cause match
                        case Present(e: PropertyFailedException) =>
                            assert(
                                e.shrunkValue.isInstanceOf[Zero.type],
                                s"Expected shrunk Nat to be Zero (idx-0 base case) but got: ${e.shrunkValue}"
                            )
                        case other => fail(s"Expected Present(PropertyFailedException), got: $other")
                case other => fail(s"Expected Failed, got $other")
            end match
            succeed
        }
    }

    "recursive ADT derivation terminates at several sizes" in {
        // The size-0 -> idx-0 guard prevents infinite recursion on the recursive Nat type.
        // The earlier-subtype thunks are lazy, so they do not cause a stack overflow when forced in bounded quantities.
        // NatGen.natGen wraps the derived gen via a lazy indirection to break the initialization cycle.
        val g          = NatGen.natGen
        val sizes      = List(0, 1, 5, 20)
        var violations = List.empty[String]
        for
            sz <- sizes
            i  <- 0 to 50
        do
            try
                val tree = g.sample(Seed(i.toLong), sz)
                // Force a bounded prefix of the shrink list only (never .toList on a potentially infinite lazy list)
                val _ = tree.shrinks().take(20).toList
                // At size 0 the value must be Zero (base case)
                if sz == 0 && !tree.value.isInstanceOf[Zero.type] then
                    violations = s"size=0 seed=$i: expected Zero but got ${tree.value}" :: violations
            catch
                case e: StackOverflowError =>
                    violations = s"StackOverflowError at size=$sz seed=$i" :: violations
            end try
        end for
        assert(violations.isEmpty, s"Termination violations: ${violations.mkString("; ")}")
        Future.successful(succeed)
    }

    "product derive (DeriveShrinkSuite) unchanged (regression)" in {
        TestRunner.runToFuture(classOf[DeriveShrinkSuite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected a leaf result from DeriveShrinkSuite")
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

    "sumTree with empty earlier returns chosen unchanged" in {
        // Direct unit test for GenDeriveRuntime.sumTree: empty earlier -> identity.
        val chosen = Tree.unfold(1)(n => if n > 0 then Iterable(n - 1) else Nil)
        val result = kyo.test.prop.internal.GenDeriveRuntime.sumTree(chosen, Array.empty)
        assert(result.value == 1, s"Expected value 1, got ${result.value}")
        assert(result.shrinks().take(3).map(_.value).toList == List(0), s"Expected same shrinks as chosen")
        Future.successful(succeed)
    }

    "sumTree with non-empty earlier prepends the earlier tree's value before chosen's shrinks" in {
        // Direct unit test: earlier has one entry (Tree.leaf(42)); chosen is Tree.leaf(100).
        // Result should have value 100, first shrink is 42, then chosen's shrinks (empty).
        val chosen  = Tree.leaf(100)
        val earlier = Array[() => Tree[Int]](() => Tree.leaf(42))
        val result  = kyo.test.prop.internal.GenDeriveRuntime.sumTree(chosen, earlier)
        assert(result.value == 100, s"Expected root value 100, got ${result.value}")
        val shrinkValues = result.shrinks().map(_.value).toList
        assert(shrinkValues == List(42), s"Expected List(42) but got $shrinkValues")
        Future.successful(succeed)
    }

end GenChoiceShrinkTest
