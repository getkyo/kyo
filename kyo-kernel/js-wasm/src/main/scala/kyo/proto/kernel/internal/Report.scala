package kyo.proto.kernel.internal

// The containment edge for a failure with no continuation to fail into: no thread
// handler exists here, so the trace goes to the console.
private[kyo] object Report:
    def unhandled(ex: Throwable): Unit =
        ex.printStackTrace()
end Report
