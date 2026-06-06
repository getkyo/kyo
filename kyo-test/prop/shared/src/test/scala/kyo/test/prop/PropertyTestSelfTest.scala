package kyo.test.prop

import java.util.concurrent.atomic.AtomicInteger
import kyo.Chunk
import kyo.Maybe
import kyo.test.TestResult
import kyo.test.prop.PropertyTestBase
import kyo.test.runner.TestRunner
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ── Fixture suites ──────────────────────────────────────────────────────────
// Top-level so TestRunner.runToFuture can discover them by class.

/** forAll passes: x * 2 == x + x always holds. */
class PTSelfPassSuite extends PropertyTestBase[Any]:
    forAll(Gen.int) { x => assert(x * 2 == x + x) }
end PTSelfPassSuite

/** forAll fails: x >= -50 fails for x <= -51 in the [-100, -51] range. */
class PTSelfFailSuite extends PropertyTestBase[Any]:
    forAll(Gen.int) { x => assert(x >= -50) }
end PTSelfFailSuite

/** forAll arity 2: string length is always non-negative. */
class PTSelfArity2Suite extends PropertyTestBase[Any]:
    forAll(Gen.int, Gen.string) { (n, s) => assert(s.length >= 0) }
end PTSelfArity2Suite

/** forAll arity 3: integer addition is associative. */
class PTSelfArity3Suite extends PropertyTestBase[Any]:
    forAll(Gen.int, Gen.int, Gen.int) { (a, b, c) => assert((a + b) + c == a + (b + c)) }
end PTSelfArity3Suite

/** forAll arity 4: addition is commutative across 4 terms. */
class PTSelfArity4Suite extends PropertyTestBase[Any]:
    forAll(Gen.int, Gen.int, Gen.int, Gen.int) { (a, b, c, d) => assert(a + b + c + d == d + c + b + a) }
end PTSelfArity4Suite

/** forAll with Kyo body (assert is a plain expression that auto-lifts). */
class PTSelfKyoBodySuite extends PropertyTestBase[Any]:
    // Always-true arithmetic property on purpose: this fixture exercises the forAll PASS path and the auto-lift of an assert body.
    forAll(Gen.int) { x => assert(x - x == 0) }
end PTSelfKyoBodySuite

/** forAll with numSamples = 3; records invocation count in PTSelfSampleCounter. */
class PTSelfSampleSuite extends PropertyTestBase[Any]:
    override def numSamples: Int = 3
    // Always-true arithmetic property on purpose: the fixture must PASS so all samples run; the real check is the invocation count.
    forAll(Gen.int) { x =>
        PTSelfSampleCounter.counter.incrementAndGet(): Unit
        assert(x - x == 0)
    }
end PTSelfSampleSuite

object PTSelfSampleCounter:
    val counter: AtomicInteger = new AtomicInteger(0)

// ── PropertyTestSelfTest ─────────────────────────────────────────────────────

/** Self-tests for the kyo.test.prop.PropertyTest[S] DSL.
  *
  * ScalaTest bootstrap: this file tests PropertyTest DSL itself; cannot self-host using the framework-under-test. Uses
  * TestRunner.runToFuture (available via kyo-test-runner % Test).
  */
