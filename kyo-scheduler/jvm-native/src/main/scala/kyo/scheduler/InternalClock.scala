package kyo.scheduler

import java.util.concurrent.Executor
import java.util.concurrent.locks.LockSupport
import scala.annotation.nowarn

/** Low-resolution clock optimized for frequent access in the scheduler.
  *
  * While System.currentTimeMillis is accurate, calling it frequently creates significant overhead due to system calls. This clock uses a
  * dedicated thread to update a volatile timestamp every millisecond, allowing other threads to read the current time without system calls.
  *
  * The tradeoff of potentially being off by up to a millisecond is acceptable for scheduler operations like measuring task runtime and
  * detecting stalled workers. The performance benefit of avoiding system calls on every time check is substantial when processing thousands
  * of tasks per second.
  *
  * The clock self-corrects any drift by measuring the actual elapsed time between updates.
  *
  * @param executor
  *   Executor for running the update thread
  */
final case class InternalClock(executor: Executor) {

    @volatile private var _stop = false

    // padding to avoid false sharing
    val a1, a2, a3, a4, a5, a6, a7 = 0L

    @volatile private var millis = System.currentTimeMillis()

    // padding to avoid false sharing
    val b1, b2, b3, b4, b5, b6, b7 = 0L

    private var start = System.nanoTime()

    /** Get the current time in milliseconds without making a system call.
      *
      * This method is designed for frequent calls, returning the latest cached timestamp from the update thread. The returned time has
      * millisecond resolution but may be up to one millisecond behind the system time.
      *
      * @return
      *   Current time in milliseconds since epoch, accurate to within one millisecond
      */
    def currentMillis(): Long = millis

    /** Stop the clock's update thread.
      *
      * After stopping, currentMillis() will return the last updated timestamp. The clock cannot be restarted after stopping - create a new
      * instance if needed.
      */
    def stop(): Unit =
        _stop = true

    executor.execute(() =>
        // update in a separate method to ensure the code is JIT compiled
        while (!_stop) update()
    )

    private def update(): Unit = {
        millis = System.currentTimeMillis()
        val end     = System.nanoTime()
        val elapsed = Math.max(0, end - start)
        start = end
        LockSupport.parkNanos(1000000L - elapsed)
    }

    @nowarn("msg=unused")
    private val gauge =
        statsScope.scope("clock").gauge("skew")((System.currentTimeMillis() - millis).toDouble)
}
