package kyo.test.runner.internal

import kyo.test.RunConfig
import kyo.test.TestReport
import kyo.test.TestResult
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.Task
import sbt.testing.TaskDef
import scala.concurrent.Await
// Java-interop: scala.concurrent.duration.Duration is required by Await.result; this is the only permissible use of scala.concurrent.duration in this file.
import scala.concurrent.duration.Duration

/** sbt [[Task]] implementation that runs one kyo-test suite.
  *
  * `execute` is called by sbt, potentially concurrently for different suites. It:
  *   1. Loads the suite class from `testClassLoader`.
  *   2. Calls `TestRunner.run` to execute all leaves.
  *   3. Blocks via `Await.result` until the run completes (sbt's task model is synchronous).
  *   4. Emits one [[sbt.testing.Event]] per leaf to `eventHandler`.
  *   5. Adds the [[TestReport]] to the shared `results` queue for `done()`.
  *
  * Exceptions escaping `TestRunner.run` propagate to sbt, which marks the task as `Error`. Per-leaf failures are handled internally by the
  * runner and surfaced as [[TestResult.Failed]] events.
  */
final private[internal] class SbtTask(
    val taskDef: TaskDef,
    baseConfig: RunConfig,
    testClassLoader: ClassLoader,
    results: java.util.concurrent.ConcurrentLinkedQueue[TestReport],
    leakCheck: Boolean
) extends Task:

    def tags(): Array[String] = Array.empty

    def execute(
        eventHandler: EventHandler,
        loggers: Array[Logger]
    ): Array[Task] =
        // This call runs on the sbt ForkMain pool thread carrying the task; record it as harness infrastructure so the
        // end-of-run thread probe never mistakes a parked sbt worker for a leaked test thread (see LeakCheck.registerCarrierThread).
        if leakCheck then LeakCheck.registerCarrierThread()
        val report = runSuite()
        results.add(report)
        emitEvents(report, eventHandler)
        Array.empty[Task]
    end execute

    // Unsafe: Reflective bridge. sbt's ClassLoader gives us a raw Class[?]; we coerce it to the parameterized form expected by the runner.
    // The erasure is safe because sbt's SuiteFingerprint scanner already verified the suite extends TestBase.
    private def runSuite(): TestReport =
        val raw = testClassLoader.loadClass(taskDef.fullyQualifiedName())
        // Unsafe: the SuiteFingerprint match confirms the class extends TestBase
        val nextClass = raw.asInstanceOf[Class[? <: kyo.test.internal.TestBase[?]]]
        // Unsafe: Frame.internal at the sbt edge. sbt's Task.execute has no caller Frame to propagate, and
        // SbtTask.scala is not a Frame-deriving file (only *Test/*Bench.scala are). This is the sanctioned
        // sbt-edge boundary (steering.md), matching the AllowUnsafe already used inside runToFuture.
        val future = kyo.test.runner.TestRunner.runToFuture(nextClass, baseConfig)(using kyo.Frame.internal)
        Await.result(future, Duration.Inf)
    end runSuite

    private def emitEvents(report: TestReport, handler: EventHandler): Unit =
        for
            sr             <- report.suiteReports.iterator
            (path, result) <- sr.leafResults.iterator
        do
            handler.handle(EventBuilder.build(taskDef, path, result))

end SbtTask
