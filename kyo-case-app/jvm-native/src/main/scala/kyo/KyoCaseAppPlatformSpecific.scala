package kyo

import caseapp.core.RemainingArgs

trait KyoCaseAppPlatformSpecific[T]:
    self: KyoCaseAppSupport[T, Async & Scope & Abort[Throwable]] =>

    final override protected def registerRun[A](v: (T, RemainingArgs) => A < (Async & Scope & Abort[Throwable]))(using
        Frame,
        Render[A]
    ): Unit =
        import AllowUnsafe.embrace.danger
        initCode = initCode.appended(() =>
            val (options, remainingArgs) = cliArgs
            val result = Sync.Unsafe.evalOrThrow(Abort.run(KyoApp.runAndBlock(runTimeout)(handle(v(options, remainingArgs)))))
            onResult(result)
        )
    end registerRun

end KyoCaseAppPlatformSpecific
