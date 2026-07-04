package kyo

import kyo.internal.OsSignal

/** Signal handling for Kyo application entrypoints. Mixed into `KyoAppRunnerWithInterrupts`. */
private[kyo] trait KyoAppInterrupts:
    private val awaitInterrupt =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val promise       = Promise.Unsafe.init[Nothing, Any]()

        val interrupt = (signal: String) =>
            () =>
                promise
                    .completeDiscard(Result.panic(Interrupted(Frame.internal, s"Interrupt Signal: $signal")))

        // Unsafe: kyo.System lives in kyo-system (which depends on kyo-core); detect Windows from java.lang.System os.name (empty on JS, where signal registration is a no-op anyway).
        if !java.lang.System.getProperty("os.name", "").startsWith("Windows") then
            OsSignal.handle("INT", interrupt("INT"))
            OsSignal.handle("TERM", interrupt("TERM"))

        promise.mask().safe
    end awaitInterrupt

    protected def handleWithInterrupts[A](v: A < (Async & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable]) =
        Async.raceFirst(v, awaitInterrupt.get)
    end handleWithInterrupts
end KyoAppInterrupts
