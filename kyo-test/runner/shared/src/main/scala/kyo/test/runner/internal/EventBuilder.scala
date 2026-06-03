package kyo.test.runner.internal

import kyo.Chunk
import kyo.test.TestResult
import sbt.testing.Event
import sbt.testing.Fingerprint
import sbt.testing.OptionalThrowable
import sbt.testing.Selector
import sbt.testing.Status
import sbt.testing.TaskDef
import sbt.testing.TestSelector

/** Constructs [[sbt.testing.Event]] values from kyo-test [[TestResult]] values.
  *
  * Each leaf produces exactly one Event. sbt uses the Event's [[Status]] to determine whether the build is red (`Failure`) or green
  * (`Success`). IDEs use the [[TestSelector]] to provide per-leaf re-run buttons.
  *
  * Status mapping:
  *   - [[TestResult.Passed]] → [[Status.Success]]
  *   - [[TestResult.Failed]] → [[Status.Failure]]
  *   - [[TestResult.Cancelled]] → [[Status.Canceled]] (an unmet precondition, e.g. wrong platform; NOT a build failure)
  *   - [[TestResult.Pending]] → [[Status.Skipped]]
  *   - [[TestResult.Ignored]] → [[Status.Skipped]]
  *   - [[TestResult.TimedOut]] → [[Status.Failure]]
  *   - [[TestResult.Skipped]] → [[Status.Skipped]]
  */
private[internal] object EventBuilder:

    def build(
        taskDef: TaskDef,
        path: Chunk[String],
        result: TestResult
    ): Event = new Event:

        def fullyQualifiedName(): String   = taskDef.fullyQualifiedName()
        def fingerprint(): Fingerprint     = taskDef.fingerprint()
        def selector(): Selector           = new TestSelector(path.mkString("/"))
        def status(): Status               = toStatus(result)
        def throwable(): OptionalThrowable = toThrowable(result)
        def duration(): Long               = toDurationMs(result)

    end build

    private def toStatus(r: TestResult): Status = r match
        case _: TestResult.Passed    => Status.Success
        case _: TestResult.Failed    => Status.Failure
        case _: TestResult.Cancelled => Status.Canceled
        case _: TestResult.Pending   => Status.Skipped
        case TestResult.Ignored      => Status.Skipped
        case _: TestResult.TimedOut  => Status.Failure
        case _: TestResult.Skipped   => Status.Skipped

    private def toThrowable(r: TestResult): OptionalThrowable = r match
        case TestResult.Failed(diagram, maybeCause, _, _) =>
            maybeCause.fold(
                new OptionalThrowable(new AssertionError(diagram))
            )(t => new OptionalThrowable(t))
        case TestResult.Cancelled(reason, _) =>
            new OptionalThrowable(new RuntimeException(reason))
        case TestResult.TimedOut(limit) =>
            new OptionalThrowable(
                new java.util.concurrent.TimeoutException(s"Test timed out after $limit")
            )
        case _ => new OptionalThrowable()

    private def toDurationMs(r: TestResult): Long = r match
        case TestResult.Passed(d, _)       => d.toMillis
        case TestResult.Failed(_, _, d, _) => d.toMillis
        case TestResult.Cancelled(_, d)    => d.toMillis
        case TestResult.TimedOut(limit)    => limit.toMillis
        case _                             => -1L

end EventBuilder
