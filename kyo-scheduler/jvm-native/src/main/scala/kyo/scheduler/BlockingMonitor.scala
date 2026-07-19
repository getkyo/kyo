package kyo.scheduler

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.LockSupport
import kyo.scheduler.util.ThreadUserTime
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Monitors worker threads for blocking by sampling user CPU time.
  *
  * A thread is "blocked" when its user CPU time stops advancing — it is blocked in a kernel operation (I/O, sleep, lock) rather than
  * executing user code. The monitor detects this by periodically sampling per-thread user CPU time: if two consecutive samples return the
  * same value, the thread is blocked. This catches all forms of blocking including cases where Thread.getState() is misleading — socket
  * reads, server accepts, NIO operations, and file I/O on FIFOs all report RUNNABLE despite being blocked in the kernel. User-mode time
  * (excluding kernel time) is used on all platforms, which also identifies threads spinning in kernel locks (contended futex/mutex) as
  * blocked.
  *
  * The monitor provides two capabilities from a single background thread:
  *
  * #### Blocking compensation
  *
  * Blocked workers have their `blocked` flag set. The scheduler reads this in checkAvailability to stop routing new tasks to blocked
  * workers and draining their queues to other workers.
  *
  * #### Interrupt dispatch
  *
  * When a fiber is interrupted, the task reports it via needsInterrupt() and the monitor is woken for an immediate scan via wake(). If the
  * worker's thread is blocked, Thread.interrupt() is dispatched to wake it from the blocking operation. The monitor re-interrupts on
  * subsequent cycles if the thread re-blocks (e.g., code that catches InterruptedException and retries).
  *
  * #### Timing and pressure adaptation
  *
  * The monitor parks between scans using LockSupport.parkNanos and can be woken early by wake(). Two parameters control timing:
  *
  *   - **intervalNs** (default 2ms, `-Dkyo.scheduler.blockingMonitorIntervalNs`): the periodic scan cadence for blocking compensation.
  *   - **minIntervalNs** (default 500μs, `-Dkyo.scheduler.blockingMonitorMinIntervalNs`): the minimum time between scans, controlling the
  *     fastest response to wake() for interrupt dispatch.
  *
  * Safety against false positives is handled by pressure-scaled block thresholds, not by the scan interval. Each cycle, the monitor
  * measures its own scheduling delay (how long parkNanos actually took vs requested). If the OS couldn't schedule the monitor on time,
  * worker threads are likely CPU-starved too, and flat CPU time doesn't mean "blocked." The block threshold scales proportionally with this
  * pressure: under normal load (pressure ~1.0), the base threshold (default 2) applies and detection is fast. Under heavy load (e.g.,
  * pressure 5.0), the threshold scales to 10, requiring more consecutive idle samples before marking a worker as blocked. Truly blocked
  * threads (flat CPU time indefinitely) still reach any threshold. CPU-starved threads (flat transiently) get scheduled eventually and
  * reset their count before reaching the elevated threshold.
  *
  * When wake() is called, the monitor runs a scan as soon as minInterval has elapsed. Multiple concurrent wake() calls collapse into a
  * single scan via LockSupport's permit model, providing natural backpressure.
  *
  * @see
  *   Worker for the blocked flag and checkAvailability
  * @see
  *   Task for needsInterrupt
  */
