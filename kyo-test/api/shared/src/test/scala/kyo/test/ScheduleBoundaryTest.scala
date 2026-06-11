package kyo.test

import kyo.Duration
import kyo.Instant
import kyo.Maybe
import kyo.Schedule
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Boundary tests for [[kyo.Schedule]]: verifies the off-by-one boundary around `.take(n)` via `next(Instant)`.
  *
  * These tests verify the equivalent invariants that were previously tested via the deleted `kyo.test.Schedule.delayBefore(attempt: Int)`
  * API. The `kyo.Schedule.next(now: Instant)` API returns `Maybe[(Duration, Schedule)]` where `Absent` means "stop".
  */
// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class ScheduleBoundaryTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    private val now: Instant = Instant.fromJava(java.time.Instant.now())

    // ── Test 11: take(0) never retries ───────────────────────────────────────────────────────

    // Schedule.fixed(Duration.Zero).take(0) means 0 retries.
    // next(now) should immediately return Absent (no further iterations).
    "test-11: Schedule.fixed(Duration.Zero).take(0).next(now) == Maybe.empty" in {
        val s = Schedule.fixed(Duration.Zero).take(0)
        assert(s.next(now).isEmpty, s"expected Absent, got ${s.next(now)}")
        Future.successful(succeed)
    }

    // ── Test 12: take(3) allows first retry ──────────────────────────────────────────────────

    // Schedule.fixed(Duration.Zero).take(3) allows 3 retries.
    // next(now) should return Present((Duration.Zero, nextSchedule)).
    "test-12: Schedule.fixed(Duration.Zero).take(3).next(now) is Present" in {
        val s      = Schedule.fixed(Duration.Zero).take(3)
        val result = s.next(now)
        assert(result.isDefined, s"expected Present, got Absent")
        result match
            case Maybe.Present((d, _)) => assert(d == Duration.Zero, s"expected Duration.Zero delay, got $d")
            case Maybe.Absent          => fail("expected Present")
        end match
        Future.successful(succeed)
    }

    // ── Test 13: take(3) allows 3rd retry (exhaust all 3) ────────────────────────────────────

    // After consuming 2 retries from take(3), the 3rd next(now) should still return Present.
    "test-13: Schedule.fixed(Duration.Zero).take(3) allows exactly 3 next() calls before exhaustion" in {
        val (_, finalSchedule) = (1 to 3).foldLeft((Duration.Zero, Schedule.fixed(Duration.Zero).take(3): Schedule)) {
            case ((acc, s), i) =>
                s.next(now + acc) match
                    case Maybe.Present((delay, next)) =>
                        assert(true, s"expected Present on call $i"): Unit
                        (acc + delay, next)
                    case Maybe.Absent =>
                        fail(s"expected Present on call $i")
                        (acc, s)
        }
        // After 3 retries, must be exhausted
        assert(finalSchedule.next(now).isEmpty, s"expected Absent after 3 retries, got ${finalSchedule.next(now)}")
        Future.successful(succeed)
    }

    // ── Test 14: take(3) does NOT allow 4th retry ────────────────────────────────────────────

    // Consuming 3 next() calls from take(3) exhausts the schedule; 4th call returns Absent.
    "test-14: Schedule.fixed(Duration.Zero).take(3) returns Absent after 3 next() calls" in {
        val (_, finalSchedule) = (1 to 3).foldLeft((Duration.Zero, Schedule.fixed(Duration.Zero).take(3): Schedule)) {
            case ((acc, s), i) =>
                s.next(now + acc) match
                    case Maybe.Present((delay, next)) => (acc + delay, next)
                    case Maybe.Absent                 => fail(s"expected Present on call $i"); (acc, s)
        }
        assert(finalSchedule.next(now).isEmpty, s"expected Absent on 4th call, got ${finalSchedule.next(now)}")
        Future.successful(succeed)
    }

end ScheduleBoundaryTest
