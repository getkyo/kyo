package kyo

abstract class KyoAppPlatformSpecific extends KyoApp.Base[Async & Scope & Abort[Any]]:

    final override protected def run[A](v: => A < (Async & Scope & Abort[Any]))(using Frame, Render[A]): Unit =
        import AllowUnsafe.embrace.danger
        initCode = initCode.appended(() =>
            val result = Sync.Unsafe.evalOrThrow(Abort.run(KyoApp.runAndBlock(runTimeout)(handle(v))))
            onResult(result)
        )
    end run

end KyoAppPlatformSpecific
