package kyo.scheduler.util

/** Platform-specific sleep used by the concurrency regulator's jitter probe.
  *
  * On JVM, Thread.sleep delegates to a simple nanosleep syscall, which is lightweight and introduces minimal measurement noise. On Scala
  * Native, Thread.sleep uses pipe+poll+close for interrupt support (4 syscalls per call), which creates fd contention that destabilizes
  * jitter measurements. The Native implementation calls nanosleep directly to match JVM behavior.
  *
  * @see
  *   [[kyo.scheduler.regulator.Concurrency]] for the jitter probe that uses this
  */
private[scheduler] object Sleep {
    def apply(ms: Int): Unit = Thread.sleep(ms)
}
