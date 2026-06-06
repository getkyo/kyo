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

// ── Fixture suite for the applicative minimality proof ──────────────────────
// Top-level so TestRunner.runToFuture can discover it by class reference (nested
// classes require a $ in the class name and may fail on some platforms).

/** forAll arity 2 with two constant-100 gens; property a + b < 0 fails at both (100, 100)
  * (since 200 < 0 is false) and at (0, 0) (since 0 < 0 is false). After applicative shrinking,
  * both components minimize independently toward 0, giving the minimal failing counterexample
  * (0, 0). A monadic zip cannot guarantee this because the inner component is re-sampled per
  * outer shrink and the dependency blocks component-wise minimization.
  */
class ZipMinSuite extends PropertyTestBase[Any]:
    forAll(IntegratedShrinkGens.bigConst, IntegratedShrinkGens.bigConst) { (a, b) => assert(a + b < 0) }
end ZipMinSuite

/** GenZipTest: pins the public-visibility, root-stability, and applicative-shrink guarantees for
  * the Gen.zip family and Gen.zipWith.
  *
  * ScalaTest bootstrap: tests the Gen API itself; uses AsyncFreeSpec + NonImplicitAssertions,
  * same pattern as IntegratedShrinkTest.
  */
class GenZipTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = TestExecutionContext.executionContext

    // ── Leaf 1: public visibility (compile-time proof) ──────────────────────

    "zip/zip3/zip4/zipWith are public and callable from outside the package" in {
        // All four calls must type-check here (package kyo.test.prop, not internal).
        val _        = Gen.zip(Gen.int, Gen.int)
        val _        = Gen.zip3(Gen.int, Gen.int, Gen.int)
        val _        = Gen.zip4(Gen.int, Gen.int, Gen.int, Gen.int)
        val combined = Gen.zipWith(Gen.int, Gen.string)((i, s) => s + i.toString)
        // Read a root value to confirm the Gen is operational: the combiner appends i.toString (always at
        // least one digit), so a sampled value is always non-empty, proving the zipWith combiner actually ran.
        val v: String = combined.sample(Seed(1L), 10).value
        assert(v.nonEmpty, s"Expected zipWith's combiner to append a non-empty int suffix, got: '$v'")
        Future.successful(succeed)
    }

    // ── Leaf 2: zip equals zipWith with a tuple combiner ────────────────────

    "zip equals zipWith with a tuple combiner (no duplicated logic)" in {
        val seed       = Seed(99L)
        val size       = 50
        val viaZip     = Gen.zip(Gen.int, Gen.int).sample(seed, size).value
        val viaZipWith = Gen.zipWith(Gen.int, Gen.int)((a, b) => (a, b)).sample(seed, size).value
        assert(viaZip == viaZipWith, s"zip and zipWith((_,_)) must produce the same root tuple; got $viaZip vs $viaZipWith")
        Future.successful(succeed)
    }

    // ── Leaf 3: zip root tuple is seed-stable (topology preserved, INV-004) ──

    "zip root tuple is seed-stable (deterministic, topology preserved)" in {
        val seed   = Seed(7L)
        val size   = 50
        val first  = Gen.zip(Gen.int, Gen.string).sample(seed, size).value
        val second = Gen.zip(Gen.int, Gen.string).sample(seed, size).value
        assert(first == second, s"zip root tuple must be deterministic: $first vs $second")
        Future.successful(succeed)
    }

    // ── Leaf 4: zip sample tree shrinks both components (applicative interleave) ──

    "zip sample tree has interleaved children: one component shrinks while the other is held fixed" in {
        val seed = Seed(7L)
        val size = 50
        val tree = Gen.zip(Gen.int, Gen.int).sample(seed, size)
        val root = tree.value
        // Guard: if both components are already 0, there is nothing to shrink and the
        // applicative interleave cannot be demonstrated. The seed/size pair is chosen so
        // this does not happen, but the guard makes the assertion deterministic.
        assert(
            root._1 != 0 || root._2 != 0,
            s"Guard: expected a non-(0,0) root tuple to demonstrate shrink interleave, got $root"
        )
        val children = tree.shrinks().take(20).toList
        assert(children.nonEmpty, "A non-(0,0) tuple must have shrink children")
        // There must exist a child that shrinks only component 1 (second == root second).
        val shrinksFirst = children.exists(c => c.value._2 == root._2 && c.value._1 != root._1)
        // There must exist a child that shrinks only component 2 (first == root first).
        val shrinksSecond = children.exists(c => c.value._1 == root._1 && c.value._2 != root._2)
        assert(shrinksFirst, s"Expected a child that shrinks only component 1; children: ${children.map(_.value)}")
        assert(shrinksSecond, s"Expected a child that shrinks only component 2; children: ${children.map(_.value)}")
        Future.successful(succeed)
    }

    // ── Leaf 5: arity-2 forAll reaches the component-wise minimal counterexample ──

    "arity-2 forAll reaches the component-wise minimal counterexample (0, 0) via applicative shrinking" in {
        // ZipMinSuite: forAll(bigConst, bigConst) { (a, b) => assert(a + b < 0) }
        // At (100, 100): 200 < 0 is false, so the property FAILS at the initial sample.
        // At (0, 0): 0 + 0 = 0, 0 < 0 is false, so (0, 0) is also a failing counterexample.
        // Applicative shrinking minimizes each component independently, reaching (0, 0).
        TestRunner.runToFuture(classOf[ZipMinSuite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected a leaf result from ZipMinSuite")
            val (_, result) = allResults.head
            result match
                case f: TestResult.Failed =>
                    f.cause match
                        case Present(e: PropertyFailedException) =>
                            e.shrunkValue match
                                case p: (Int, Int) @unchecked =>
                                    assert(
                                        p == (0, 0),
                                        s"Applicative shrinking must reach the component-wise minimum (0, 0); got $p"
                                    )
                                    succeed
                                case other =>
                                    fail(s"Expected (Int, Int) shrunkValue, got: $other")
                        case other =>
                            fail(s"Expected Present(PropertyFailedException), got: $other")
                case other =>
                    fail(s"Expected ZipMinSuite to fail, got: $other")
            end match
        }
    }

    // ── Leaf 6: zip3 sample is seed-stable and shrinks each of three components ──

    "zip3 sample is seed-stable and has children for each of three components" in {
        val seed  = Seed(11L)
        val size  = 50
        val treeA = Gen.zip3(Gen.int, Gen.int, Gen.int).sample(seed, size)
        val treeB = Gen.zip3(Gen.int, Gen.int, Gen.int).sample(seed, size)
        assert(treeA.value == treeB.value, s"zip3 root triple must be deterministic: ${treeA.value} vs ${treeB.value}")
        val root = treeA.value
        // Only verify component-wise shrinks when the root has at least one non-zero component.
        if root._1 != 0 || root._2 != 0 || root._3 != 0 then
            val children = treeA.shrinks().take(30).toList.map(_.value)
            assert(children.nonEmpty, "A non-(0,0,0) triple must have shrink children")
            val shrinksComp1 = children.exists(c => c._2 == root._2 && c._3 == root._3 && c._1 != root._1)
            val shrinksComp2 = children.exists(c => c._1 == root._1 && c._3 == root._3 && c._2 != root._2)
            val shrinksComp3 = children.exists(c => c._1 == root._1 && c._2 == root._2 && c._3 != root._3)
            assert(shrinksComp1, s"Expected a child shrinking only component 1; children: $children")
            assert(shrinksComp2, s"Expected a child shrinking only component 2; children: $children")
            assert(shrinksComp3, s"Expected a child shrinking only component 3; children: $children"): Unit
        end if
        Future.successful(succeed)
    }

    // ── Leaf 7: zip4 sample is seed-stable ──────────────────────────────────

    "zip4 sample is seed-stable" in {
        val seed  = Seed(13L)
        val size  = 50
        val treeA = Gen.zip4(Gen.int, Gen.int, Gen.int, Gen.int).sample(seed, size)
        val treeB = Gen.zip4(Gen.int, Gen.int, Gen.int, Gen.int).sample(seed, size)
        assert(treeA.value == treeB.value, s"zip4 root quad must be deterministic: ${treeA.value} vs ${treeB.value}")
        Future.successful(succeed)
    }

    // ── Leaf 8: arity 2/3/4 PASS self-suites still pass (regression) ────────

    "arity-2 PASS suite still passes after applicative rewrite" in {
        TestRunner.runToFuture(classOf[PTSelfArity2Suite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected a leaf result")
            val (_, result) = allResults.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected PTSelfArity2Suite to pass, got $other")
        }
    }

    "arity-3 PASS suite still passes after applicative rewrite" in {
        TestRunner.runToFuture(classOf[PTSelfArity3Suite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected a leaf result")
            val (_, result) = allResults.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected PTSelfArity3Suite to pass, got $other")
        }
    }

    "arity-4 PASS suite still passes after applicative rewrite" in {
        TestRunner.runToFuture(classOf[PTSelfArity4Suite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected a leaf result")
            val (_, result) = allResults.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected PTSelfArity4Suite to pass, got $other")
        }
    }

    // ── Leaf 9: flatMap caveat is doc-only (behavior unchanged, regression) ──

    "flatMap caveat is documentation-only: flatMap behavior is deterministic and unchanged" in {
        val g      = Gen.int.flatMap(n => Gen.const(n))
        val seed   = Seed(5L)
        val size   = 30
        val first  = g.sample(seed, size)
        val second = g.sample(seed, size)
        assert(first.value == second.value, s"flatMap must be deterministic: ${first.value} vs ${second.value}")
        val s1 = first.shrinks().take(5).map(_.value).toList
        val s2 = second.shrinks().take(5).map(_.value).toList
        assert(s1 == s2, s"flatMap shrink expansion must be reproducible: $s1 vs $s2")
        Future.successful(succeed)
    }

end GenZipTest
