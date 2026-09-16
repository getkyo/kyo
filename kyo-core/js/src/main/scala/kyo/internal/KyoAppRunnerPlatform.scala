package kyo.internal

import kyo.*

/** Scala.js registration of [[KyoAppRunner]] effects via a long-lived fiber. */
trait KyoAppRunnerPlatform:
    self: KyoAppRunnerWithInterrupts =>

    private var last: Unit < (Async & Abort[Throwable]) = ()
    private var deferredRuns: Chunk[() => Unit]         = Chunk.empty

    /** Registers an effect to run when [[KyoAppRunner.runInitCode]] executes. */
    final protected def registerEffect[A](effect: => A < (Async & Scope & Abort[Any]))(using Frame, Render[A]): Unit =
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
            // The application is the one thing that holds the host running while it has work left: kyo's own timers are unref'd,
            // matching the daemon threads the same cadences run on elsewhere, so nothing internal keeps a finished program alive.
            // The hold is taken here rather than inside the carrier, so it already exists when this returns to the host.
            val hold = HostKeepAlive.acquire()
            discard(Fiber.Unsafe.init(Sync.ensure(Sync.defer(hold.release()))(last)))
        )
    end registerEffect
end KyoAppRunnerPlatform
