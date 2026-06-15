package kyo.scheduler

import Worker.State
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.top.WorkerStatus
import kyo.scheduler.util.ThreadUserTime
import scala.annotation.nowarn
import scala.util.control.NonFatal

/** A scheduler worker that executes tasks with preemption and work stealing capabilities.
  *
  * This worker implementation provides core task execution functionality for the scheduler, including:
  *   - Task queuing and execution
  *   - Preemptive scheduling with time slicing
  *   - Work stealing for load balancing
  *   - Thread state monitoring and stall detection
  *   - Performance metrics collection
  *
  * #### Task Execution
  *
  * Workers maintain a priority queue of pending tasks and execute them according to their priority ordering. Each task runs until either:
  *   - It completes naturally
  *   - It exceeds its time slice and is preempted
  *   - The worker is instructed to stop
  *
  * #### Preemption
  *
  * The worker monitors task execution time and preempts long-running tasks that exceed the configured time slice. Preempted tasks are
  * re-queued to allow other tasks to execute, implementing fair scheduling. This prevents individual tasks from monopolizing the worker
  * thread.
  *
  * #### Work Stealing
  *
  * When a worker's queue is empty, it attempts to steal tasks from other workers that have higher load. The stealing mechanism uses atomic
  * batch transfers to move multiple tasks at once, maintaining priority ordering while balancing load across workers. This improves overall
  * scheduler throughput by keeping workers busy.
  *
  * #### State Management
  *
  * The worker transitions between three states:
  *   - Idle: No tasks to execute
  *   - Running: Actively executing tasks
  *   - Stalled: Detected as blocked or exceeding time slice
  *
  * Thread state monitoring detects when workers become blocked on I/O or synchronization, allowing the scheduler to compensate by not
  * scheduling new tasks to blocked workers.
  *
  * #### Thread Management
  *
  * Workers use an ephemeral thread model where they acquire threads from the executor only when actively processing tasks:
  *   - Thread is mounted when the worker begins processing tasks
  *   - Thread is released back to the pool when the worker becomes idle
  *   - New thread is requested from executor when work arrives for an idle worker
  *
  * The provided executor may be configured to start new virtual threads instead of platform threads when Project Loom is available. The
  * worker treats virtual and platform threads identically, with thread management handled transparently by the provided executor instance.
  *
  * @param id
  *   Unique identifier for this worker
  * @param exec
  *   Executor for running the worker thread
  * @param scheduleTask
  *   Function to schedule a task on a specific worker
  * @param stealTask
  *   Function to steal tasks from another worker
  * @param clock
  *   Internal clock for timing operations
  * @param timeSliceMs
  *   Maximum time slice for task execution before preemption
  *
  * @see
  *   WorkerQueue for details on the underlying task queue implementation
  * @see
  *   Task for the task execution model
  * @see
  *   Scheduler for how workers are managed
  */
