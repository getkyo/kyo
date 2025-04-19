package kyo

abstract class KyoAppPlatformSpecific extends KyoApp.Base[Async & Resource & Abort[Throwable]]:

    final override protected def run[A](v: => A < (Async & Resource & Abort[Throwable]))(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        initCode = initCode.appended(() =>
            val result = IO.Unsafe.evalOrThrow(Abort.run(Async.runAndBlock(timeout)(handle(v))))
            onResult(result)
        )
    end run

end KyoAppPlatformSpecific
