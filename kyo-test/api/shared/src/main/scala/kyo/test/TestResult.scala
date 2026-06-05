package kyo.test

import kyo.Duration
import kyo.Maybe

/** The outcome of running a single leaf test.
  *
  * Every leaf that the runner executes (or deliberately skips) produces exactly one `TestResult`. Reporters and the post-run [[TestReport]]
  * derive their summary counters from these values.
  *
  * @see
  *   [[kyo.test.TestReport]] for aggregate counters derived from a collection of results
  * @see
  *   [[kyo.test.TestReporter.onLeafComplete]] where each result is dispatched to reporters
  * @see
  *   [[kyo.test.TestBuilder]] for decorators (.retry, .pending, .ignore) that determine which variant is produced
  * @see
  *   [[kyo.test.AssertionFailed]] the exception that produces a [[TestResult.Failed]] leaf
  */
enum TestResult derives CanEqual:
    /** The leaf ran to completion without any assertion failure.
      *
      * @param duration
      *   wall-clock time for the successful run
      * @param attempts
      *   how many attempts it took to pass; 1 means it passed on the first try. Values > 1 indicate a flaky leaf that the `.flaky` /
      *   `.retry(schedule)` decorator rescued. Always 1 when no retry decorator is in scope.
      */
    case Passed(duration: Duration, attempts: Int = 1)

    /** The leaf threw an [[AssertionFailed]] or an unexpected exception.
      *
      * @param diagram
      *   the power-assert diagram; empty string when the failure is an unexpected exception
      * @param cause
      *   the root throwable; `Absent` for pure assertion mismatches
      * @param duration
      *   elapsed time before the failure
      * @param attempts
      *   how many attempts were made before giving up; 1 means it failed on the first try. Values > 1 indicate exhaustion of a retry
      *   schedule. Always 1 when no retry decorator is in scope.
      */
    case Failed(diagram: String, cause: Maybe[Throwable], duration: Duration, attempts: Int = 1)

    /** The leaf was interrupted before it could complete (e.g., setup failed).
      *
      * @param reason
      *   a human-readable description of why the leaf was cancelled
      * @param duration
      *   elapsed time before cancellation
      */
    case Cancelled(reason: String, duration: Duration)

    /** The leaf was held pending by `.pendingUntilFixed`: the body ran and still failed, so it is reported pending (not failed) until
      * the underlying issue is fixed.
      */
    case Pending(reason: String)

    /** The leaf was marked `.ignore` (optionally `.ignore(reason)`) and its body was not executed. */
    case Ignored(reason: String)

    /** The leaf's body exceeded the configured timeout limit. */
    case TimedOut(limit: Duration)

    /** The leaf was filtered out (e.g., `only(false)` or a focus-filter on another leaf). */
    case Skipped(reason: String)
end TestResult
