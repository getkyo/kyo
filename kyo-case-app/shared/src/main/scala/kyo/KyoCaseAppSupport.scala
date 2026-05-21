package kyo

import caseapp.core.RemainingArgs
import caseapp.core.app.CaseApp

/** Shared machinery for Kyo + case-app entrypoints. */
trait KyoCaseAppSupport[T]:
    self: CaseApp[T] & KyoAppRunnerWithInterrupts & KyoAppRunnerPlatform =>

    private var _parsed: Boolean                     = false
    private var _options: T                          = null.asInstanceOf[T]
    private var _remainingArgs: RemainingArgs | Null = null

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
        else runInitCode()
        end if
    end run

    /** Parsed options and remaining arguments; only valid while registered effects run. */
    final protected def cliArgs: (T, RemainingArgs) =
        if !_parsed then
            throw IllegalStateException("cliArgs are not available until after CaseApp parsing")
        val rem = _remainingArgs
        if rem eq null then
            throw IllegalStateException("cliArgs are not available until after CaseApp parsing")
        (_options, rem)
    end cliArgs

    override protected def exitHook(code: Int)(using AllowUnsafe): Unit =
        internal.Platform.exit(code)

    /** Registers a Kyo effect to run after case-app parsing completes.
      *
      * '''Recommended''' for typical CLIs: most commands only need parsed flags and options, not leftover positionals.
      */
    final protected def run[A](v: T => A < (Async & Scope & Abort[Throwable]))(using Frame, Render[A]): Unit =
        registerRun((options, _) => v(options))
    end run

    /** Registers a Kyo effect when both parsed options and [[caseapp.core.RemainingArgs RemainingArgs]] are required. */
    final protected def run[A](v: (T, RemainingArgs) => A < (Async & Scope & Abort[Throwable]))(using Frame, Render[A]): Unit =
        registerRun(v)
    end run

    /** Registers a Kyo effect that does not use parsed CLI data. */
    final protected def run[A](v: => A < (Async & Scope & Abort[Throwable]))(using Frame, Render[A]): Unit =
        registerRun((_, _) => v)
    end run

    /** Registers one effect block. All [[run]] overloads delegate here. */
    final protected def registerRun[A](v: (T, RemainingArgs) => A < (Async & Scope & Abort[Throwable]))(using Frame, Render[A]): Unit =
        registerEffect:
            val (options, remainingArgs) = cliArgs
            v(options, remainingArgs)
    end registerRun
end KyoCaseAppSupport
