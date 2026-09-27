package kyo.scheduler

import java.util.concurrent.Executor
import scala.annotation.nowarn

/** Monotonic milliseconds from an arbitrary origin, re-read every 128 calls.
  *
  * A duration source, never a calendar time. The kernel's safepoint judges the slice deadlines built from these readings against
  * `System.nanoTime` in milliseconds, so the two must stay on the same basis.
  */
@nowarn
final class InternalClock(executor: Executor = null) {

    var steps = 0
    var curr  = InternalClock.monotonicMillis()

    def currentMillis(): Long = {
        steps += 1
        if ((steps & 128) == 0)
            curr = InternalClock.monotonicMillis()
        curr
    }

    def stop(): Unit = {}

}

object InternalClock {
    private[kyo] def monotonicMillis(): Long = System.nanoTime() / 1000000L
}
