package kyo

import kyo.internal.OsSignal

/** Interrupt handling for Kyo case-app entrypoints. */
trait KyoCaseAppInterrupts:
    self: KyoCaseAppSupport[?, ?] =>

    private val awaitInterrupt =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val promise       = Promise.Unsafe.init[Nothing, Any]()

        val interrupt = (signal: String) =>
            () =>
                promise
                    .completeDiscard(Result.panic(Interrupted(Frame.internal, s"Interrupt Signal: $signal")))

        if System.live.unsafe.operatingSystem() != System.OS.Windows then
            OsSignal.handle("INT", interrupt("INT"))
            OsSignal.handle("TERM", interrupt("TERM"))

        promise.mask().safe
    end awaitInterrupt

    override protected def handle[A](v: A < (Async & Scope & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable]) =
        Async.raceFirst(Scope.run(v), awaitInterrupt.get)
    end handle
end KyoCaseAppInterrupts
