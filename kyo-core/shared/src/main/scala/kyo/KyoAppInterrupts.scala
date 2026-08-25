package kyo

import kyo.internal.OsSignal
import kyo.internal.Platform

/** Signal handling for Kyo application entrypoints. Mixed into `KyoAppRunnerWithInterrupts`. */
private[kyo] trait KyoAppInterrupts:
    private val awaitInterrupt =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val promise       = Promise.Unsafe.init[Nothing, Any]()

        val interrupt = (signal: String) =>
            () =>
                promise
                    .completeDiscard(Result.panic(Interrupted(Frame.internal, s"Interrupt Signal: $signal")))

        if !Platform.isWindows then
            OsSignal.handle("INT", interrupt("INT"))
            OsSignal.handle("TERM", interrupt("TERM"))

        promise.mask().safe
    end awaitInterrupt

    protected def handleWithInterrupts[A](v: A < (Async & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable]) =
        Async.raceFirst(v, awaitInterrupt.get)
    end handleWithInterrupts
end KyoAppInterrupts
