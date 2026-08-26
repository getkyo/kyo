package kyo

import kyo.internal.OsSignal
import kyo.internal.Platform

/** Signal handling for Kyo application entrypoints. Mixed into `KyoAppRunnerWithInterrupts`. */
private[kyo] trait KyoAppInterrupts:

    // The exact Interrupted this trait raised for a signal, with the signal's conventional exit code.
    // The runner recognises the termination by IDENTITY against this value rather than by matching on a
    // message, which an application's own interrupt could imitate and be mistaken for a clean stop.
    // Unsafe: a plain cell, written from the signal handler thread and read once at exit.
    private val signalCause =
        AtomicRef.Unsafe.init[Maybe[(Interrupted, Int)]](Absent)(using AllowUnsafe.embrace.danger)

    /** Records that `signal` fired and returns the interrupt raised for it. */
    private[kyo] def recordSignal(signal: String, exitCode: Int)(using AllowUnsafe): Interrupted =
        val cause = Interrupted(Frame.internal, s"Interrupt Signal: $signal")
        signalCause.set(Present((cause, exitCode)))
        cause
    end recordSignal

    /** The exit code for `e`, when `e` is the interrupt this trait raised for an OS signal. */
    private[kyo] def signalExitCode(e: Throwable)(using AllowUnsafe): Maybe[Int] =
        signalCause.get() match
            case Present((cause, code)) if cause eq e => Present(code)
            case _                                    => Absent

    private val awaitInterrupt =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val promise       = Promise.Unsafe.init[Nothing, Any]()

        val interrupt = (signal: String, exitCode: Int) =>
            () => promise.completeDiscard(Result.panic(recordSignal(signal, exitCode)))

        if !Platform.isWindows then
            // 128 + the signal number: what a shell reports and a supervisor reads for a signalled stop.
            OsSignal.handle("INT", interrupt("INT", 130))
            OsSignal.handle("TERM", interrupt("TERM", 143))
        end if

        promise.mask().safe
    end awaitInterrupt

    protected def handleWithInterrupts[A](v: A < (Async & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable]) =
        Async.raceFirst(v, awaitInterrupt.get)
    end handleWithInterrupts
end KyoAppInterrupts