// ScalaTest bootstrap: this file tests PropertyTest DSL itself; cannot self-host using the framework-under-test.
class PropertyTestSelfTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    "forAll passes when property holds" in {
        TestRunner.runToFuture(classOf[PTSelfPassSuite]).map { report =>
            val results = report.suiteReports.flatMap(_.leafResults)
            assert(results.nonEmpty, "Expected at least one leaf result")
            val (_, result) = results.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "forAll fails with shrunken counterexample" in {
        // Gen.int samples from the producible set: [-size, size] UNION {Int.MinValue, Int.MaxValue} (edge bias).
        // The property 'x >= -50' will fail for any sample below -50. With edge bias the seed-42 schedule
        // surfaces Int.MinValue as the first failing sample. shrinkInt drops the
        // overflowing -v mirror for Int.MinValue (where -Int.MinValue overflows back to Int.MinValue)
        // and halves instead: Int.MinValue -> -1073741824 -> ... -> -64 (the first halving step that is
        // still <= -51). The shrunken value is in [-100, -51] (the same window as before P1 edge bias).
        TestRunner.runToFuture(classOf[PTSelfFailSuite]).map { report =>
            val results = report.suiteReports.flatMap(_.leafResults)
            assert(results.nonEmpty, "Expected at least one leaf result")
            val (_, result) = results.head
            result match
                case f: TestResult.Failed =>
                    assert(f.diagram.contains("Shrunk"), s"Expected 'Shrunk' in diagram: ${f.diagram}")
                    f.cause match
                        case Maybe.Present(e: PropertyFailedException) =>
                            val shrunk = e.shrunkValue match
                                case n: Int => n
                                case other  => fail(s"Expected shrunkValue to be Int, got: $other")
                            end shrunk
                            assert(
                                shrunk <= -51,
                                s"Shrunken counterexample $shrunk should be <= -51 (near-minimal for x >= -50)"
                            )
                            // With edge bias, Int.MinValue is the first failing sample; shrinkInt
                            // halves it through the negative range (no overflow mirror for MinValue), landing in
                            // [-100, -51]. The bound >= -100 is a precise membership check, not a tautology.
                            assert(
                                shrunk >= -100,
                                s"Shrunken counterexample $shrunk should be >= -100 (halving from Int.MinValue lands in [-100,-51])"
                            )
                            succeed
                        case Maybe.Present(other) =>
                            fail(s"Expected cause to be PropertyFailedException, got: ${other.getClass.getName}")
                        case Maybe.Absent =>
                            fail("Expected cause to be Present(PropertyFailedException) but was Absent")
                    end match
                case _: TestResult.Passed =>
                    fail("Expected forAll to fail for x >= -50 with 100 int samples (seed 42 should produce values in [-100,-51])")
                case other => fail(s"Expected Failed, got $other")
            end match
        }
    }

    "forAll arity 2 with two generators" in {
        TestRunner.runToFuture(classOf[PTSelfArity2Suite]).map { report =>
            val results = report.suiteReports.flatMap(_.leafResults)
            assert(results.nonEmpty, "Expected at least one leaf result")
            val (_, result) = results.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "forAll arity 3" in {
        TestRunner.runToFuture(classOf[PTSelfArity3Suite]).map { report =>
            val results = report.suiteReports.flatMap(_.leafResults)
            assert(results.nonEmpty, "Expected at least one leaf result")
            val (_, result) = results.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "forAll arity 4" in {
        TestRunner.runToFuture(classOf[PTSelfArity4Suite]).map { report =>
            val results = report.suiteReports.flatMap(_.leafResults)
            assert(results.nonEmpty, "Expected at least one leaf result")
            val (_, result) = results.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "forAll with Kyo body" in {
        TestRunner.runToFuture(classOf[PTSelfKyoBodySuite]).map { report =>
            val results = report.suiteReports.flatMap(_.leafResults)
            assert(results.nonEmpty, "Expected at least one leaf result")
            val (_, result) = results.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "numSamples override changes sample count" in {
        PTSelfSampleCounter.counter.set(0)
        TestRunner.runToFuture(classOf[PTSelfSampleSuite]).map { report =>
            val results = report.suiteReports.flatMap(_.leafResults)
            assert(results.nonEmpty, "Expected at least one leaf result")
            val (_, result) = results.head
            result match
                case _: TestResult.Passed =>
                    assert(
                        PTSelfSampleCounter.counter.get() == 3,
                        s"Expected body to run exactly 3 times, ran ${PTSelfSampleCounter.counter.get()}"
                    )
                    succeed
                case other => fail(s"Expected Passed, got $other")
            end match
        }
    }

end PropertyTestSelfTest
