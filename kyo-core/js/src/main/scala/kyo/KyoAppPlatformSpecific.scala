package kyo

abstract class KyoAppPlatformSpecific extends KyoApp.Base[Async & Resource & Abort[Throwable]]:

    private var last: Unit < (Async & Abort[Throwable]) = ()

    final override protected def run[A](v: => A < (Async & Resource & Abort[Throwable]))(using Frame, Render[A]): Unit =
        import AllowUnsafe.embrace.danger
        val current: Unit < (Async & Abort[Throwable]) =
            Abort.runWith(Async.timeout(runTimeout)(handle(v)))(result => Sync.defer(onResult(result)).andThen(Abort.get(result)).unit)

        last = last.andThen(current)

        initCode = Chunk(() =>
            val raced = Async.raceFirst(Clock.repeatWithDelay(1.hour)(()).map(_.get), last)
            val _     = Sync.Unsafe.evalOrThrow(Fiber.init(raced))
        )
    end run

end KyoAppPlatformSpecific
