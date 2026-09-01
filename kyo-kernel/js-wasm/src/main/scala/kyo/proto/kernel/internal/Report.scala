package kyo.proto.kernel.internal

private[kyo] object Report:
    def unhandled(ex: Throwable): Unit =
        ex.printStackTrace()
end Report
