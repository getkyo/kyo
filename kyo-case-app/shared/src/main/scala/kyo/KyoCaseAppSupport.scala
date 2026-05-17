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
                          |   run {
                          |     Console.printLine(s"Hello ${options}")
                          |   }
                          |""".stripMargin,
                trailer = ""
            )))
        else for proc <- initCode do proc()
        end if
    end run

    /** Parsed command-line options for this application. */
    final protected def options: T =
        if !_parsed then
            throw IllegalStateException("options are not available until after CaseApp parsing")
        else _options
    end options

    /** Positional arguments that were not consumed by option parsing. */
    final protected def remainingArgs: RemainingArgs =
        val args = _remainingArgs
        if args eq null then
            throw IllegalStateException("remainingArgs are not available until after CaseApp parsing")
        else args
    end remainingArgs

    /** Unsafely exits the application. */
    protected def exitApp(code: Int)(using AllowUnsafe): Unit =
        internal.Platform.exit(code)

    /** Unified handling logic for supporting arbitrary effects. */
    protected def handle[A](v: A < S)(using Frame): A < (Async & Abort[Throwable])

    /** The timeout for each [[run]] block. */
    protected def runTimeout: Duration = Duration.Infinity

    /** Registers a Kyo effect to run after case-app parsing completes. */
    protected def run[A](v: => A < S)(using Frame, Render[A]): Unit

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
