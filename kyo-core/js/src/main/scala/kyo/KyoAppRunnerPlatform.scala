package kyo

/** Scala.js registration of [[KyoAppRunner]] effects via a long-lived fiber. */
trait KyoAppRunnerPlatform:
    self: KyoAppRunnerWithInterrupts =>

    private var last: Unit < (Async & Abort[Throwable]) = ()
    private var deferredRuns: Chunk[() => Unit]         = Chunk.empty

    /** Registers an effect to run when [[KyoAppRunner.runInitCode]] executes. */
    final protected def registerEffect[A](effect: => A < (Async & Scope & Abort[Throwable]))(using Frame, Render[A]): Unit =
        import AllowUnsafe.embrace.danger
        deferredRuns = deferredRuns.appended { () =>
            val current: Unit < (Async & Abort[Throwable]) =
                Abort.runWith(Async.timeout(runTimeout)(handle(effect)))(result =>
                    Sync.defer(onResult(result)).andThen(Abort.get(result)).unit
                )
            last = last.andThen(current)
        }

        initCode = Chunk(() =>
            deferredRuns.foreach(_.apply())
            val raced = Async.raceFirst(Clock.repeatWithDelay(1.hour)(()).map(_.get), last)
            val _     = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(raced))
        )
    end registerEffect
end KyoAppRunnerPlatform
