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

    /** Handles the result of a registered effect block.
      *
      * A termination [[normalTermination]] recognises is reported by neither channel: it renders nothing
      * and exits with the code that supplies. An operator stopping the process is not a fault, so
      * rendering and rethrowing it puts a stack-trace-shaped line on stderr for every ordinary stop.
      *
      * Otherwise a value is rendered to standard output and a failure to standard error. The split is
      * what an application's stdout contract requires: stdout is the program's output, and a failure is
      * a diagnostic about the program, not output from it. A stdio server makes the cost of getting this
      * wrong concrete, since every startup failure (a bad URL, a database refusing connections, a
      * missing environment variable) put a non-JSON line on the very channel its host was parsing, and
      * the host's first read of the connection was garbage. No discipline in the run block avoids it:
      * this is the framework's own terminal reporting, reached by any failure the block does not catch.
      * Every other application type wants the same split for the same reason, which is why `IOApp`
      * reports an unhandled error to `System.err`.
      *
      * A `Throwable` failure is rendered here and then rethrown, so the runtime's uncaught handler
      * reports it a second time with its stack. Both copies land on standard error, and the second
      * carries strictly more than the first.
      */
    final protected def onResult[E, A](result: Result[E, A])(using Render[Result[E, A]], AllowUnsafe): Unit =
        result match
            case Error(e: Throwable) if normalTermination(e).nonEmpty =>
                exitHook(normalTermination(e).getOrElse(0))
            case _ =>
                if !result.exists(().equals(_)) then
                    if result.isError then Console.live.unsafe.printLineErr(result.show)
                    else Console.live.unsafe.printLine(result.show)
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
