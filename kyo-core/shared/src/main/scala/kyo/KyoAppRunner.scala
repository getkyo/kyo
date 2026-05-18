package kyo

import Result.Error

/** Shared runner for Kyo application entrypoints (see [[KyoApp]] and case-app `KyoCaseApp`).
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
    protected def handle[A](v: A < (Async & Scope & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable])

    /** Runs all registered thunks in order. */
    final protected def runInitCode(): Unit =
        for proc <- initCode do proc()
    end runInitCode

    /** Handles the result of a registered effect block. */
    final protected def onResult[E, A](result: Result[E, A])(using Render[Result[E, A]], AllowUnsafe): Unit =
        if !result.exists(().equals(_)) then println(result.show)
        result match
            case Error(e: Throwable) => throw e
            case Error(_)            => exitHook(1)
            case _                   =>
        end match
    end onResult
end KyoAppRunner

/** [[KyoAppRunner]] with SIGINT/SIGTERM handling on non-Windows platforms. */
trait KyoAppRunnerWithInterrupts extends KyoAppRunner, KyoAppInterrupts:
    final override protected def handle[A](v: A < (Async & Scope & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable]) =
        handleWithInterrupts(v)
    end handle
end KyoAppRunnerWithInterrupts
