package kyo.test

import kyo.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Verifies kyo.Schedule + kyo.Duration adoption across the public API. */
// ScalaTest bootstrap: this file tests kyo.Schedule and kyo.Duration adoption in the api module; migrating to kyo.test.Test[Future] would introduce a circular dependency on kyo-test-runner.
class ScheduleDurationTest extends AnyFunSuite with NonImplicitAssertions:

    // ── Leaf 1: TestBuilder.retrySchedule holds kyo.Schedule ─────────────────────────────────

    // TestBuilder("x").copy(retrySchedule = Maybe(kyo.Schedule.fixed(Duration.Zero).take(2))).retrySchedule.get.show
    // must contain both "Take" and "Fixed", confirming the Schedule.fixed(...).take(...) construction.
    test("leaf-1: retrySchedule built via kyo.Schedule.fixed(Duration.Zero).take(2): show mentions take and fixed") {
        val sb   = TestBuilder("x").copy(retrySchedule = Maybe(kyo.Schedule.fixed(Duration.Zero).take(2)))
        val show = sb.retrySchedule.get.show
        assert(show.contains("take"), s"expected 'take' in show but got: $show")
        assert(show.contains("fixed"), s"expected 'fixed' in show but got: $show")
    }

    // ── Leaf 3: TestResult.Passed.duration is kyo.Duration ───────────────────────────────────

    test("leaf-3: TestResult.Passed(50L.millis).duration == 50L.millis and type is kyo.Duration") {
        val result: TestResult.Passed = TestResult.Passed(50L.millis)
        val d: Duration               = result.duration
        assert(d == 50L.millis, s"expected 50L.millis but got $d")
    }

    // ── Leaf 4: TestResult.TimedOut.limit is kyo.Duration ────────────────────────────────────

    test("leaf-4: TestResult.TimedOut(1.second).limit == 1.second and type is kyo.Duration") {
        val result: TestResult.TimedOut = TestResult.TimedOut(1.second)
        val d: Duration                 = result.limit
        assert(d == 1.second, s"expected 1.second but got $d")
    }

    // ── Leaf 5: SuiteReport.duration is kyo.Duration ─────────────────────────────────────────

    test("leaf-5: SuiteReport(\"S\", Chunk.empty, 30L.millis).duration == 30L.millis") {
        val sr: SuiteReport = SuiteReport("S", Chunk.empty, 30L.millis)
        val d: Duration     = sr.duration
        assert(d == 30L.millis, s"expected 30L.millis but got $d")
    }

    // ── Leaf 6: TestReport.totalDuration sums SuiteReport durations ──────────────────────────

    test("leaf-6: TestReport with 10L.millis + 20L.millis suites has totalDuration == 30L.millis") {
        val report          = TestReport(Chunk(SuiteReport("S", Chunk.empty, 10L.millis), SuiteReport("T", Chunk.empty, 20L.millis)))
        val total: Duration = report.totalDuration
        assert(total == 30L.millis, s"expected 30L.millis but got $total")
    }

    // ── Leaf 7: TestBuilder.timeout is Maybe[kyo.Duration] ───────────────────────────────────

    test("leaf-7: TestBuilder(x).copy(timeout = Maybe(5L.seconds)).timeout == Maybe(5L.seconds)") {
        val sb                 = TestBuilder("x").copy(timeout = Maybe(5L.seconds))
        val t: Maybe[Duration] = sb.timeout
        assert(t == Maybe(5L.seconds), s"expected Maybe(5L.seconds) but got $t")
    }

end ScheduleDurationTest
