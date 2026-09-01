package kyo.proto.kernel.internal

private[kyo] object Report:
    def unhandled(ex: Throwable): Unit =
        val thread = Thread.currentThread()
        thread.getUncaughtExceptionHandler().uncaughtException(thread, ex)
end Report
