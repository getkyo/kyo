package kyo.test

import java.util.concurrent.TimeUnit
import kyo.*
import kyo.test.ReporterEventRenderer.ConsoleEventRenderer

/** A `TestRunner[R, E]` encapsulates all the logic necessary to run specs that require an environment `R` and may fail with an error `E`.
  * Test runners require a test executor, a runtime configuration, and a reporter.
  */
final case class TestRunner[R, E](executor: TestExecutor[R, E]):
    self =>

    // Using a Kyo runtime for executing effects.
    val runtime: Runtime[Any] = Runtime.default

    /** Runs the spec, producing the execution results. Converted from Kyo's Summary < Any to a Kyo effect type: Summary < (Env[Any] & IO).
      */
    def run(fullyQualifiedName: String, spec: Spec[R, E], defExec: ExecutionStrategy = ExecutionStrategy.ParallelN(4))(
        using trace: Trace
    ): Summary < (Env[Any] & IO) =
        for
            start   <- Clock.instant
            summary <- executor.run(fullyQualifiedName, spec, defExec)
            end     <- Clock.instant
        yield summary.timed(start, end)

    trait UnsafeAPI:
        def run(spec: Spec[R, E])(using trace: Trace, unsafe: Unsafe): Unit
        def runAsync(spec: Spec[R, E])(k: => Unit)(using trace: Trace, unsafe: Unsafe): Unit
        def runSync(spec: Spec[R, E])(using trace: Trace, unsafe: Unsafe): Exit[Nothing, Unit]
    end UnsafeAPI

    val unsafe: UnsafeAPI =
        new UnsafeAPI:
            def run(spec: Spec[R, E])(using trace: Trace, unsafe: Unsafe): Unit =
                runtime.unsafe
                    .run(self.run("Test Task name unavailable in this context.", spec))
                    .getOrThrowFiberFailure()

            def runAsync(spec: Spec[R, E])(k: => Unit)(using trace: Trace, unsafe: Unsafe): Unit =
                val fiber = runtime.unsafe.fork(self.run("Test Task name unavailable in this context.", spec))
                fiber.unsafe.addObserver {
                    case Exit.Success(_) => k
                    case Exit.Failure(c) => throw FiberFailure(c)
                }
            end runAsync

            def runSync(spec: Spec[R, E])(using trace: Trace, unsafe: Unsafe): Exit[Nothing, Unit] =
                runtime.unsafe.run(self.run("Test Task name unavailable in this context.", spec).unit)

end TestRunner
