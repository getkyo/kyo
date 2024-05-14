package kyo.scheduler

import java.lang.StackWalker.StackFrame
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.Executor
import java.util.concurrent.atomic.LongAdder
import kyo.stats.internal.MetricReceiver
import scala.util.control.NonFatal

final private class Worker(
    id: Int,
    exec: Executor,
    scheduleTask: (Task, Worker) => Unit,
    stealTask: Worker => Task,
    getCurrentCycle: () => Long,
    clock: InternalClock
) extends Runnable {

    import Worker.internal.*

    val a1, a2, a3, a4, a5, a6, a7 = 0L // padding

    @volatile private var running       = false
    @volatile private var mount: Thread = null

    val b1, b2, b3, b4, b5, b6, b7 = 0L // padding

    @volatile private var currentCycle = 0L

    val c1, c2, c3, c4, c5, c6, c7 = 0L // padding

    @volatile private var currentTask: Task = null

    private var executions  = 0L
    private var preemptions = 0L
    private var completions = 0L
    private var mounts      = 0L
    private var stolenTasks = 0L

    private val lostTasks = new LongAdder

    private val queue = new Queue[Task]()

    private val schedule = scheduleTask(_, this)

    def enqueue(cycle: Long, task: Task, force: Boolean = false): Boolean = {
        val proceed = force || checkAvailability(cycle)
        if (proceed) {
            queue.add(task)
            wakeup()
        }
        proceed
    }

    def wakeup() =
        if (!running && runningHandle.compareAndSet(this, false, true))
            exec.execute(this)

    def load() = {
        var load = queue.size()
        if (currentTask != null)
            load += 1
        load
    }

    def stealingBy(thief: Worker): Task = {
        val task = queue.stealingBy(thief.queue)
        if (task != null)
            lostTasks.add(thief.queue.size() + 1)
        task
    }

    def drain(): Unit =
        if (!queue.isEmpty())
            queue.drain(schedule)

    def cycle(cycles: Long): Unit = {
        val task = currentTask
        if (task != null && currentCycle < cycles - 1)
            task.doPreempt()
        checkAvailability(cycles)
        ()
    }

    def checkAvailability(cycles: Long): Boolean = {
        val available = !isStalled(cycles) && !isBlocked()
        if (!available)
            drain()
        available
    }

    def isStalled(cycles: Long): Boolean =
        running && currentCycle < cycles - 2

    def isBlocked(): Boolean = {
        val mount = this.mount
        mount != null && {
            val state = mount.getState().ordinal()
            state == Thread.State.BLOCKED.ordinal() ||
            state == Thread.State.WAITING.ordinal() ||
            state == Thread.State.TIMED_WAITING.ordinal()
        }
    }

    def run(): Unit = {
        mounts += 1
        mount = Thread.currentThread()
        setCurrent(this)
        var task: Task = null
        while (true) {
            val cycle = getCurrentCycle()
            if (currentCycle != cycle)
                currentCycle = cycle
            if (task == null)
                task = queue.poll()
            if (task == null) {
                task = stealTask(this)
                if (task != null)
                    stolenTasks += queue.size() + 1
            }
            if (task != null) {
                executions += 1
                if (runTask(task) == Task.Preempted) {
                    preemptions += 1
                    task = queue.addAndPoll(task)
                } else {
                    completions += 1
                    task = null
                }
            } else {
                running = false
                if (queue.isEmpty() || !runningHandle.compareAndSet(this, false, true)) {
                    mount = null
                    clearCurrent()
                    return
                }
            }
        }
    }

    private def runTask(task: Task): Task.Result = {
        currentTask = task
        val start = clock.currentMillis()
        try
            task.run(start, clock)
        catch {
            case ex if NonFatal(ex) =>
                val thread = Thread.currentThread()
                thread.getUncaughtExceptionHandler().uncaughtException(thread, ex)
                Task.Done
        } finally {
            currentTask = null
            task.addRuntime((clock.currentMillis() - start).asInstanceOf[Int])
        }
    }

    private def registerStats() = {
        val scope    = statsScope("worker", id.toString)
        val receiver = MetricReceiver.get
        receiver.gauge(scope, "executions")(executions.toDouble)
        receiver.gauge(scope, "preemptions")(preemptions.toDouble)
        receiver.gauge(scope, "completions")(completions.toDouble)
        receiver.gauge(scope, "queue_size")(queue.size())
        receiver.gauge(scope, "current_cycle")(currentCycle.toDouble)
        receiver.gauge(scope, "mounts")(mounts.toDouble)
        receiver.gauge(scope, "stolen_tasks")(stolenTasks.toDouble)
        receiver.gauge(scope, "lost_tasks")(lostTasks.sum().toDouble)
    }
    registerStats()

    def status(): Worker.WorkerStatus = {
        val taskStatus =
            currentTask match {
                case null => null
                case task => task.status()
            }
        val (thread, frame) =
            mount match {
                case null =>
                    (null, null)
                case mount: Thread =>
                    (mount.getName(), mount.getStackTrace().head.toString())
            }
        Worker.WorkerStatus(
            id,
            running,
            thread,
            isBlocked(),
            isStalled(getCurrentCycle()),
            frame,
            executions,
            preemptions,
            completions,
            stolenTasks,
            lostTasks.sum(),
            load(),
            mounts,
            currentCycle,
            taskStatus
        )
    }
}

private object Worker {

    case class WorkerStatus(
        id: Int,
        running: Boolean,
        mount: String,
        isBlocked: Boolean,
        isStalled: Boolean,
        frame: String,
        executions: Long,
        preemptions: Long,
        completions: Long,
        stolenTasks: Long,
        lostTasks: Long,
        load: Int,
        mounts: Long,
        currentCycle: Long,
        currentTask: Task.Status
    ) {
        infix def -(other: WorkerStatus): WorkerStatus =
            WorkerStatus(
                id,
                running,
                mount,
                isBlocked,
                isStalled,
                frame,
                executions - other.executions,
                preemptions - other.preemptions,
                completions - other.completions,
                stolenTasks - other.stolenTasks,
                lostTasks - other.lostTasks,
                load,
                mounts - other.mounts,
                currentCycle,
                currentTask
            )
    }

    final class WorkerThread(init: Runnable) extends Thread(init) {
        var currentWorker: Worker = null
    }

    private[Worker] object internal {

        val runningHandle: VarHandle =
            MethodHandles.privateLookupIn(classOf[Worker], MethodHandles.lookup())
                .findVarHandle(classOf[Worker], "running", classOf[Boolean])

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
