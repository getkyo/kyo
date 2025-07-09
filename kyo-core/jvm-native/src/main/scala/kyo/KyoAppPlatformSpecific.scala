package kyo

abstract class KyoAppPlatformSpecific extends KyoApp.Base[Async & Scope & Abort[Throwable]]:

    final override protected def run[A](v: => A < (Async & Scope & Abort[Throwable]))(using Frame, Render[A]): Unit =
        import AllowUnsafe.embrace.danger
        initCode = initCode.appended(() =>
            val result = Sync.Unsafe.evalOrThrow(Abort.run(Async.runAndBlock(runTimeout)(handle(v))))
            onResult(result)
        )
    end run

end KyoAppPlatformSpecific
