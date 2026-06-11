package kyo.internal

import kyo.*

abstract class KyoAppPlatformSpecific
    extends KyoApp.Base[Async & Scope & Abort[Any]]
    with KyoAppRunnerWithInterrupts
    with KyoAppRunnerPlatform:

    final override protected def exitHook(code: Int)(using AllowUnsafe): Unit =
        exit(code)
    end exitHook

    final override protected def run[A](v: => A < (Async & Scope & Abort[Any]))(using Frame, Render[A]): Unit =
        registerEffect(v)
    end run

end KyoAppPlatformSpecific
