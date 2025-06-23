package kyo

abstract class KyoAppPlatformSpecific extends KyoApp.Base[Async & Resource & Abort[Throwable]]:

    final override protected def run[A](v: => A < (Async & Resource & Abort[Throwable]))(using Frame, Render[A]): Unit =
        import AllowUnsafe.embrace.danger
        initCode = initCode.appended(() =>
            val result = Sync.Unsafe.evalOrThrow(Abort.run(Async.runAndBlock(timeout)(handle(v))))
            onResult(result)
        )
    end run

end KyoAppPlatformSpecific
