package kyo

abstract class KyoAppPlatformSpecific
    extends KyoApp.Base[Async & Scope & Abort[Throwable]]
    with KyoAppRunnerWithInterrupts
    with KyoAppRunnerPlatform:

    final override protected def exitHook(code: Int)(using AllowUnsafe): Unit =
        exit(code)
    end exitHook

    final override protected def run[A](v: => A < (Async & Scope & Abort[Throwable]))(using Frame, Render[A]): Unit =
        registerEffect(v)
    end run

end KyoAppPlatformSpecific
