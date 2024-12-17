package kyo.scheduler

import Worker.State
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.top.WorkerStatus
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
  * ==Task Execution==
  *
  * Workers maintain a priority queue of pending tasks and execute them according to their priority ordering. Each task runs until either:
  *   - It completes naturally
  *   - It exceeds its time slice and is preempted
  *   - The worker is instructed to stop
  *
  * ==Preemption==
  *
  * The worker monitors task execution time and preempts long-running tasks that exceed the configured time slice. Preempted tasks are
  * re-queued to allow other tasks to execute, implementing fair scheduling. This prevents individual tasks from monopolizing the worker
  * thread.
  *
  * ==Work Stealing==
  *
  * When a worker's queue is empty, it attempts to steal tasks from other workers that have higher load. The stealing mechanism uses atomic
  * batch transfers to move multiple tasks at once, maintaining priority ordering while balancing load across workers. This improves overall
  * scheduler throughput by keeping workers busy.
  *
  * ==State Management==
  *
  * The worker transitions between three states:
  *   - Idle: No tasks to execute
  *   - Running: Actively executing tasks
  *   - Stalled: Detected as blocked or exceeding time slice
  *
  * Thread state monitoring detects when workers become blocked on I/O or synchronization, allowing the scheduler to compensate by not
  * scheduling new tasks to blocked workers.
  *
  * ==Thread Management==
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
  *   Queue for details on the underlying task queue implementation
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

    val a1, a2, a3, a4, a5, a6, a7 = 0L // padding

    private val state = new AtomicReference[State](State.Idle)

    @volatile private var mount: Thread = null

    val b1, b2, b3, b4, b5, b6, b7 = 0L // padding

    @volatile private var taskStartMs       = 0L
    @volatile private var currentTask: Task = null

    val c1, c2, c3, c4, c5, c6, c7 = 0L // padding

    private var executions  = 0L
    private var preemptions = 0L
    private var completions = 0L
    private var mounts      = 0L
    private var stolenTasks = 0L

    private val lostTasks = new LongAdder

    private val queue = new Queue[Task]()

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

    /** Checks if this worker can accept new tasks by verifying:
      *   - Not stalled on a long-running task
      *   - Not in Stalled state
      *   - Thread not blocked on I/O or synchronization
      *
      * If checks fail while Running, transitions to Stalled and drains queue. Used by scheduler to skip workers that can't make progress.
      */
    def checkAvailability(nowMs: Long): Boolean = {
        val st        = this.state.get()
        val available = !checkStalling(nowMs) && (st ne State.Stalled) && !isBlocked()
        if (!available && (st eq State.Running) && state.compareAndSet(State.Running, State.Stalled))
            drain()
        available
    }

    private def checkStalling(nowMs: Long): Boolean = {
        val task    = currentTask
        val start   = taskStartMs
        val stalled = (task ne null) && start > 0 && start < nowMs - timeSliceMs
        if (stalled) {
            task.doPreempt()
        }
        stalled
    }

    private def isBlocked(): Boolean = {
        val mount = this.mount
        (mount ne null) && {
            val state = mount.getState().ordinal()
            state == Thread.State.BLOCKED.ordinal() ||
            state == Thread.State.WAITING.ordinal() ||
            state == Thread.State.TIMED_WAITING.ordinal()
        }
    }

    def run(): Unit = {
        // Set up worker state
        mounts += 1
        mount = Thread.currentThread()
        setCurrent(this)
        var task: Task = null

        while (true) {
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
                executions += 1
                if (runTask(task) == Task.Preempted) {
                    // Task was preempted - add it back to queue and get next task
                    preemptions += 1
                    task = queue.addAndPoll(task)
                } else {
                    // Task completed normally
                    completions += 1
                    task = null
                }
            } else {
                // No tasks available - prepare to go idle
                state.set(State.Idle)
                if (queue.isEmpty() || !state.compareAndSet(State.Idle, State.Running)) {
                    // Either queue is empty or another thread changed our state
                    // Clean up and exit
                    mount = null
                    clearCurrent()
                    return
                }
            }

            // Check if we should stop processing tasks
            if (shouldStop()) {
                state.set(State.Idle)
                // Reschedule current task if we have one
                if (task ne null) schedule(task)
                // Drain remaining tasks from queue
                drain()
                return
            }
        }
    }

    private def runTask(task: Task): Task.Result = {
        currentTask = task
        val start = clock.currentMillis()
        taskStartMs = start
        try
            task.run(start, clock)
        catch {
            case ex if NonFatal(ex) =>
                val thread = Thread.currentThread()
                thread.getUncaughtExceptionHandler().uncaughtException(thread, ex)
                Task.Done
        } finally {
            currentTask = null
            taskStartMs = 0
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
                    (mount.getName(), mount.getStackTrace().head.toString())
            }
        WorkerStatus(
            id,
            state.get() eq State.Running,
            thread,
            frame,
            isBlocked(),
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
