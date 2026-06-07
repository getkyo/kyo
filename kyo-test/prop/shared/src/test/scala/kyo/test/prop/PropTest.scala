package kyo.test.prop

import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Maybe
import kyo.Scope
import kyo.kernel.<
import kyo.test.TestResult
import kyo.test.internal.TestContext
import kyo.test.prop.Gen
import kyo.test.prop.PropertyFailedException
import kyo.test.runner.TestRunner
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ── Fixture suites ─────────────────────────────────────────────────────────────────────────────
// Top-level so reflection can instantiate them. They extend kyo.test.prop.PropertyTestBase[S]
// (the marker-free forAll base), NOT kyo.test.prop.PropertyTest[S], so they do NOT carry
// SuiteFingerprintMarker and are not auto-discovered as standalone suites (which matters on Scala
// Native, whose reflective discovery would otherwise run these deliberately-failing fixtures and
// fail the task). Used via TestRunner.runToFuture or direct TestContext probing in the tests below.

/** A passing forAll: x == x is always true. Used for Leaf 1 (registration) and Leaf 3 (extra S row). */
class PTPassSuite extends PropertyTestBase[Any]:
    forAll(Gen.int) { n => assert(n == n) }
end PTPassSuite

/** A failing forAll: n > 0 fails for non-positive samples. Used for Leaf 2 (failure path). */
class PTFailSuite extends PropertyTestBase[Any]:
    forAll(Gen.int) { n => assert(n > 0) }
end PTFailSuite

/** A forAll with numSamples overridden to 5. Used to verify iteration count. */
class PTFiveSampleSuite extends PropertyTestBase[Any]:
    override def numSamples: Int = 5
    forAll(Gen.int) { n => assert(n == n) }
end PTFiveSampleSuite

/** Leaf 3 extra-S scenario: PropertyTest[Async] (S = Async which is already in baseline). */
class PTAsyncSRowSuite extends PropertyTestBase[Async]:
    forAll(Gen.int) { n => assert(n == n) }
end PTAsyncSRowSuite

// ── Helpers ────────────────────────────────────────────────────────────────────────────────────

private def installContext(next: TestContext): Unit =
    TestContext.setForInstantiation(next)

// ── PropTest (pins the PropertyTest[S] effect-row parameterization) ─────────────────────────────

/** Tests for the PropertyTest[S] reparameterization: forAll registers and discharges leaves under the parameterized effect row.
  *
  * ScalaTest AsyncFreeSpec: this file tests the kyo.test.prop.PropertyTest[S] DSL surface; it cannot self-host on the framework-under-test.
  */
class PropTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = TestExecutionContext.executionContext

    // ── Leaf 1: forall-registers-kyo-leaf (registration under discovery) ──────────────────────

    "forall-registers-kyo-leaf: forAll registers a leaf under the new Kyo body row" in {
        val ctx = new TestContext(Chunk(0), discovery = true)
        installContext(ctx)
        val _ = new PTPassSuite
        ctx.signalPastEnd()
        ctx.peekRegisteredLeaf match
            case Maybe.Present((path, result)) =>
                assert(path.nonEmpty, "path should be non-empty")
                val leafName = path.last
                assert(leafName.startsWith("forAll @"), s"leaf name should start with 'forAll @', got: $leafName")
                result match
                    case _: TestResult.Passed => succeed
                    case other                => fail(s"Expected Passed discovery marker, got $other")
            case Maybe.Absent =>
                fail("Expected a leaf result but got Absent")
        end match
        Future.successful(succeed)
    }

    "forall-registers-kyo-leaf: peekWasGroup is false for a forAll leaf" in {
        val ctx = new TestContext(Chunk(0), discovery = true)
        installContext(ctx)
        val _ = new PTPassSuite
        ctx.signalPastEnd()
        assert(!ctx.peekWasGroup, "forAll leaf should not be a group")
        Future.successful(succeed)
    }

    // ── Leaf 2: failure path via runner ──────────────────────────────────────────────────────

    "forall-registers-kyo-leaf: failing forAll records TestResult.Failed with PropertyFailedException and shrunk input" in {
        TestRunner.runToFuture(classOf[PTFailSuite]).map { report =>
            assert(report.suiteReports.nonEmpty, "Expected at least one suite report")
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected at least one leaf result")
            val (_, result) = allResults.head
            result match
                case f: TestResult.Failed =>
                    assert(f.diagram.contains("Property failed!"), s"Expected 'Property failed!' in diagram, got: ${f.diagram}")
                    assert(f.diagram.contains("Shrunk:"), s"Expected 'Shrunk:' in diagram, got: ${f.diagram}")
                    assert(f.diagram.contains("Seed:"), s"Expected 'Seed:' in diagram, got: ${f.diagram}")
                    f.cause match
                        case Maybe.Present(e: PropertyFailedException) =>
                            val shrunk = e.shrunkValue match
                                case n: Int => n
                                case other  => fail(s"Expected shrunkValue Int, got: $other")
                            // n > 0 fails for n <= 0; shrunk should be near-minimal (0 or negative)
                            assert(shrunk <= 0, s"Shrunk value $shrunk should be <= 0 for property n > 0")
                            assert(e.seed == 42L, s"seed should be nonRandomSeed (42L), got: ${e.seed}")
                            succeed
                        case Maybe.Present(other) =>
                            fail(s"Expected PropertyFailedException, got: ${other.getClass.getName}")
                        case Maybe.Absent =>
                            fail("Expected cause to be Present(PropertyFailedException) but was Absent")
                    end match
                case other => fail(s"Expected TestResult.Failed, got $other")
            end match
        }
    }

    // ── Leaf 3: extra S row (PropertyTest[Async]) ─────────────────────────────────────────────

    "forall-registers-kyo-leaf: PropertyTest[Async] (S = Async already in baseline) compiles and registers" in {
        val ctx = new TestContext(Chunk(0), discovery = true)
        installContext(ctx)
        val _ = new PTAsyncSRowSuite
        ctx.signalPastEnd()
        ctx.peekRegisteredLeaf match
            case Maybe.Present((path, _)) =>
                assert(path.nonEmpty)
                assert(path.last.startsWith("forAll @"), s"leaf name starts with 'forAll @', got: ${path.last}")
                assert(!ctx.peekWasGroup)
                succeed
            case Maybe.Absent =>
                fail("Expected a leaf result but got Absent")
        end match
        Future.successful(succeed)
    }

    // ── numSamples override ────────────────────────────────────────────────────────────────────

    "forall-registers-kyo-leaf: numSamples override is respected (5 samples suite passes)" in {
        TestRunner.runToFuture(classOf[PTFiveSampleSuite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected at least one leaf result")
            val (_, result) = allResults.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
            end match
        }
    }

end PropTest