abstract private class Worker(
    id: Int,
    exec: Executor,
    scheduleTask: (Task, Worker) => Unit,
    stealTask: Worker => Task,
    clock: InternalClock,
    timeSliceMs: Int
) extends Runnable {

    import Worker.internal.*

    protected def shouldStop(): Boolean

    /** Returns the current interrupt epoch from the scheduler. Each call to Scheduler.notifyInterrupt
      * bumps this value; rebalance compares it against lastRebuiltEpoch to gate queue rebuilds.
      */
    protected def currentInterruptEpoch(): Long

    val a1, a2, a3, a4, a5, a6, a7 = 0L // padding

    private val state = new AtomicReference[State](State.Idle)

    @volatile private[scheduler] var mount: Thread = null
    // -1 = not mounted (idle or never started). Set to the thread's CPU-time ID
    // at the start of run(), reset to -1 on exit. Published by currentTask volatile.
    private[scheduler] var mountId: Long = -1L
    // Not volatile: written by BlockingMonitor timer thread (~2ms), read by cycleWorkers timer
    // thread (~100μs). Stale reads are acceptable: this is a scheduling heuristic, not a
    // correctness constraint. Worst case: a blocked worker accepts one extra task before
    // detection, which is then drained on the next cycle.
    private[scheduler] var blocked: Boolean = false

    // Coordinates between BlockingMonitor's interrupt dispatch and task cleanup.
    // Prevents the TOCTOU race where Thread.interrupt() dispatched for task A arrives
    // after task B starts on the same worker. Both monitor and worker acquire this lock:
    // monitor before dispatch, worker before clearing currentTask + Thread.interrupted().
    private[scheduler] val interruptLock = new java.util.concurrent.atomic.AtomicBoolean(false)

    @scala.annotation.tailrec
    private def acquireInterruptLock(): Unit =
        if (!interruptLock.compareAndSet(false, true))
            acquireInterruptLock()

    val b1, b2, b3, b4, b5, b6, b7 = 0L // padding

    @volatile private var taskStartMs                  = 0L
    @volatile private[scheduler] var currentTask: Task = null

    val c1, c2, c3, c4, c5, c6, c7 = 0L // padding

    private var executions  = 0L
    private var preemptions = 0L
    private var completions = 0L
    private var mounts      = 0L
    private var stolenTasks = 0L

    // Epoch and timestamp of this worker's last queue rebuild. Single-thread-owned by the
    // worker's own run loop (read and written only inside rebalance), so plain vars suffice.
    private var lastRebuiltEpoch = 0L
    private var lastRebuildMs    = 0L

    private val lostTasks = new LongAdder

    private val queue = new WorkerQueue()

    private val schedule = scheduleTask(_, this)

    /** Adds a task to this worker's queue and ensures the worker is running. Called by the scheduler when assigning new work.
      */
    def enqueue(task: Task): Unit = {
        queue.add(task)
        wakeup()
    }

    /** Transitions the worker from Idle to Running state and requests a new thread from the executor if successful. Used when new work
      * arrives for an idle worker.
      */
    def wakeup() = {
        if ((state.get() eq State.Idle) && state.compareAndSet(State.Idle, State.Running))
            exec.execute(this)
    }

    /** Returns the current task count including both queued tasks and any actively executing task. Used by the scheduler for load balancing
      * decisions.
      */
    def load() = {
        var load = queue.size()
        if (currentTask ne null)
            load += 1
        load
    }

    /** Attempts to steal tasks from this worker's queue into the thief's queue. Updates metrics to track task movement between workers.
      * Returns null if no tasks were stolen.
      */
    def stealingBy(thief: Worker): Task = {
        val task = queue.stealingBy(thief.queue)
        if (task ne null)
            lostTasks.add(thief.queue.size() + 1)
        task
    }

    /** Transfers all tasks from this worker's queue to the scheduler for reassignment. Called when the worker becomes stalled to allow
      * other workers to take over the work.
      */
    def drain(): Unit =
        queue.drain(schedule)

    /** Re-heapifies this worker's own queue when an interrupt has reset a queued task's runtime in place.
      *
      * Gated on two conditions so the common case stays off the hot path: the epoch from currentInterruptEpoch
      * (bumped by Scheduler.notifyInterrupt on every real interrupt) must have advanced past lastRebuiltEpoch,
      * and at least minInterval milliseconds must have elapsed since the last rebuild. When both hold,
      * queue.rebuild() re-establishes priority order in O(n), sifting the reset task (now lowest runtime) to
      * the head so the next poll returns it within a bounded, load-independent delay. Operates only on this
      * worker's own queue.
      */
    private def rebalance(): Unit = {
        val epoch = currentInterruptEpoch()
        if (epoch != lastRebuiltEpoch) {
            val now = clock.currentMillis()
            if (now - lastRebuildMs >= minInterval()) {
                queue.rebuild()
                lastRebuiltEpoch = epoch
                lastRebuildMs = now
            }
        }
    }

    /** Checks if this worker can accept new tasks by verifying:
      *   - Not stalled on a long-running task
      *   - Not in Stalled state
      *   - Thread not blocked on I/O or synchronization
      *
      * If checks fail while Running, transitions to Stalled and drains queue. Used by scheduler to skip workers that can't make progress.
      */
    def checkAvailability(nowMs: Long): Boolean = {
        val st = this.state.get()
        // Evaluate checkStalling for any non-blocked worker, not just a Running one: a worker already in Stalled
        // state but pinned on a long-running, preemptible CPU-bound task must still receive doPreempt once work
        // queues up behind it. Otherwise a worker that stalled while its queue was momentarily empty (no doPreempt
        // issued) stays Stalled forever, never re-checked, spinning the task while its queue grows unbounded, which
        // deadlocks the scheduler under CPU-bound load. Blocked workers are excluded: their task is parked on I/O,
        // not burning a time slice, so it is the BlockingMonitor's Thread.interrupt (not a time-slice doPreempt)
        // that frees them. Issuing doPreempt against a blocked task is pointless and, on Native, unsafe.
        val stalling  = !blocked && checkStalling(nowMs)
        val available = (st ne State.Stalled) && !blocked && !stalling
        if (!available && (st eq State.Running) && state.compareAndSet(State.Running, State.Stalled))
            drain()
        available
    }

    private def checkStalling(nowMs: Long): Boolean = {
        val task    = currentTask
        val start   = taskStartMs
        val stalled = (task ne null) && start > 0 && start < nowMs - timeSliceMs
        if (stalled) {
            // Preempt a long-running task even when this worker's own queue is empty. The task may be
            // pinned on work that cannot progress until a task stranded on another worker's queue runs:
            // e.g. a fiber blocked in runAndBlock parks its carrier, and the completer that would
            // unblock it can be enqueued onto that now-parked worker (the producer cannot avoid this:
            // a worker can block after a task is submitted to it). The pinned worker is then the only
            // one that can make progress, but it never would, because with an empty local queue it has
            // no reason to yield and run() never reaches the steal path. Preempting unconditionally lets
            // run() attempt a steal and pick that stranded work up. With local work queued the behavior
            // is unchanged: the preempted task interleaves with the queued tasks.
            task.doPreempt()
        }
        stalled
    }

    def run(): Unit = {
        Thread.interrupted() // clear stale interrupt from pool reuse
        // Set up worker state
        mounts += 1
        mount = Thread.currentThread()
        mountId = ThreadUserTime.currentThreadId()
        setCurrent(this)
        var task: Task = null

        try
            while (!shouldStop()) {
                // Re-sift the queue if an interrupt reset a queued task, before polling so the
                // reset victim reaches the head and the next poll returns it (epoch-and-time-gated).
                rebalance()

                // Mark worker as actively running
                state.set(State.Running)

                if (task eq null)
                    // Try to get a task from our own queue first
                    task = queue.poll()

                if (task eq null) {
                    // If our queue is empty, try to steal work from another worker
                    task = stealTask(this)
                    if (task ne null)
                        // Track number of stolen tasks including batch size
                        stolenTasks += queue.size() + 1
                }

                if (task ne null) {
                    // We have a task to execute
                    val current = task
                    task = null
                    executions += 1
                    if (runTask(current) == Task.Preempted) {
                        preemptions += 1
                        if (current.needsInterrupt())
                            // Interrupted during its slice: never requeue (a racy runtime key could starve
                            // it). Run it again immediately; eval observes the interrupt and finalizes.
                            task = current
                        else {
                            // Add the preempted task back and pick the next local task. addAndPoll returns
                            // `current` itself only when the local queue was empty; in that case attempt a
                            // steal before resuming `current`. This is what lets a worker pinned on a task
                            // that cannot progress (until work stranded on a blocked worker's queue runs)
                            // yield its core to that stranded work instead of spinning on `current`. With
                            // local work queued, addAndPoll returns it and we run it as before.
                            val next = queue.addAndPoll(current)
                            if (next ne current)
                                task = next
                            else {
                                val stolen = stealTask(this)
                                if (stolen ne null) {
                                    stolenTasks += queue.size() + 1
                                    // Re-enqueue `current` (it keeps its accumulated runtime, so it sorts
                                    // behind the stolen work) and run the stolen task now.
                                    queue.add(current)
                                    task = stolen
                                } else
                                    task = current
                            }
                        }
                    } else {
                        // Task completed normally
                        completions += 1
                    }
                } else {
                    // No tasks available - prepare to go idle
                    state.set(State.Idle)
                    if (queue.isEmpty() || !state.compareAndSet(State.Idle, State.Running))
                        return
                }
            }
        finally {
            // Clean up on any exit path: idle, shouldStop, or fatal Throwable escaping runTask
            state.set(State.Idle)
            if (task ne null) queue.add(task)
            drain()
            mountId = -1L
            blocked = false
            mount = null
            clearCurrent()
        }
    }

    private def runTask(task: Task): Task.Result = {
        currentTask = task
        val start = clock.currentMillis()
        taskStartMs = start
        try
            task.run(start, clock, Long.MaxValue)
        catch {
            case ex if NonFatal(ex) =>
                val thread = Thread.currentThread()
                thread.getUncaughtExceptionHandler().uncaughtException(thread, ex)
                Task.Done
        } finally {
            acquireInterruptLock()
            currentTask = null
            taskStartMs = 0
            Thread.interrupted() // clear stale interrupt flag before next task
            interruptLock.set(false)
            task.addRuntime((clock.currentMillis() - start).asInstanceOf[Int])
        }
    }

    @nowarn("msg=unused")
    private val gauges =
        List(
            statsScope.gauge("queue_size")(queue.size()),
            statsScope.counterGauge("executions")(executions),
            statsScope.counterGauge("preemptions")(preemptions),
            statsScope.counterGauge("completions")(completions),
            statsScope.counterGauge("mounts")(mounts),
            statsScope.counterGauge("stolen_tasks")(stolenTasks),
            statsScope.counterGauge("lost_tasks")(lostTasks.sum())
        )

    def status(): WorkerStatus = {
        val (thread, frame) =
            mount match {
                case null =>
                    ("", "")
                case mount: Thread =>
                    val trace = mount.getStackTrace()
                    (mount.getName(), if (trace.nonEmpty) trace.head.toString() else "")
            }
        WorkerStatus(
            id,
            state.get() eq State.Running,
            thread,
            frame,
            blocked,
            checkStalling(clock.currentMillis()),
            executions,
            preemptions,
            completions,
            stolenTasks,
            lostTasks.sum(),
            load(),
            mounts
        )
    }
}

private object Worker {

    final class WorkerThread(init: Runnable) extends Thread(init) {
        var currentWorker: Worker = null
    }

    sealed trait State
    object State {
        case object Idle    extends State
        case object Running extends State
        case object Stalled extends State
    }

    private[Worker] object internal {

        val local = new ThreadLocal[Worker]

        def setCurrent(worker: Worker): Unit =
            Thread.currentThread() match {
                case t: WorkerThread => t.currentWorker = worker
                case _               => local.set(worker)
            }

        def clearCurrent(): Unit =
            Thread.currentThread() match {
                case t: WorkerThread => t.currentWorker = null
                case _               => local.set(null)
            }
    }

    def current(): Worker =
        Thread.currentThread() match {
            case t: WorkerThread => t.currentWorker
            case _               => internal.local.get()
        }
}
