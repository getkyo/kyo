package kyo.scheduler

import Scheduler.Config
import java.util.ArrayList
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport
import kyo.scheduler.regulator.Admission
import kyo.scheduler.regulator.Concurrency
import kyo.scheduler.top.Reporter
import kyo.scheduler.top.Status
import kyo.scheduler.util.Flag
import kyo.scheduler.util.LoomSupport
import kyo.scheduler.util.Threads
import kyo.scheduler.util.XSRandom
import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/** A high-performance task scheduler with adaptive concurrency control and admission regulation.
  *
  * The scheduler provides a foundation for concurrent task execution with features including dynamic worker pool sizing, admission control
  * to prevent overload, work stealing for load balancing, task preemption, and comprehensive performance monitoring.
  *
  * ==Worker Management==
  *
  * The scheduler maintains a pool of worker threads that execute tasks. The number of workers adjusts dynamically between configured
  * minimum and maximum bounds based on system load and performance metrics. Workers can steal tasks from each other to balance load across
  * the pool, ensuring efficient resource utilization.
  *
  * ==Admission Control==
  *
  * An admission regulator prevents system overload by selectively rejecting tasks when the system shows signs of congestion. The admission
  * rate adjusts automatically based on measured queuing delays, providing natural backpressure that helps maintain system stability under
  * varying loads.
  *
  * ==Concurrency Control==
  *
  * A concurrency regulator continuously monitors system scheduling efficiency through sophisticated timing measurements. By analyzing
  * scheduling delays and system load, it dynamically adjusts the worker pool size to maintain optimal performance. The regulator detects
  * both under-utilization and thread interference, scaling the thread count up or down accordingly.
  *
  * ==Thread Blocking and Adaptive Concurrency==
  *
  * The scheduler employs a sophisticated approach to handle thread blocking that requires no explicit signaling or special handling from
  * tasks. Instead of treating blocking as an exceptional case, the system embraces it as a natural part of task execution through its
  * adaptive concurrency mechanism.
  *
  * At its core, the scheduler uses an ephemeral thread model where workers acquire threads from a pool only when actively processing tasks.
  * When a thread becomes blocked, the scheduler detects this through direct thread state inspection and automatically compensates by
  * adjusting its concurrency level. This detection-and-response cycle creates a natural overflow capacity that maintains throughput even
  * when many threads are blocked.
  *
  * The design's effectiveness lies in its transparency: tasks can freely perform blocking operations without concerning themselves with
  * thread management. When blocking occurs, the concurrency regulator observes increased scheduling delays and responds by expanding the
  * worker pool if possible and necessary. As blocked threads resume, the improved scheduling efficiency triggers a gradual reduction in
  * worker count.
  *
  * ==Loom Integration==
  *
  * The scheduler seamlessly integrates with Java's Project Loom virtual threads when available, providing enhanced scalability for
  * I/O-bound workloads. To enable virtual threads, add the JVM argument '--add-opens=java.base/java.lang=ALL-UNNAMED' and set
  * '-Dkyo.scheduler.virtualizeWorkers=true'. The scheduler transparently manages virtual thread creation and scheduling through the worker
  * executor.
  *
  * ==Monitoring==
  *
  * Comprehensive metrics about scheduler operation are exposed through JMX and optional console reporting, providing detailed insight into
  * worker utilization, task execution, regulation decisions, and system load.
  *
  * @param workerExecutor
  *   Executor for running worker threads
  * @param clockExecutor
  *   Executor for running the internal clock
  * @param timerExecutor
  *   Executor for running scheduled operations
  * @param config
  *   Configuration parameters controlling scheduler behavior
  *
  * @see
  *   Worker for details on task execution and work stealing
  * @see
  *   Admission for admission control implementation
  * @see
  *   Concurrency for concurrency regulation details
  */