private[scheduler] class BlockingMonitor(
    workers: Array[Worker],
    currentWorkers: () => Int,
    maxWorkers: Int,
    executor: ExecutorService
) {

    /** Test-only constructor for unit testing sample/isBlocked without workers or a monitor thread. */
    private[scheduler] def this(capacity: Int) =
        this(null, () => 0, capacity, null)

    // Periodic scan interval for blocking compensation (default 2ms).
    private val intervalNs = blockingMonitorIntervalNs()

    // Minimum interval between scans. Probed at startup to match the platform's CPU time
    // counter resolution — the smallest interval where cross-thread samples reliably advance.
    // This ensures two consecutive samples are far enough apart for the OS to update. The
    // pressure-scaled block threshold handles load-dependent false positives on top of this.
    // Can be overridden via system property.
    private val minIntervalNs: Long = {
        val override_ = blockingMonitorMinIntervalNs()
        if (override_ > 0) override_.toLong
        else if (executor ne null) Math.max(ThreadUserTime.probeResolution(), 2000000L) // 2ms floor
        else 2000000L                                                                   // test-only constructor
    }

    // The cadence the monitor actually aims for between scans: the configured interval,
    // floored by the platform's CPU-time counter resolution. Scheduling pressure is
    // measured against this cadence; measuring against intervalNs alone would read the
    // counter granularity itself as permanent CPU starvation on platforms where the
    // resolution exceeds the interval (Windows ticks at ~15.6ms vs the 2ms default),
    // pinning the block threshold several times above its configured value.
    private val cadenceNs = Math.max(intervalNs, minIntervalNs)

    // Base block threshold: consecutive idle samples required before marking a worker as blocked.
    // Dynamically scaled by scheduling pressure (see effectiveBlockThreshold).
    private val blockThreshold = blockingMonitorBlockThreshold()

    // Pre-allocated arrays — reused each cycle, single-threaded access
    private val threadIds = new Array[Long](maxWorkers)
    private val positions = new Array[Int](maxWorkers)
    private val tasks     = new Array[Task](maxWorkers)
    private val userTimes = new Array[Long](maxWorkers)
    // Per-slot sampling state, keyed by worker position (or by batch index in the
    // detection-only test path). lastThreadIds guards thread identity: the batch order
    // shifts as workers mount and unmount, and a slot's accumulated state must never be
    // read against a sample of a different thread — comparing one thread's CPU time to
    // another's fabricates idle/active verdicts and corrupts blockCounts.
    private val lastThreadIds = new Array[Long](maxWorkers)
    private val lastUserTimes = Array.fill[Long](maxWorkers)(-1L)
    private val blockedFlags  = new Array[Boolean](maxWorkers)
    private val blockCounts   = new Array[Int](maxWorkers)

    @volatile private var monitorThread: Thread = null
    private var lastCycleNanos: Long            = 0L
    // Effective threshold scaled by scheduling pressure. When the monitor's own parkNanos
    // takes longer than expected, the system is CPU-starved and flat CPU time on worker
    // threads is expected (not blocking). The threshold scales proportionally so truly blocked
    // threads (flat indefinitely) still get detected while CPU-starved threads (flat transiently)
    // don't reach the elevated threshold before getting CPU time again.
    private var effectiveBlockThreshold: Int = blockThreshold

    private val task =
        if (executor ne null)
            executor.submit(
                (
                    () => {
                        monitorThread = Thread.currentThread()
                        lastCycleNanos = System.nanoTime()
                        val thread = Thread.currentThread()
                        @scala.annotation.tailrec
                        def loop(): Unit =
                            if (!thread.isInterrupted()) {
                                val now     = System.nanoTime()
                                val elapsed = now - lastCycleNanos
                                if (elapsed >= minIntervalNs) {
                                    val pressure = elapsed.toDouble / cadenceNs
                                    effectiveBlockThreshold = Math.max(blockThreshold, (blockThreshold * pressure).toInt)
                                    cycle()
                                    lastCycleNanos = System.nanoTime()
                                    LockSupport.parkNanos(intervalNs)
                                } else {
                                    LockSupport.parkNanos(minIntervalNs - elapsed)
                                }
                                loop()
                            }
                        loop()
                        Thread.interrupted(): Unit
                    }
                ): Callable[Unit]
            )
        else null

    /** Wakes the monitor for an immediate scan cycle.
      *
      * Called by the Scheduler when a fiber is interrupted. Uses LockSupport.unpark which is a no-op if the monitor is already running, and
      * collapses multiple calls into a single wakeup via the permit model.
      */
    def wake(): Unit = {
        val t = monitorThread
        if (t ne null) LockSupport.unpark(t)
    }

    private var lastCount: Int = 0

    // Scan counter — written only by the monitor thread, read cross-thread by tests to
    // verify that a burst of wake() calls coalesces into few scans, not one scan per call.
    @volatile private[scheduler] var cycles: Long = 0L

    private def cycle(): Unit = {
        cycles += 1
        try {
            // Scan every worker slot, not just the first currentWorkers(): the regulator can
            // shrink the admitted count while workers above it still hold mounted tasks (e.g.
            // blocked in a syscall), and an unscanned worker is never flagged blocked, never
            // compensated for, and never receives its requested interrupt.
            val count = collect(workers.length, 0, 0)
            // Clear task references from positions no longer in use
            clearTasks(lastCount, count)
            lastCount = count
            if (count > 0) {
                ThreadUserTime.userTimes(threadIds, count, userTimes)
                process(count, 0)
            }
        } catch {
            case ex if NonFatal(ex) =>
                bug(s"Blocking monitor has failed.", ex)
        }
    }

    /** Samples CPU time and updates blocking state for the given thread IDs. Used by tests to drive the monitor without workers. */
    private[scheduler] def sample(threadIds: Array[Long], count: Int): Unit = {
        ThreadUserTime.userTimes(threadIds, count, userTimes)
        detectOnly(threadIds, count, 0)
    }

    /** Whether the thread at this position has unchanged user CPU time. */
    private[scheduler] def isBlocked(position: Int): Boolean =
        blockedFlags(position)

    @tailrec private def collect(n: Int, position: Int, count: Int): Int =
        if (position >= n) count
        else {
            val worker  = workers(position)
            val mountId = if (worker ne null) worker.mountId else -1L
            if (mountId > 0L) {
                threadIds(count) = mountId
                positions(count) = position
                tasks(count) = worker.currentTask
                collect(n, position + 1, count + 1)
            } else
                collect(n, position + 1, count)
        }

    /** Records the sample `(tid, userTime)` for slot `pos` and returns whether the thread was idle across the last two samples of the same
      * thread. A change of thread at the slot resets the baseline and the accumulated block count instead of inheriting the previous
      * occupant's state. Exposed to tests as the single sampling-state transition.
      */
    private[scheduler] def updateSlot(pos: Int, tid: Long, userTime: Long): Boolean = {
        val sameThread = lastThreadIds(pos) == tid
        val lastTime   = lastUserTimes(pos)
        lastThreadIds(pos) = tid
        lastUserTimes(pos) = userTime
        val idle = sameThread && userTime >= 0 && lastTime >= 0 && userTime == lastTime
        if (idle) blockCounts(pos) += 1
        else blockCounts(pos) = 0
        idle
    }

    // Single pass: detect blocking from CPU time samples, set blocked flags, dispatch interrupts.
    // Requires blockThreshold consecutive idle samples before marking a worker as blocked,
    // preventing false positives from OS timer tick granularity or transient CPU starvation.
    // Compares currentTask against the snapshot from collect() to avoid dispatching Thread.interrupt()
    // based on stale CPU time data if the worker switched tasks between the batch query and now.
    @tailrec private def process(count: Int, i: Int): Unit =
        if (i < count) {
            val pos     = positions(i)
            val _       = updateSlot(pos, threadIds(i), userTimes(i))
            val blocked = blockCounts(pos) >= effectiveBlockThreshold
            val worker  = workers(pos)
            worker.blocked = blocked
            if (blocked) {
                val task = tasks(i)
                if ((task ne null) && task.needsInterrupt() && worker.interruptLock.compareAndSet(false, true)) {
                    try {
                        if (worker.currentTask eq task) {
                            val mount = worker.mount
                            if (mount ne null)
                                mount.interrupt()
                        }
                    } finally worker.interruptLock.set(false)
                }
            }
            tasks(i) = null // clear reference
            process(count, i + 1)
        }

    // Clear stale task references from positions that were in the previous cycle but not this one
    @tailrec private def clearTasks(prev: Int, curr: Int): Unit =
        if (curr < prev) {
            tasks(curr) = null
            clearTasks(prev, curr + 1)
        }

    // Detection only — used by tests via sample() where there are no workers. Slots are
    // keyed by batch index; the identity guard in updateSlot applies the same way.
    @tailrec private def detectOnly(tids: Array[Long], count: Int, i: Int): Unit =
        if (i < count) {
            blockedFlags(i) = updateSlot(i, tids(i), userTimes(i))
            detectOnly(tids, count, i + 1)
        }

    def stop(): Unit = if (task ne null) { val _ = task.cancel(true) }
}
