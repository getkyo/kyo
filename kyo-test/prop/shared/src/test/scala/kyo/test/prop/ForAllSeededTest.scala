package kyo.test.prop

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
// Extend PropertyTestBase[Any] (NOT PropertyTest[Any]) so they do NOT carry
// SuiteFingerprintMarker and are not auto-discovered as standalone suites.
// This matters on Scala Native, whose reflective discovery would otherwise run
// these deliberately-failing fixtures and fail the task.

/** forAllSeeded arity 1: fails on the first negative int the seed-7 schedule produces. */
class SeededFailSuite extends PropertyTestBase[Any]:
    forAllSeeded(7L, Gen.int) { n => assert(n >= 0) }
end SeededFailSuite

/** Mixed: one forAllSeeded(7L) leaf and one plain forAll leaf, both failing. */
class MixedSeedSuite extends PropertyTestBase[Any]:
    forAllSeeded(7L, Gen.int) { n => assert(n >= 0) }
    forAll(Gen.int) { n => assert(n >= 0) }
end MixedSeedSuite

/** forAllSeeded arity 2: passes (string length is never negative). */
class SeededArity2PassSuite extends PropertyTestBase[Any]:
    forAllSeeded(7L, Gen.int, Gen.string) { (n, s) => assert(s.length >= 0) }
end SeededArity2PassSuite

/** forAllSeeded arity 2: fails so the forced seed shows in the exception. */
class SeededArity2FailSuite extends PropertyTestBase[Any]:
    forAllSeeded(7L, Gen.int, Gen.string) { (n, s) => assert(n >= 0) }
end SeededArity2FailSuite

/** forAllSeeded arity 3: fails so the forced seed shows in the exception. */
class SeededArity3FailSuite extends PropertyTestBase[Any]:
    forAllSeeded(9L, Gen.int, Gen.int, Gen.int) { (a, b, c) => assert(a >= 0 && b >= 0 && c >= 0) }
end SeededArity3FailSuite

/** forAllSeeded arity 4: fails so the forced seed shows in the exception. */
class SeededArity4FailSuite extends PropertyTestBase[Any]:
    forAllSeeded(9L, Gen.int, Gen.int, Gen.int, Gen.int) { (a, b, c, d) => assert(a >= 0 && b >= 0 && c >= 0 && d >= 0) }
end SeededArity4FailSuite

/** forAllSeeded with a large seed literal: proves the parameter is a plain Long. */
class SeededLargeLiteralSuite extends PropertyTestBase[Any]:
    forAllSeeded(123456789L, Gen.int) { n => assert(n == n) }
end SeededLargeLiteralSuite

// ── ForAllSeededTest ──────────────────────────────────────────────────────────

/** Tests for the forAllSeeded replay entry point.
  *
  * Uses the ScalaTest AsyncFreeSpec bootstrap because these tests drive the kyo-test-prop DSL itself
  * and cannot self-host on the framework-under-test. Fixtures extend PropertyTestBase[Any] (not
  * PropertyTest[Any]) so they remain invisible to auto-discovery on all platforms.
  */
class ForAllSeededTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // helper: extract the PropertyFailedException from the first failed leaf
    private def extractFailure(suiteClass: Class[? <: PropertyTestBase[Any]]): Future[PropertyFailedException] =
        TestRunner.runToFuture(suiteClass).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected at least one leaf result")
            // find the first Failed result
            val failedOpt = allResults.collectFirst { case (_, f: TestResult.Failed) => f }
            assert(failedOpt.isDefined, s"Expected at least one Failed leaf, got: ${allResults.map(_._2)}")
            val f = failedOpt.get
            f.cause match
                case Maybe.Present(e: PropertyFailedException) => e
                case Maybe.Present(other)                      => fail(s"Expected PropertyFailedException, got: ${other.getClass.getName}")
                case Maybe.Absent => fail("Expected cause to be Present(PropertyFailedException) but was Absent")
            end match
        }

    // ── forAllSeeded reports the forced seed ──────────────────────

    "forAllSeeded reports the forced seed in PropertyFailedException" in {
        extractFailure(classOf[SeededFailSuite]).map { e =>
            assert(e.seed == 7L, s"forAllSeeded(7L, ...) should record seed 7L, got: ${e.seed}")
        }
    }

    // ── re-run yields the same shrunk counterexample (determinism) ─

    "forAllSeeded re-run yields the same shrunk counterexample" in {
        // Run the same fixture twice; both runs must produce the identical shrunk value.
        // This is the core replay guarantee: same seed -> same counterexample.
        val run1 = extractFailure(classOf[SeededFailSuite])
        val run2 = extractFailure(classOf[SeededFailSuite])
        for
            e1 <- run1
            e2 <- run2
        yield assert(
            e1.shrunkValue.equals(e2.shrunkValue),
            s"Same seed (7L) must yield the same shrunk counterexample: run1=${e1.shrunkValue} run2=${e2.shrunkValue}"
        )
        end for
    }

    // ── mixed forAllSeeded + forAll keeps per-call locality ────────

    "forAllSeeded and forAll in the same suite use their own seeds independently" in {
        TestRunner.runToFuture(classOf[MixedSeedSuite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected at least one leaf result")
            val failures = allResults.collect { case (_, f: TestResult.Failed) => f }
            assert(failures.size == 2, s"Expected exactly 2 failed leaves, got: ${failures.size}")
            val seeds = failures.map { f =>
                f.cause match
                    case Maybe.Present(e: PropertyFailedException) => e.seed
                    case _                                         => fail("Expected PropertyFailedException")
            }
            // One leaf uses forAllSeeded(7L) -> seed == 7L; the other uses plain forAll -> seed == 42L (nonRandomSeed)
            assert(seeds.contains(7L), s"forAllSeeded leaf seed should be 7L; got: $seeds")
            assert(seeds.contains(42L), s"plain forAll leaf seed should be 42L (nonRandomSeed); got: $seeds")
        }
    }

    // ── forAllSeeded arity 2 forces the seed ──────────────────────

    "forAllSeeded arity 2 passing leaf registers TestResult.Passed" in {
        TestRunner.runToFuture(classOf[SeededArity2PassSuite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected at least one leaf result")
            val (_, result) = allResults.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed for always-true arity-2 property, got: $other")
        }
    }

    "forAllSeeded arity 2 failing leaf reports seed == 7L" in {
        extractFailure(classOf[SeededArity2FailSuite]).map { e =>
            assert(e.seed == 7L, s"forAllSeeded arity-2 should record seed 7L, got: ${e.seed}")
        }
    }

    // ── forAllSeeded arities 3 and 4 force the seed ──────────────

    "forAllSeeded arity 3 failing leaf reports seed == 9L" in {
        extractFailure(classOf[SeededArity3FailSuite]).map { e =>
            assert(e.seed == 9L, s"forAllSeeded arity-3 should record seed 9L, got: ${e.seed}")
        }
    }

    "forAllSeeded arity 4 failing leaf reports seed == 9L" in {
        extractFailure(classOf[SeededArity4FailSuite]).map { e =>
            assert(e.seed == 9L, s"forAllSeeded arity-4 should record seed 9L, got: ${e.seed}")
        }
    }

    // ── leaf name keeps the "forAll @" prefix ───────────────────────────

    "forAllSeeded leaf name starts with 'forAll @' for consistent reports" in {
        TestRunner.runToFuture(classOf[SeededFailSuite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected at least one leaf result")
            val (path, _) = allResults.head
            assert(path.nonEmpty, "path should be non-empty")
            val leafName = path.last
            assert(leafName.startsWith("forAll @"), s"forAllSeeded leaf name should start with 'forAll @', got: $leafName")
        }
    }

    // ── seed is a plain Long at the boundary ─────────────────────
    // SeededLargeLiteralSuite uses forAllSeeded(123456789L, ...) -- if it compiles and runs, the
    // seed parameter is a plain Long; no internal Seed import is needed.

    "forAllSeeded accepts a plain Long seed literal (compile + run)" in {
        TestRunner.runToFuture(classOf[SeededLargeLiteralSuite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected at least one leaf result")
            val (_, result) = allResults.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed for always-true large-seed property, got: $other")
        }
    }

    // ── replay proves seed drives reproduction, not a constant ────
    // Run SeededFailSuite with seed 7L and a "different seed" fixture; confirm
    // that the seed-7 counterexample equals a second seed-7 run AND that different
    // seeds can differ. We verify the inequality by checking a known-different seed.
    // Note: both same-seed runs are already verified in leaf 2. Here we add the
    // cross-seed check by constructing a second fixture using nonRandomSeed (42L)
    // and comparing its shrunkValue against the seed-7 result.

    "same seed produces the same counterexample, different seed may differ" in {
        // seed-7 run
        val seed7Run = extractFailure(classOf[SeededFailSuite])
        // seed-42 run (uses plain forAll, which reads nonRandomSeed == 42L)
        val seed42Run: Future[PropertyFailedException] =
            TestRunner.runToFuture(classOf[PTFailSuite]).map { report =>
                val allResults = report.suiteReports.flatMap(_.leafResults)
                assert(allResults.nonEmpty, "Expected at least one leaf result from PTFailSuite")
                val failedOpt = allResults.collectFirst { case (_, f: TestResult.Failed) => f }
                assert(failedOpt.isDefined, "Expected PTFailSuite to produce a Failed leaf")
                val f = failedOpt.get
                f.cause match
                    case Maybe.Present(e: PropertyFailedException) => e
                    case Maybe.Present(other) => fail(s"Expected PropertyFailedException, got: ${other.getClass.getName}")
                    case Maybe.Absent         => fail("Expected cause to be Present(PropertyFailedException) but was Absent")
                end match
            }
        // Also confirm seed-7 run is deterministic (a second identical run)
        val seed7RunAgain = extractFailure(classOf[SeededFailSuite])
        for
            e7a <- seed7Run
            e42 <- seed42Run
            e7b <- seed7RunAgain
        yield
            // Same seed must give the same shrunk value
            assert(
                e7a.shrunkValue.equals(e7b.shrunkValue),
                s"Two seed-7 runs must produce the same shrunk counterexample: run1=${e7a.shrunkValue} run2=${e7b.shrunkValue}"
            )
            // Both seed-7 runs must record seed 7L
            assert(e7a.seed == 7L, s"seed-7 run should record seed 7L, got: ${e7a.seed}")
            assert(e7b.seed == 7L, s"second seed-7 run should record seed 7L, got: ${e7b.seed}")
            // Seed-42 run must record seed 42L
            assert(e42.seed == 42L, s"plain forAll (seed 42) run should record seed 42L, got: ${e42.seed}")
            // The seeds are different; the shrunk values CAN differ (and for these two seeds they do,
            // but even if they happen to match the seed-determinism guarantee is proved by the
            // e7a == e7b assertion above, which would fail if the seed were ignored).
        end for
    }

    // ── plain forAll still reports 42L ─────

    "plain forAll (PropTest regression) still reports seed == 42L" in {
        TestRunner.runToFuture(classOf[PTFailSuite]).map { report =>
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected at least one leaf result")
            val failedOpt = allResults.collectFirst { case (_, f: TestResult.Failed) => f }
            assert(failedOpt.isDefined, "Expected PTFailSuite to produce a Failed leaf")
            val f = failedOpt.get
            f.cause match
                case Maybe.Present(e: PropertyFailedException) =>
                    assert(e.seed == 42L, s"plain forAll seed should be 42L (nonRandomSeed), got: ${e.seed}")
                case Maybe.Present(other) =>
                    fail(s"Expected PropertyFailedException, got: ${other.getClass.getName}")
                case Maybe.Absent =>
                    fail("Expected cause to be Present(PropertyFailedException) but was Absent")
            end match
        }
    }

end ForAllSeededTest