final class Scheduler(
    workerExecutor: Executor = Scheduler.defaultWorkerExecutor,
    clockExecutor: Executor = Scheduler.defaultClockExecutor,
    timerExecutor: ScheduledExecutorService = Scheduler.defaultTimerExecutor,
    config: Config = Config.default
) {

    import config.*

    private val pool    = LoomSupport.tryVirtualize(virtualizeWorkers, workerExecutor)
    private val clock   = new InternalClock(clockExecutor)
    private val workers = new Array[Worker](maxWorkers)
    private val flushes = new LongAdder

    @volatile private var allocatedWorkers = 0
    @volatile private var currentWorkers   = coreWorkers

    ensureWorkers()

    private val timer = InternalTimer(timerExecutor)

    private val admissionRegulator =
        new Admission(() => loadAvg(), schedule, () => System.currentTimeMillis, timer)

    private val concurrencyRegulator =
        new Concurrency(() => loadAvg(), updateWorkers, Thread.sleep(_), () => System.nanoTime, timer)

    private val top = new Reporter(status, enableTopJMX, enableTopConsoleMs, timer)

    /** Schedules a task for execution by the scheduler.
      *
      * The scheduler will assign the task to an available worker based on current load and system conditions. Tasks are executed according
      * to their priority ordering and may be preempted if they exceed their time slice.
      *
      * @param task
      *   The task to schedule for execution
      */
    def schedule(task: Task): Unit =
        schedule(task, null)

    /** Tests if a new task should be rejected based on current system conditions.
      *
      * The scheduler uses admission control to prevent system overload by selectively rejecting tasks when detecting signs of congestion.
      * This method provides probabilistic load shedding using random sampling, making it suitable for one-off tasks where consistent
      * admission decisions aren't required.
      *
      * This approach works well for:
      *   - One-off tasks with no related operations
      *   - Tasks where consistent rejection isn't critical
      *   - High-volume scenarios where perfect distribution isn't necessary
      *   - Cases where no natural key exists for the task
      *
      * For tasks requiring consistent admission decisions (e.g., related operations that should be handled similarly), prefer using
      * reject(key) or reject(string) instead.
      *
      * @return
      *   true if the task should be rejected, false if it can be accepted
      */
    def reject(): Boolean =
        admissionRegulator.reject()

    /** Tests if a task with the given string key should be rejected based on current system conditions.
      *
      * This method provides consistent admission decisions by using the string's hash as a sampling key. This ensures that identical
      * strings will receive the same admission decision at any given admission percentage, creating stable and predictable load shedding
      * patterns.
      *
      * This consistency is particularly valuable for:
      *   - User IDs or session identifiers to maintain consistent user experience
      *   - Transaction or operation IDs for related task sets
      *   - Service names or endpoints for targeted load shedding
      *   - Any scenario requiring deterministic admission control
      *
      * The string-based rejection provides several benefits:
      *   - Related requests from the same user/session get uniform treatment
      *   - Retries of rejected tasks won't add load since they'll stay rejected
      *   - System stabilizes with a consistent subset of flowing traffic
      *   - Natural backpressure mechanism for distributed systems
      *
      * @param key
      *   String to use for admission decision
      * @return
      *   true if the task should be rejected, false if it can be accepted
      */
    def reject(key: String): Boolean =
        admissionRegulator.reject(key)

    /** Tests if a task with the given integer key should be rejected based on current system conditions.
      *
      * This method provides consistent admission decisions by using the integer directly as a sampling key. It guarantees that identical
      * integers will receive the same admission decision at any given admission percentage, implemented through efficient modulo
      * operations.
      *
      * This method is particularly useful for:
      *   - Numeric identifiers like user IDs or request sequence numbers
      *   - Hash values from other sources
      *   - Cases where the caller has already computed a suitable numeric key
      *   - Performance-critical scenarios needing minimal overhead
      *
      * The integer-based rejection maintains the same consistency benefits as string-based rejection:
      *   - Deterministic decisions for identical keys
      *   - Stable load shedding patterns
      *   - Efficient handling of related operations
      *   - Natural queueing behavior for rejected requests
      *
      * @param key
      *   Integer to use for admission decision
      * @return
      *   true if the task should be rejected, false if it can be accepted
      */
    def reject(key: Int): Boolean =
        admissionRegulator.reject(key)

    /** Provides an Executor interface to the scheduler.
      *
      * Allows using the scheduler as a drop-in replacement for standard Java executors while maintaining all scheduler capabilities like
      * admission control and adaptive concurrency.
      *
      * @return
      *   An Executor that submits Runnables as scheduler tasks
      */
    def asExecutor: Executor =
        (r: Runnable) => schedule(Task(r))

    /** Provides a Scala ExecutionContext interface to the scheduler.
      *
      * Allows using the scheduler as a drop-in replacement for Scala execution contexts while maintaining all scheduler capabilities like
      * admission control and adaptive concurrency.
      *
      * @return
      *   An ExecutionContext backed by the scheduler
      */
    def asExecutionContext: ExecutionContext =
        ExecutionContext.fromExecutor(asExecutor)

    /** Provides a Java ExecutorService interface to the scheduler.
      *
      * Allows using the scheduler as a drop-in replacement for Java ExecutorService implementations while maintaining all scheduler
      * capabilities like admission control and adaptive concurrency. This implementation provides a minimal ExecutorService that delegates
      * task execution to the scheduler.
      *
      * Note that shutdown-related operations are no-ops in this implementation:
      *   - shutdown() and shutdownNow() have no effect
      *   - isShutdown() and isTerminated() always return false
      *   - awaitTermination() always returns false
      *
      * @return
      *   An ExecutorService that submits Runnables as scheduler tasks
      */
    def asExecutorService: ExecutorService =
        new AbstractExecutorService {
            def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
            def execute(command: Runnable): Unit                         = schedule(Task(command))
            def isShutdown(): Boolean                                    = false
            def isTerminated(): Boolean                                  = false
            def shutdown(): Unit                                         = {}
            def shutdownNow(): java.util.List[Runnable]                  = new ArrayList[Runnable]
        }

    /** Schedules a task for execution, optionally specifying the submitting worker.
      *
      * Implements a work-stealing load balancing strategy:
      *   - If submitted by a worker, tries to execute on that worker first
      *   - Otherwise samples a subset of workers to find one with minimal load
      *   - Falls back to random worker assignment if no suitable worker found
      */
    private def schedule(task: Task, submitter: Worker): Unit = {
        val nowMs          = clock.currentMillis()
        var worker: Worker = null
        if (submitter eq null) {
            worker = Worker.current()
            if ((worker ne null) && ((worker eq submitter) || !worker.checkAvailability(nowMs)))
                worker = null
        }
        if (worker eq null) {
            val currentWorkers = this.currentWorkers
            var position       = XSRandom.nextInt(currentWorkers)
            var stride         = Math.min(currentWorkers, scheduleStride)
            var minLoad        = Int.MaxValue
            while (stride > 0 && minLoad != 0) {
                val candidate = workers(position)
                if (
                    (candidate ne null) &&
                    (candidate ne submitter) &&
                    candidate.checkAvailability(nowMs)
                ) {
                    val l = candidate.load()
                    if (l < minLoad) {
                        minLoad = l
                        worker = candidate
                    }
                }
                position += 1
                if (position == currentWorkers)
                    position = 0
                stride -= 1
            }
        }
        while (worker eq null)
            worker = workers(XSRandom.nextInt(currentWorkers))
        worker.enqueue(task)
    }

    /** Attempts to steal a task from another worker with higher load.
      *
      * Implements work stealing by:
      *   - Sampling a subset of workers based on stealStride config
      *   - Finding worker with highest load above threshold
      *   - Atomically transferring tasks from chosen worker
      *
      * Returns null if no suitable tasks found to steal.
      */
    private def steal(thief: Worker): Task = {
        val currentWorkers = this.currentWorkers
        var worker: Worker = null
        var maxLoad        = 1
        var position       = XSRandom.nextInt(currentWorkers)
        var stride         = Math.min(currentWorkers, stealStride)
        while (stride > 0) {
            val candidate = workers(position)
            if (
                (candidate ne null) &&
                (candidate ne thief)
            ) {
                val load = candidate.load()
                if (load > maxLoad) {
                    maxLoad = load
                    worker = candidate
                }
            }
            position += 1
            if (position == currentWorkers)
                position = 0
            stride -= 1
        }
        if (worker ne null)
            worker.stealingBy(thief)
        else
            null
    }

    /** Forces completion of any pending tasks on the current worker thread.
      *
      * When called from a worker thread, drains and re-submits all queued tasks before returning. Has no effect when called from non-worker
      * threads.
      */
    def flush() = {
        val worker = Worker.current()
        if (worker ne null) {
            flushes.increment()
            worker.drain()
        }
    }

    /** Calculates the current average load across all workers.
      *
      * Load is measured as the number of queued plus executing tasks per worker. This metric is used by the regulators to make admission
      * and concurrency decisions.
      *
      * @return
      *   Average load per worker between 0.0 and worker queue capacity
      */
    def loadAvg(): Double = {
        val currentWorkers =
            this.currentWorkers
        var position = 0
        var sum      = 0
        while (position < currentWorkers) {
            val w = workers(position)
            if (w ne null)
                sum += w.load()
            position += 1
        }
        sum.toDouble / currentWorkers
    }

    /** Shuts down the scheduler and releases resources.
      *
      * Stops all internal threads, cancels pending tasks, and cleans up monitoring systems. The scheduler cannot be restarted after
      * shutdown.
      */
    def shutdown(): Unit = {
        cycleTask.cancel(true)
        admissionRegulator.stop()
        concurrencyRegulator.stop()
        top.close()
    }

    /** Updates the number of active workers within configured bounds.
      *
      * Called by the concurrency regulator to adjust worker count:
      *   - Increases workers when system is underutilized
      *   - Decreases workers when detecting scheduling delays
      *   - Maintains count between minWorkers and maxWorkers
      */
    private def updateWorkers(delta: Int) = {
        currentWorkers = Math.max(minWorkers, Math.min(maxWorkers, currentWorkers + delta))
        ensureWorkers()
    }

    /** Ensures required number of workers are allocated and initialized.
      *
      * Creates new workers as needed up to currentWorkers count:
      *   - Allocates worker instances with unique IDs
      *   - Configures worker with scheduler callbacks
      *   - Tracks total allocated workers
      */
    private def ensureWorkers() =
        for (idx <- allocatedWorkers until currentWorkers) {
            workers(idx) =
                new Worker(idx, pool, schedule, steal, clock, timeSliceMs) {
                    def shouldStop(): Boolean = idx >= currentWorkers
                }
            allocatedWorkers += 1
        }

    private val cycleTask =
        timerExecutor.submit(
            (
                () => {
                    val thread = Thread.currentThread()
                    while (!thread.isInterrupted()) {
                        cycleWorkers()
                        LockSupport.parkNanos(cycleIntervalNs)
                    }
                }
            ): Callable[Unit]
        )

    /** Periodically checks worker health and availability.
      *
      * Runs on timer to:
      *   - Detect stalled or blocked workers
      *   - Clear stale worker states
      *   - Maintain accurate worker availability status
      *
      * Critical for work stealing and load balancing decisions.
      */
    private def cycleWorkers(): Unit = {
        try {
            val nowMs    = clock.currentMillis()
            var position = 0
            while (position < currentWorkers) {
                val worker = workers(position)
                if (worker ne null) {
                    val _ = worker.checkAvailability(nowMs)
                }
                position += 1
            }
        } catch {
            case ex if NonFatal(ex) =>
                bug(s"Worker cyclying has failed.", ex)
        }
    }

    @nowarn("msg=unused")
    private val gauges =
        List(
            statsScope.gauge("current_workers")(currentWorkers),
            statsScope.gauge("allocated_workers")(allocatedWorkers),
            statsScope.gauge("load_avg")(loadAvg()),
            statsScope.gauge("flushes")(flushes.sum().toDouble)
        )

    def status(): Status = {
        def workerStatus(i: Int) =
            workers(i) match {
                case null   => null
                case worker => worker.status()
            }
        val (activeThreads, totalThreads) =
            workerExecutor match {
                case exec: ThreadPoolExecutor =>
                    (exec.getActiveCount(), exec.getPoolSize())
                case _ =>
                    (-1, -1)
            }
        Status(
            currentWorkers,
            allocatedWorkers,
            loadAvg(),
            flushes.sum(),
            activeThreads,
            totalThreads,
            (0 until allocatedWorkers).map(workerStatus),
            admissionRegulator.status(),
            concurrencyRegulator.status()
        )
    }
}

