package kyo.test.runner.internal

import kyo.test.RunConfig
import kyo.test.TestReport
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.Task
import sbt.testing.TaskDef
import scala.concurrent.ExecutionContext
import scala.scalajs.reflect.Reflect

/** Scala.js [[Task]] implementation that runs one kyo-test suite.
  *
  * Mirrors [[SbtTask]] on JVM but avoids `Await.result`, which is not available on Scala.js. Instead it registers an `onComplete` on the
  * run Future; the Scala.js test runner drains microtasks between task invocations so the callback fires before the next task begins.
  *
  * JS-specific quirk: `parallelism > 1` is a no-op (JS is single-threaded). A warning is emitted to stderr when this condition is detected
  * so the user knows the setting was ignored.
  *
  * `execute` returns `Array.empty[Task]` immediately and fires events asynchronously via `onComplete`. The Scala.js sbt test runner is
  * designed for this: it awaits the completion of all scheduled futures before proceeding.
  */
final private[internal] class JsTask(
    val taskDef: TaskDef,
    baseConfig: RunConfig,
    testClassLoader: ClassLoader,
    results: scala.collection.mutable.ListBuffer[TestReport]
) extends Task:

    def tags(): Array[String] = Array.empty

    def execute(
        eventHandler: EventHandler,
        loggers: Array[Logger]
    ): Array[Task] =
        val _ = runSuite(eventHandler)
        Array.empty[Task]
    end execute

    /** Scala.js-specific async execute: runs the suite and calls `continuation` when done.
      *
      * The Scala.js test bridge (HTMLRunner / NodeRunner) calls this 3-argument overload instead of the 2-argument one. The `continuation`
      * receives the array of new tasks produced (always empty for kyo-test); calling it signals to the bridge that this task has finished.
      *
      * The return type of the continuation is `BoxedUnit` to match the [[sbt.testing.Task]] sjsir interface.
      */
    def execute(
        eventHandler: EventHandler,
        loggers: Array[Logger],
        continuation: Array[Task] => scala.runtime.BoxedUnit
    ): Unit =
        // sbt-edge EC: the kyo scheduler exposed as an ExecutionContext (MacrotaskExecutor on JS via the cross-platform Scheduler).
        given ExecutionContext = kyo.scheduler.Scheduler.get.asExecutionContext
        runSuite(eventHandler).onComplete { _ =>
            continuation(Array.empty[Task])
        }
    end execute

    private def runSuite(eventHandler: EventHandler): scala.concurrent.Future[Unit] =
        val needsCap = baseConfig.parallelism > 1
        if needsCap then
            java.lang.System.err.println(
                s"[kyo-test] WARNING: parallelism=${baseConfig.parallelism} is not supported on Scala.js (single-threaded). " +
                    "Tests will run sequentially."
            )
        end if

        // Cap parallelism to 1 for JS: even if the config says > 1, we run sequentially.
        val jsConfig = if needsCap then baseConfig.copy(parallelism = 1) else baseConfig

        // sbt-edge EC: the kyo scheduler exposed as an ExecutionContext (MacrotaskExecutor on JS via the cross-platform Scheduler).
        given ExecutionContext = kyo.scheduler.Scheduler.get.asExecutionContext

        // On Scala.js, Class.forName(String) is not available. Use the Scala.js Reflect API to obtain
        // the runtime Class object for the test suite. The class is included in the reflection registry
        // because kyo.test.Test extends KyoTestReflect which carries @EnableReflectiveInstantiation.
        val fqn = taskDef.fullyQualifiedName()
        val runtimeClass = Reflect
            .lookupInstantiatableClass(fqn)
            .getOrElse(
                throw new ClassNotFoundException(
                    s"kyo-test: cannot find reflectively instantiatable class '$fqn'. " +
                        "Make sure it is a top-level subclass of kyo.test.Test."
                )
            )
            .runtimeClass

        // Unsafe: the SuiteFingerprint scanner already confirmed the class extends TestBase
        val nextClass = runtimeClass.asInstanceOf[Class[? <: kyo.test.internal.TestBase[?]]]
        // Unsafe: Frame.internal at the sbt edge. sbt's Task.execute has no caller Frame to propagate, and
        // JsTask.scala is not a Frame-deriving file (only *Test/*Bench.scala are). This is the sanctioned
        // sbt-edge boundary (steering.md), matching the AllowUnsafe already used inside runToFuture.
        val future = kyo.test.runner.TestRunner.runToFuture(nextClass, jsConfig)(using kyo.Frame.internal)

        future.map { report =>
            results += report
            emitEvents(report, eventHandler)
        }.recover { case t =>
            val syntheticReport = syntheticFailReport(t)
            results += syntheticReport
            emitEvents(syntheticReport, eventHandler)
        }
    end runSuite

    private def emitEvents(report: TestReport, handler: EventHandler): Unit =
        for
            sr             <- report.suiteReports.iterator
            (path, result) <- sr.leafResults.iterator
        do
            handler.handle(EventBuilder.build(taskDef, path, result))

    private def syntheticFailReport(t: Throwable): TestReport =
        import kyo.{Chunk, Duration, Maybe}
        import kyo.test.{SuiteReport, TestResult}
        val sr = kyo.test.SuiteReport(
            taskDef.fullyQualifiedName(),
            Chunk((Chunk("<constructor>"), TestResult.Failed(t.toString, Maybe(t), Duration.Zero))),
            Duration.Zero
        )
        kyo.test.TestReport(Chunk(sr))
    end syntheticFailReport

end JsTask
