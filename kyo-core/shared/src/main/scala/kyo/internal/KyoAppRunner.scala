package kyo.internal

import kyo.*
import kyo.Result.Error

/** Shared runner for Kyo application entrypoints (see [[kyo.KyoApp]] and case-app `KyoCaseApp`).
  *
  * Integrations mix this trait with [[KyoAppRunnerPlatform]] and implement [[exitHook]]. Register effects via
  * [[KyoAppRunnerPlatform.registerEffect]] (or a module-specific wrapper such as `registerRun`).
  */
trait KyoAppRunner:

    /** Thunks executed in registration order when the entrypoint runs. */
    protected var initCode: Chunk[() => Unit] = Chunk.empty

    /** The timeout for each registered effect block. */
    protected def runTimeout: Duration = Duration.Infinity

    /** Unsafely exits the process on non-throwable failures ([[onResult]]). */
    protected def exitHook(code: Int)(using AllowUnsafe): Unit

    /** Wraps an effect for execution (interrupts, scope, etc.). */
    protected def handle[A](v: A < (Async & Scope & Abort[Any]))(using Frame): A < (Async & Abort[Throwable])

    /** Runs all registered thunks in order. */
    final protected def runInitCode(): Unit =
        for proc <- initCode do proc()
    end runInitCode

    /** The exit code for a termination the runner treats as normal, when this is one.
      *
      * An operator stopping the process is not a fault, so it must not be rendered, must not throw, and
      * must not exit as a failure would. The default recognises none; [[KyoAppRunnerWithInterrupts]]
      * recognises the interrupt it raised for a signal.
      */
    protected def normalTermination(e: Throwable)(using AllowUnsafe): Maybe[Int] = Absent

    /** Handles the result of a registered effect block. */
    final protected def onResult[E, A](result: Result[E, A])(using Render[Result[E, A]], AllowUnsafe): Unit =
        result match
            case Error(e: Throwable) if normalTermination(e).nonEmpty =>
                // A signalled stop ends quietly with the signal's conventional code. Throwing here is
                // what put a stack-trace-shaped line on stderr for every normal stop, which trains an
                // operator watching a supervised process to ignore that stream.
                exitHook(normalTermination(e).getOrElse(0))
            case _ =>
                // Failures are rendered to stderr, never stdout: an application whose stdout is a
                // protocol channel (an MCP stdio server, a pipeline stage) would otherwise have its
                // channel corrupted by its own crash report, which is the worst moment to lose it.
                if !result.exists(().equals(_)) then scala.Console.err.println(result.show)
                result match
                    case Error(e: Throwable) => throw e
                    case Error(_)            => exitHook(1)
                    case _                   =>
                end match
        end match
    end onResult
end KyoAppRunner

/** [[KyoAppRunner]] with SIGINT/SIGTERM handling on non-Windows platforms. */
trait KyoAppRunnerWithInterrupts extends KyoAppRunner, KyoAppInterrupts:
    final override protected def normalTermination(e: Throwable)(using AllowUnsafe): Maybe[Int] =
        signalExitCode(e)

    final override protected def handle[A](v: A < (Async & Scope & Abort[Any]))(using Frame): A < (Async & Abort[Throwable]) =
        handleWithInterrupts(KyoApp.abortAnyToThrowable(Scope.run(v)))
    end handle
end KyoAppRunnerWithInterrupts
