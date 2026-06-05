package kyo.test.runner.internal

import kyo.*
import kyo.test.TestResult
import sbt.testing.Status
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.TaskDef

/** Tests for [[EventBuilder.build]]: Status mapping coverage.
  *
  * Verifies that every [[TestResult]] subclass maps to the correct [[sbt.testing.Status]].
  */
class EventBuilderTest extends kyo.test.Test[Any]:

    private val fingerprint: SubclassFingerprint = new SubclassFingerprint:
        def isModule(): Boolean                = false
        def superclassName(): String           = "kyo.test.KyoTestSuite"
        def requireNoArgConstructor(): Boolean = true

    private val taskDef: TaskDef =
        new TaskDef("kyo.test.FakeSuite", fingerprint, false, Array(new SuiteSelector))

    private val oneMilli: Duration = 1L.millis

    // Cache enum values as vals to avoid macro instrumentation trying to load sbt/testing/Status$
    private val statusSuccess: Status  = Status.Success
    private val statusFailure: Status  = Status.Failure
    private val statusSkipped: Status  = Status.Skipped
    private val statusCanceled: Status = Status.Canceled

    // ── Test 6: Passed → Status.Success ──────────────────────────────────────────────────────────

    "test-6: Passed produces Status.Success" in {
        val event = EventBuilder.build(taskDef, Chunk("suite", "leaf"), TestResult.Passed(oneMilli))
        val s     = event.status()
        assert(s eq statusSuccess): Unit
    }

    // ── Test 7: Failed → Status.Failure ──────────────────────────────────────────────────────────

    "test-7: Failed produces Status.Failure" in {
        import kyo.Maybe
        val event = EventBuilder.build(
            taskDef,
            Chunk("leaf"),
            TestResult.Failed("x == y", Maybe.empty, oneMilli)
        )
        val s = event.status()
        assert(s eq statusFailure): Unit
    }

    // ── Test 8: Cancelled → Status.Canceled ──────────────────────────────────────────────────────
    // A cancellation (an unmet precondition, e.g. a platform/`assume` skip) is NOT a build failure; sbt
    // reds the build only on Status.Error/Failure. It maps to the dedicated Status.Canceled.

    "test-8: Cancelled produces Status.Canceled" in {
        val event = EventBuilder.build(taskDef, Chunk("leaf"), TestResult.Cancelled("interrupted", oneMilli))
        val s     = event.status()
        assert(s eq statusCanceled): Unit
    }

    // ── Test 9: Pending → Status.Skipped ─────────────────────────────────────────────────────────

    "test-9: Pending produces Status.Skipped" in {
        val event = EventBuilder.build(taskDef, Chunk("leaf"), TestResult.Pending("not yet"))
        val s     = event.status()
        assert(s eq statusSkipped): Unit
    }

    // ── Test 10: Ignored → Status.Skipped ────────────────────────────────────────────────────────

    "test-10: Ignored produces Status.Skipped" in {
        val event = EventBuilder.build(taskDef, Chunk("leaf"), TestResult.Ignored(""))
        val s     = event.status()
        assert(s eq statusSkipped): Unit
    }

    // ── Test 11: TimedOut → Status.Failure ───────────────────────────────────────────────────────

    "test-11: TimedOut produces Status.Failure" in {
        val event = EventBuilder.build(taskDef, Chunk("leaf"), TestResult.TimedOut(5L.seconds))
        val s     = event.status()
        assert(s eq statusFailure): Unit
    }

    // ── Test 12: Skipped → Status.Skipped ────────────────────────────────────────────────────────

    "test-12: Skipped produces Status.Skipped" in {
        val event = EventBuilder.build(taskDef, Chunk("leaf"), TestResult.Skipped("filtered"))
        val s     = event.status()
        assert(s eq statusSkipped): Unit
    }

    // ── Test 10: toThrowable for Cancelled returns non-null Throwable with reason (M34) ─────────────

    "phase8-test-10: EventBuilder.toThrowable(Cancelled) returns non-null Throwable with message containing reason" in {
        val event = EventBuilder.build(taskDef, Chunk("leaf"), TestResult.Cancelled("reason", oneMilli))
        val ot    = event.throwable()
        assert(ot.isDefined, "Expected OptionalThrowable.isDefined==true for Cancelled result")
        val t = ot.get()
        assert(t != null, "Expected non-null Throwable for Cancelled result")
        assert(
            t.getMessage != null && t.getMessage.contains("reason"),
            s"Expected getMessage to contain 'reason', got: ${t.getMessage}"
        )
    }

end EventBuilderTest
