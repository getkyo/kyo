package kyo.test

import kyo.Duration
import kyo.Maybe
import kyo.Schedule
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class TestBuilderTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    "TestBuilder" - {
        "apply: defaults are correct" in {
            val b = TestBuilder("foo")
            assert(b.name == "foo")
            assert(b.tags == Set.empty)
            assert(!b.focus)
            assert(!b.ignore)
            assert(b.pending == Maybe.empty)
            assert(b.timeout == Maybe.empty)
            assert(b.retrySchedule.isEmpty)
            assert(b.repeat == 1)
            assert(b.onlyIf == Maybe.empty)
            assert(!b.tags.contains("slow"))
            Future.successful(succeed)
        }

        "copy semantics work" in {
            val a      = TestBuilder("foo")
            val retryS = kyo.Schedule.fixed(Duration.Zero).take(3)
            val b      = a.copy(retrySchedule = Maybe(retryS), tags = Set("slow"))
            assert(b.name == "foo")
            assert(b.retrySchedule.contains(retryS))
            assert(b.tags == Set("slow"))
            // original unchanged
            assert(a.retrySchedule.isEmpty)
            assert(a.tags == Set.empty)
            Future.successful(succeed)
        }
    }
end TestBuilderTest
