package kyo.scheduler.util

import scala.scalanative.posix.time.*
import scala.scalanative.posix.timeOps.*
import scala.scalanative.unsafe.*

/** Platform-specific sleep used by the concurrency regulator's jitter probe.
  *
  * Scala Native's Thread.sleep uses pipe+poll+close for interrupt support
  * (4 syscalls per call), which creates fd contention that destabilizes the
  * regulator's jitter measurements. This implementation calls nanosleep
  * directly (single syscall, no fd allocation), matching JVM behavior.
  *
  * @see
  *   [[kyo.scheduler.regulator.Concurrency]] for the jitter probe that uses this
  */
private[scheduler] object Sleep {
    def apply(ms: Int): Unit = {
        val ts = stackalloc[timespec]()
        ts.tv_sec = (ms / 1000).toSize
        ts.tv_nsec = ((ms % 1000).toLong * 1000000L).toSize
        val _ = nanosleep(ts, null)
    }
}
