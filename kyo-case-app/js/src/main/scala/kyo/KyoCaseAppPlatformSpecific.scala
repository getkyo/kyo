package kyo

import caseapp.core.RemainingArgs

trait KyoCaseAppPlatformSpecific[T]:
    self: KyoCaseAppSupport[T, Async & Scope & Abort[Throwable]] =>

    private var last: Unit < (Async & Abort[Throwable]) = ()
    private var deferredRuns: Chunk[() => Unit]         = Chunk.empty

    final override protected def run[A](v: (T, RemainingArgs) => A < (Async & Scope & Abort[Throwable]))(using Frame, Render[A]): Unit =
        import AllowUnsafe.embrace.danger
        deferredRuns = deferredRuns.appended { () =>
            val (options, remainingArgs) = cliArgs
            val current: Unit < (Async & Abort[Throwable]) =
                Abort.runWith(Async.timeout(runTimeout)(handle(v(options, remainingArgs))))(result =>
                    Sync.defer(onResult(result)).andThen(Abort.get(result)).unit
                )
            last = last.andThen(current)
        }

        initCode = Chunk(() =>
            deferredRuns.foreach(_.apply())
            val raced = Async.raceFirst(Clock.repeatWithDelay(1.hour)(()).map(_.get), last)
            val _     = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(raced))
        )
    end run

end KyoCaseAppPlatformSpecific
