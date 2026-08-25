package kyo.kernel.internal

/** Requests a preemption stop for the running evaluation.
  *
  * The shared park tests drive a stop from inside the computation; how the request reaches the slot is the
  * platform's business, which is what this helper hides. On a single-threaded runtime the scheduler's
  * deadline is the stop channel, so a deadline already in the past is a pending stop, and the slice
  * boundary's consume clears it the same way it clears a dispatched stop.
  */
private[kernel] object SafepointStop:

    def request(): Unit =
        Safepoint.deadline(0L)

end SafepointStop
