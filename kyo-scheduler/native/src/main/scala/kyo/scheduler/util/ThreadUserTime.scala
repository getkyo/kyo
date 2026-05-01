package kyo.scheduler.util

import scala.annotation.tailrec
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.pthread.pthread_self
import scala.scalanative.posix.time.*
import scala.scalanative.posix.timeOps.*
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Queries per-thread CPU time for the BlockingMonitor on Scala Native.
  *
  * Unlike JVM where Thread.getId maps directly to ThreadMXBean queries, Scala Native threads are backed by pthreads whose handles are not
  * exposed by the Thread API. The Worker captures its pthread handle via currentThreadId() during mount, storing it as mountId. The
  * BlockingMonitor then passes these handles to userTimes() for cross-thread CPU time queries.
  *
  * ==Platform-specific implementations==
  *
  *   - '''macOS''': Uses Mach `thread_info(THREAD_BASIC_INFO)` via `pthread_mach_thread_np` to convert the pthread handle to a Mach port.
  *     Returns user CPU time only (excludes kernel time), matching JVM behavior. Correctly detects kernel-level spinning as blocked.
  *   - '''Linux''': Uses `pthread_getcpuclockid` + `clock_gettime` with clock ID bit manipulation to get user-only CPU time. The clock ID
  *     from `pthread_getcpuclockid` defaults to CPUCLOCK_SCHED (total time); flipping bits 0-1 to CPUCLOCK_VIRT (01) switches to user-only
  *     time. This is the same technique used by OpenJDK's ThreadMXBean.getThreadUserTime (JDK-8372584). Correctly detects kernel-level
  *     spinning as blocked, matching JVM and macOS behavior.
  *
  * Platform selection is resolved at link time via LinktimeInfo, enabling dead code elimination of the unused platform path.
  *
  * @see
  *   BlockingMonitor for how these measurements drive blocking detection
  * @see
  *   NativeThreadBindings for the extern declarations
  */
private[scheduler] object ThreadUserTime {

    /** Returns the native pthread handle for the current thread.
      *
      * Must be called from within the thread itself — pthread_self() returns the calling thread's handle. The Worker calls this during
      * mount and stores the result as mountId for cross-thread queries by the BlockingMonitor.
      */
    def currentThreadId(): Long = pthread_self().toLong

    /** Probes the platform's cross-thread CPU time counter resolution. */
    def probeResolution(): Long = {
        val default = 2000000L
        val started = new java.util.concurrent.CountDownLatch(1)
        val stop    = new java.util.concurrent.atomic.AtomicBoolean(false)
        val handle  = new java.util.concurrent.atomic.AtomicLong(0L)
        val spinner = new Thread((() => {
            handle.set(pthread_self().toLong)
            started.countDown()
            while (!stop.get()) ()
        }): Runnable)
        spinner.setDaemon(true)
        spinner.start()
        try {
            if (!started.await(5, java.util.concurrent.TimeUnit.SECONDS)) return default
            probeLoop(handle.get(), 100000L, default)
        } finally { stop.set(true); spinner.join(1000) }
    }

    @tailrec private def probeLoop(tid: Long, intervalNs: Long, default: Long): Long =
        if (intervalNs > default) default
        else if (probeVerify(tid, intervalNs, 5)) intervalNs
        else probeLoop(tid, intervalNs * 2, default)

    @tailrec private def probeVerify(tid: Long, intervalNs: Long, remaining: Int): Boolean =
        if (remaining <= 0) true
        else {
            val t1 = cpuTime(tid)
            java.util.concurrent.locks.LockSupport.parkNanos(intervalNs)
            val t2 = cpuTime(tid)
            if (t2 > t1) probeVerify(tid, intervalNs, remaining - 1)
            else false
        }

    /** Queries CPU times for multiple threads by pthread handle. Results in nanos, -1 if unavailable. */
    def userTimes(threadIds: Array[Long], count: Int, results: Array[Long]): Unit =
        fillUserTimes(threadIds, count, results, 0)

    @tailrec private def fillUserTimes(threadIds: Array[Long], count: Int, results: Array[Long], i: Int): Unit =
        if (i < count) {
            results(i) = cpuTime(threadIds(i))
            fillUserTimes(threadIds, count, results, i + 1)
        }

    private def cpuTime(pthreadHandle: Long): Long =
        if (LinktimeInfo.isMac) macUserTime(pthreadHandle)
        else if (LinktimeInfo.isLinux) linuxCpuTime(pthreadHandle)
        else -1L

    /** macOS: Convert pthread to mach port, then query thread_info for user CPU time only. */
    private def macUserTime(pthreadHandle: Long): Long = {
        val machPort = NativeThreadBindings.pthread_mach_thread_np(pthreadHandle.toUSize)
        if (machPort == 0.toUInt) return -1L

        // thread_basic_info has 10 integer_t fields; allocate as Ptr[CInt]
        val info  = stackalloc[CInt](10)
        val count = stackalloc[CInt]()
        !count = 10 // THREAD_BASIC_INFO_COUNT
        val kr = NativeThreadBindings.thread_info(
            machPort,
            3, // THREAD_BASIC_INFO
            info,
            count
        )
        if (kr != 0) return -1L

        // Layout: [0] = user_time.seconds, [1] = user_time.microseconds
        val seconds      = info(0).toLong
        val microseconds = info(1).toLong
        seconds * 1000000000L + microseconds * 1000L
    }

    /** Linux: Get thread-specific user-only CPU time via clock_gettime with clock ID bit manipulation.
      *
      * pthread_getcpuclockid returns a clock ID with bits 0-1 set to CPUCLOCK_SCHED (10), which gives total CPU time. Flipping bits 0-1 to
      * CPUCLOCK_VIRT (01) switches to user-only time. This is the same technique used by OpenJDK's ThreadMXBean.getThreadUserTime on Linux
      * (JDK-8372584). Zero additional overhead vs the total-time version.
      */
    private def linuxCpuTime(pthreadHandle: Long): Long = {
        val clockId = stackalloc[CInt]()
        val result  = NativeThreadBindings.pthread_getcpuclockid(pthreadHandle.toUSize, clockId)
        if (result != 0) return -1L

        // Flip clock type from CPUCLOCK_SCHED (10) to CPUCLOCK_VIRT (01) for user-only time
        !clockId = (!clockId & ~3) | 1

        val ts  = stackalloc[timespec]()
        val ret = clock_gettime(!clockId, ts)
        if (ret != 0) return -1L

        ts.tv_sec.toLong * 1000000000L + ts.tv_nsec.toLong
    }
}

/** Native bindings for thread CPU time measurement. */
@extern
private[scheduler] object NativeThreadBindings {

    /** macOS: Convert pthread handle to Mach thread port. */
    def pthread_mach_thread_np(thread: USize): CUnsignedInt = extern

    /** Linux: Get the CPU-time clock ID for a given pthread. */
    def pthread_getcpuclockid(thread: USize, clockId: Ptr[CInt]): CInt = extern

    /** Mach: Query thread information. info is a pointer to integer_t array, count is in/out. */
    def thread_info(target: CUnsignedInt, flavor: CInt, info: Ptr[CInt], count: Ptr[CInt]): CInt = extern
}
