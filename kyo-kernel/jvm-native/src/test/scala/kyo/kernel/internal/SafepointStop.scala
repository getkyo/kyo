package kyo.kernel.internal

import kyo.discard

/** Requests a preemption stop for the evaluation running on this thread.
  *
  * The shared park tests drive a stop from inside the computation; how the request reaches the slot is the
  * platform's business, which is what this helper hides. Here it is the scheduler's own channel: a stop
  * dispatched to the current thread's slot.
  */
private[kernel] object SafepointStop:

    def request(): Unit =
        discard(Safepoint.stop(Thread.currentThread()))

end SafepointStop
