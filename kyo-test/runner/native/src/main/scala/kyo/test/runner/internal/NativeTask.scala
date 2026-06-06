package kyo.test.runner.internal

import kyo.test.RunConfig
import kyo.test.TestReport
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.Task
import sbt.testing.TaskDef
import scala.concurrent.Await
// Java-interop: scala.concurrent.duration.Duration is required by Await.result; this is the only permissible use of scala.concurrent.duration in this file.
import scala.concurrent.duration.Duration
import scala.scalanative.reflect.Reflect

/** Scala Native [[Task]] implementation that runs one kyo-test suite.
  *
  * Mirrors [[SbtTask]] on JVM: uses `Await.result` to block until the suite completes (Scala Native supports real threads, so this is
  * safe).
  *
  * Native-specific note: parallelism is kept at 1 per the plan (our test fixture is single-threaded for simplicity). A warning is emitted
  * to stderr when `parallelism > 1` is configured, and the value is silently capped to 1.
  *
  * Class lookup uses the Scala Native Reflect API (`Reflect.lookupInstantiatableClass`). The `ScalaNativeClassLoader` provided by the test
  * bridge does NOT support `loadClass` (it throws a "dummy" error); we must use the Reflect API instead. The class is in the reflection
  * registry because [[kyo.test.Test]] extends [[kyo.test.internal.KyoTestReflect]] which carries
  * `@scala.scalanative.reflect.annotation.EnableReflectiveInstantiation`.
  */
final private[internal] class NativeTask(
    val taskDef: TaskDef,
    baseConfig: RunConfig,
    testClassLoader: ClassLoader,
    results: java.util.concurrent.ConcurrentLinkedQueue[TestReport]
) extends Task:

    def tags(): Array[String] = Array.empty

    def execute(
        eventHandler: EventHandler,
        loggers: Array[Logger]
    ): Array[Task] =
        if baseConfig.parallelism > 1 then
            java.lang.System.err.println(
                s"[kyo-test] WARNING: parallelism=${baseConfig.parallelism} is capped to 1 on Scala Native " +
                    "(single-threaded test fixture). Tests will run sequentially."
            )
        end if

        // Cap parallelism to 1: Native has real threads but our test fixture is single-threaded for simplicity.
        val nativeConfig = if baseConfig.parallelism > 1 then baseConfig.copy(parallelism = 1) else baseConfig

        val report = runSuite(nativeConfig)
        results.add(report)
        emitEvents(report, eventHandler)
        Array.empty[Task]
    end execute

    // On Scala Native, ScalaNativeClassLoader.loadClass throws a "dummy" NotImplementedError.
    // Use the Scala Native Reflect API to get the runtime Class object from the reflection registry.
    // The class is registered because kyo.test.Test carries @EnableReflectiveInstantiation via
    // kyo.test.internal.KyoTestReflect.
    private def runSuite(config: RunConfig): TestReport =
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
        // NativeTask.scala is not a Frame-deriving file (only *Test/*Bench.scala are). This is the sanctioned
        // sbt-edge boundary (steering.md), matching the AllowUnsafe already used inside runToFuture.
        val future = kyo.test.runner.TestRunner.runToFuture(nextClass, config)(using kyo.Frame.internal)
        Await.result(future, Duration.Inf)
    end runSuite

    private def emitEvents(report: TestReport, handler: EventHandler): Unit =
        for
            sr             <- report.suiteReports.iterator
            (path, result) <- sr.leafResults.iterator
        do
            handler.handle(EventBuilder.build(taskDef, path, result))

end NativeTask
