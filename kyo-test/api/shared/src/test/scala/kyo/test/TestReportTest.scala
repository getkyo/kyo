package kyo.test

import kyo.Chunk
import kyo.Duration
import kyo.Maybe
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Verifies [[TestReport.Counts]] and the single-fold `lazy val counts` on [[TestReport]].
  */
// ScalaTest bootstrap: this file tests TestReport.Counts and the single-fold lazy val in the api module; cannot self-host since kyo-test-api has no KyoTestPlugin.
class TestReportTest extends AnyFunSuite with NonImplicitAssertions:

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private def singleLeaf(name: String, result: TestResult): TestReport =
        TestReport(Chunk(SuiteReport("S", Chunk((Chunk(name), result)), Duration.Zero)))

    // ── Test 1: single Passed leaf ────────────────────────────────────────────────────────────────

    test("test-1: single Passed leaf yields counts.passed == 1") {
        val report = singleLeaf("a", TestResult.Passed(Duration.Zero))
        assert(report.counts.passed == 1)
    }

    // ── Test 2: single Failed leaf ────────────────────────────────────────────────────────────────

    test("test-2: single Failed leaf yields counts.failed == 1") {
        val report = singleLeaf("a", TestResult.Failed("", Maybe.empty, Duration.Zero))
        assert(report.counts.failed == 1)
    }

    // ── Test 3: one of each of the 7 result kinds ────────────────────────────────────────────────

    test("test-3: one of each 7 result kinds yields counts.total == 7 and each field == 1") {
        val leaves = Chunk(
            (Chunk("p"), TestResult.Passed(Duration.Zero)),
            (Chunk("f"), TestResult.Failed("", Maybe.empty, Duration.Zero)),
            (Chunk("c"), TestResult.Cancelled("reason", Duration.Zero)),
            (Chunk("n"), TestResult.Pending("reason")),
            (Chunk("i"), TestResult.Ignored),
            (Chunk("t"), TestResult.TimedOut(Duration.Zero)),
            (Chunk("s"), TestResult.Skipped("reason"))
        )
        val report = TestReport(Chunk(SuiteReport("S", leaves, Duration.Zero)))
        val counts = report.counts
        assert(counts.total == 7)
        assert(counts.passed == 1)
        assert(counts.failed == 1)
        assert(counts.cancelled == 1)
        assert(counts.pending == 1)
        assert(counts.ignored == 1)
        assert(counts.timedOut == 1)
        assert(counts.skipped == 1)
    }

    // ── Test 4: lazy val is cached (referential identity after multiple accessor calls) ──────────

    test("test-4: counts lazy val is cached; eq identity holds after accessing passed, failed, timedOut") {
        val report = singleLeaf("a", TestResult.Passed(Duration.Zero))
        // Force three distinct accessors so the lazy val is definitely initialised.
        val _ = report.passed
        val _ = report.failed
        val _ = report.timedOut
        // Both sides must be the exact same object reference.
        assert(report.counts eq report.counts)
    }

    // ── Test 5: default Counts has total == 0 ────────────────────────────────────────────────────

    test("test-5: TestReport.Counts() default constructor has total == 0") {
        assert(TestReport.Counts().total == 0)
    }

end TestReportTest
