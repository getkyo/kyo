package kyo.proto.kernel.internal

// The containment edge for a failure with no continuation to fail into: the thread's
// uncaught-exception handler, the scheduler Worker's own pattern.
private[kyo] object Report:
    def unhandled(ex: Throwable): Unit =
        val thread = Thread.currentThread()
        thread.getUncaughtExceptionHandler().uncaughtException(thread, ex)
end Report
