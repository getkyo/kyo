package kyo.scheduler.util

import java.lang.management.ManagementFactory
import java.util.concurrent.locks.LockSupport
import scala.annotation.tailrec

/** Queries per-thread user CPU time for the CPUStallMonitor.
  *
  * On JVM, uses `com.sun.management.ThreadMXBean.getThreadUserTime(long[])` — a batch API that performs a single JNI transition and single
  * ThreadsListHandle walk for all queried threads. This is significantly more efficient than per-thread queries when monitoring multiple
  * workers simultaneously.
  *
  * Returns user-mode time only (excludes kernel time), which correctly identifies threads spinning in kernel locks (contended futex/mutex)
  * as stalled — their total CPU time advances but user time is flat.
  *
  * Falls back to reporting -1 for all threads if the extended ThreadMXBean is unavailable (e.g., non-HotSpot JVMs).
  *
  * @see
  *   CPUStallMonitor for how these measurements drive stall detection
  */
private[scheduler] object ThreadUserTime {

    private val mxBean: Option[com.sun.management.ThreadMXBean] =
        ManagementFactory.getThreadMXBean() match {
            case bean: com.sun.management.ThreadMXBean if bean.isThreadCpuTimeSupported && bean.isThreadCpuTimeEnabled =>
                Some(bean)
            case _ =>
                None
        }

    /** Returns a thread identifier for the current thread, usable by userTimes.
      *
      * On JVM, this is the Java thread ID (Thread.getId). The Worker captures this during mount and stores it as mountId for cross-thread
      * queries by the CPUStallMonitor.
      */
    def currentThreadId(): Long = Thread.currentThread().getId

    /** Queries user CPU times for multiple threads in a single batch call.
      *
      * Results are written into the `results` array in nanos. Returns -1 for threads whose time is unavailable. The `threadIds` array may
      * be larger than `count` — only the first `count` entries are queried.
      */
    def userTimes(threadIds: Array[Long], count: Int, results: Array[Long]): Unit =
        mxBean match {
            case Some(bean) =>
                val times = bean.getThreadUserTime(java.util.Arrays.copyOf(threadIds, count))
                java.lang.System.arraycopy(times, 0, results, 0, count)
            case None =>
                fillUnavailable(results, count, 0)
        }

    /** Probes the platform's cross-thread CPU time counter resolution. Returns the smallest interval in nanos where two consecutive
      * cross-thread samples reliably show advancing time. Used by CPUStallMonitor as the minimum scan interval.
      */
    def probeResolution(): Long = {
        val default = 2000000L
        mxBean match {
            case None => default
            case Some(bean) =>
                val started = new java.util.concurrent.CountDownLatch(1)
                val stop    = new java.util.concurrent.atomic.AtomicBoolean(false)
                val spinner = new Thread((() => { started.countDown(); while (!stop.get()) () }): Runnable)
                spinner.setDaemon(true)
                spinner.start()
                try {
                    if (!started.await(5, java.util.concurrent.TimeUnit.SECONDS)) return default
                    probeLoop(bean, spinner.getId, 100000L, default)
                } finally { stop.set(true); spinner.join(1000) }
        }
    }

    @tailrec private def probeLoop(bean: com.sun.management.ThreadMXBean, tid: Long, intervalNs: Long, default: Long): Long =
        if (intervalNs > default) default
        else if (probeVerify(bean, tid, intervalNs, 5)) intervalNs
        else probeLoop(bean, tid, intervalNs * 2, default)

    // Verify that the counter advances in N consecutive samples at the given interval
    @tailrec private def probeVerify(bean: com.sun.management.ThreadMXBean, tid: Long, intervalNs: Long, remaining: Int): Boolean =
        if (remaining <= 0) true
        else {
            val t1 = bean.getThreadUserTime(tid)
            LockSupport.parkNanos(intervalNs)
            val t2 = bean.getThreadUserTime(tid)
            if (t2 > t1) probeVerify(bean, tid, intervalNs, remaining - 1)
            else false
        }

    @tailrec private def fillUnavailable(results: Array[Long], count: Int, i: Int): Unit =
        if (i < count) {
            results(i) = -1L
            fillUnavailable(results, count, i + 1)
        }
}
