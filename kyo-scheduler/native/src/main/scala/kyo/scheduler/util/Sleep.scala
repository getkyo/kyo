package kyo.scheduler.util

import scala.scalanative.meta.LinktimeInfo

/** Platform-specific sleep used by the concurrency regulator's jitter probe.
  *
  * On Unix: calls nanosleep directly (single syscall, no fd allocation), avoiding the fd contention from Scala Native's Thread.sleep
  * (pipe+poll+close = 4 syscalls).
  *
  * On Windows: falls back to Thread.sleep since POSIX nanosleep is not available.
  *
  * @see
  *   [[kyo.scheduler.regulator.Concurrency]] for the jitter probe that uses this
  */
private[scheduler] object Sleep {
    def apply(ms: Int): Unit =
        if LinktimeInfo.isWindows then
            Thread.sleep(ms.toLong)
        else
            PosixSleep(ms)
}

// Separate object to avoid linking POSIX symbols on Windows
private[scheduler] object PosixSleep {
    import scala.scalanative.posix.time.*
    import scala.scalanative.posix.timeOps.*
    import scala.scalanative.unsafe.*

    def apply(ms: Int): Unit = {
        val ts = stackalloc[timespec]()
        ts.tv_sec = (ms / 1000).toSize
        ts.tv_nsec = ((ms % 1000).toLong * 1000000L).toSize
        val _ = nanosleep(ts, null)
    }
}