object Scheduler {

    private lazy val defaultWorkerExecutor = Executors.newCachedThreadPool(Threads("kyo-scheduler-worker", new Worker.WorkerThread(_)))
    private lazy val defaultClockExecutor  = Executors.newSingleThreadExecutor(Threads("kyo-scheduler-clock"))
    private lazy val defaultTimerExecutor  = Executors.newScheduledThreadPool(2, Threads("kyo-scheduler-timer"))

    val get = new Scheduler()

    /** Configuration parameters controlling worker behavior and performance characteristics.
      *
      * Most applications can use the default worker configuration values, which are tuned for general-purpose workloads. The defaults are
      * configured through system properties and provide good performance across a wide range of scenarios without requiring manual tuning.
      *
      * @param minWorkers
      *   Minimum number of worker threads that will be maintained even under low load. A lower number conserves resources but may require
      *   ramp-up time when load increases. (-Dkyo.scheduler.minWorkers=<value>)
      *
      * @param coreWorkers
      *   Initial worker thread count at scheduler startup. Represents the baseline capacity for handling normal workload.
      *   (-Dkyo.scheduler.coreWorkers=<value>)
      *
      * @param maxWorkers
      *   Maximum worker thread count beyond which the scheduler won't allocate new workers. Prevents unconstrained thread growth under
      *   heavy load. Must be greater than or equal to minWorkers. (-Dkyo.scheduler.maxWorkers=<value>)
      *
      * @param timeSliceMs
      *   Maximum duration a task can run before being preempted. Lower values (e.g. 5ms) ensure more frequent task switching and better
      *   responsiveness. Higher values (e.g. 20ms) reduce context switching overhead but may delay other tasks.
      *   (-Dkyo.scheduler.timeSliceMs=<value>)
      *
      * @param scheduleStride
      *   Number of workers to examine when scheduling a new task. Larger values find better scheduling targets but take longer to make
      *   decisions. (-Dkyo.scheduler.scheduleStride=<value>)
      *
      * @param stealStride
      *   Number of workers to examine when looking for tasks to steal. Larger values improve load balancing but increase steal overhead.
      *   (-Dkyo.scheduler.stealStride=<value>)
      *
      * @param cycleIntervalNs
      *   Interval between worker health checks in nanoseconds. Controls how quickly the scheduler detects and responds to stalled or
      *   blocked workers. (-Dkyo.scheduler.cycleIntervalNs=<value>)
      *
      * @param virtualizeWorkers
      *   When true, uses virtual threads from Project Loom instead of platform threads. Beneficial for workloads with significant I/O or
      *   blocking operations. Requires JDK 21+ and appropriate JVM flags. (-Dkyo.scheduler.virtualizeWorkers=<value>)
      *
      * @param enableTopJMX
      *   Exposes scheduler metrics through JMX for monitoring. Useful for observing scheduler behavior in production.
      *   (-Dkyo.scheduler.enableTopJMX=<value>)
      *
      * @param enableTopConsoleMs
      *   Interval in milliseconds for printing scheduler metrics to console. Zero disables console output. Useful for development and
      *   debugging. (-Dkyo.scheduler.enableTopConsoleMs=<value>)
      *
      * @see
      *   Worker for worker implementation details
      * @see
      *   Scheduler for how workers are managed
      */
    case class Config(
        cores: Int,
        coreWorkers: Int,
        minWorkers: Int,
        maxWorkers: Int,
        scheduleStride: Int,
        stealStride: Int,
        virtualizeWorkers: Boolean,
        timeSliceMs: Int,
        cycleIntervalNs: Int,
        enableTopJMX: Boolean,
        enableTopConsoleMs: Int
    )
    object Config {
        val default: Config = {
            val cores             = Runtime.getRuntime().availableProcessors()
            val coreWorkers       = Math.max(1, Flag("coreWorkers", cores))
            val minWorkers        = Math.max(1, Flag("minWorkers", coreWorkers.toDouble / 2).intValue())
            val maxWorkers        = Math.max(minWorkers, Flag("maxWorkers", coreWorkers * 100))
            val scheduleStride    = Math.max(1, Flag("scheduleStride", cores))
            val stealStride       = Math.max(1, Flag("stealStride", cores * 8))
            val virtualizeWorkers = Flag("virtualizeWorkers", false)
            val timeSliceMs       = Flag("timeSliceMs", 10)
            val cycleIntervalNs   = Flag("cycleIntervalNs", 100000)
            val enableTopJMX      = Flag("enableTopJMX", false)
            val enableTopConsole  = Flag("enableTopConsoleMs", 0)
            Config(
                cores,
                coreWorkers,
                minWorkers,
                maxWorkers,
                scheduleStride,
                stealStride,
                virtualizeWorkers,
                timeSliceMs,
                cycleIntervalNs,
                enableTopJMX,
                enableTopConsole
            )
        }
    }
}
