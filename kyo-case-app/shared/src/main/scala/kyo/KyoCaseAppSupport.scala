package kyo

import Result.Error
import caseapp.core.RemainingArgs
import caseapp.core.app.CaseApp

/** Shared machinery for Kyo + case-app entrypoints. */
trait KyoCaseAppSupport[T, S]:
    self: CaseApp[T] =>

    private var _parsed: Boolean                     = false
    private var _options: T                          = null.asInstanceOf[T]
    private var _remainingArgs: RemainingArgs | Null = null
    protected var initCode: Chunk[() => Unit]        = Chunk.empty

    final override def run(options: T, remainingArgs: RemainingArgs): Unit =
        this._options = options
        this._remainingArgs = remainingArgs
        this._parsed = true
        if initCode.isEmpty then
            import AllowUnsafe.embrace.danger
            onResult(Result.fail(Ansi.highlight(
                header = "KyoCaseApp: nothing to execute. Did you forget to use a run block?",
                code = """|
                          | object Example extends KyoCaseApp[MyOptions]:
                          |   run { options =>
                          |     Console.printLine(s"Hello ${options}")
                          |   }
                          |""".stripMargin,
                trailer = ""
            )))
        else for proc <- initCode do proc()
        end if
    end run

    /** Parsed options and remaining arguments; only valid while [[initCode]] thunks run. */
    final protected def cliArgs: (T, RemainingArgs) =
        if !_parsed then
            throw IllegalStateException("cliArgs are not available until after CaseApp parsing")
        val rem = _remainingArgs
        if rem eq null then
            throw IllegalStateException("cliArgs are not available until after CaseApp parsing")
        (_options, rem)
    end cliArgs

    /** Unsafely exits the application. */
    protected def exitApp(code: Int)(using AllowUnsafe): Unit =
        internal.Platform.exit(code)

    /** Unified handling logic for supporting arbitrary effects. */
    protected def handle[A](v: A < S)(using Frame): A < (Async & Abort[Throwable])

    /** The timeout for each [[run]] block. */
    protected def runTimeout: Duration = Duration.Infinity

    /** Registers a Kyo effect to run after case-app parsing completes.
      *
      * '''Recommended''' for typical CLIs: most commands only need parsed flags and options, not leftover positionals.
      */
    final protected def run[A](v: T => A < S)(using Frame, Render[A]): Unit =
        registerRun((options, _) => v(options))
    end run

    /** Registers a Kyo effect when both parsed options and [[caseapp.core.RemainingArgs RemainingArgs]] are required. */
    final protected def run[A](v: (T, RemainingArgs) => A < S)(using Frame, Render[A]): Unit =
        registerRun(v)
    end run

    /** Registers a Kyo effect that does not use parsed CLI data.
      *
      * Ergonomic overload when the block does not need `options` or `remainingArgs` (equivalent to `run { (_, _) => ... }`). Uses the same
      * [[registerRun]] queue as the explicit overload, so registration order is unchanged when both forms are mixed.
      */
    final protected def run[A](v: => A < S)(using Frame, Render[A]): Unit =
        registerRun((_, _) => v)
    end run

    /** Registers one effect block. All [[run]] overloads delegate here so registration order is global regardless of which overload is
      * used.
      *
      * At execution time each block receives the same `options` and `remainingArgs` from the single case-app parse for that `main` call.
      */
    protected def registerRun[A](v: (T, RemainingArgs) => A < S)(using Frame, Render[A]): Unit

    /** Handles the result of the [[run]] block computation. */
    protected def onResult[E, A](result: Result[E, A])(using Render[Result[E, A]], AllowUnsafe): Unit =
        if !result.exists(().equals(_)) then println(result.show)
        result match
            case Error(e: Throwable) => throw e
            case Error(_)            => exitApp(1)
            case _                   =>
        end match
    end onResult
end KyoCaseAppSupport
