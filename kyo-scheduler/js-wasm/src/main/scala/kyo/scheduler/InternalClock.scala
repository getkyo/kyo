package kyo.scheduler

import java.util.concurrent.Executor
import scala.annotation.nowarn

/** Monotonic milliseconds from an arbitrary origin, read on every call.
  *
  * A duration source, never a calendar time. The scheduler reads it once per slice to build the slice deadline, and the kernel's
  * safepoint judges that deadline against a fresh `System.nanoTime` in milliseconds, so the two must stay on the same basis. A cached
  * reading would date the deadline in the past and stop the slice at its first safepoint.
  */
@nowarn
final class InternalClock(executor: Executor = null, now: () => Long = () => InternalClock.monotonicMillis()) {

    def currentMillis(): Long = now()

    def stop(): Unit = {}

}

object InternalClock {
    private[kyo] def monotonicMillis(): Long = System.nanoTime() / 1000000L
}
