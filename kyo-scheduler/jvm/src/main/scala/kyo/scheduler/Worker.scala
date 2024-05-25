package kyo.scheduler

import java.lang.StackWalker.StackFrame
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.Executor
import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.top.WorkerStatus
import scala.util.control.NonFatal

abstract private class Worker(
    id: Int,
    exec: Executor,
    scheduleTask: (Task, Worker) => Unit,
    stealTask: Worker => Task,
    clock: InternalClock,
    timeSliceMs: Int,
    javaInterruptAfterMs: Int
) extends Runnable {

    import Worker.internal.*

    protected def shouldStop(): Boolean

    val a1, a2, a3, a4, a5, a6, a7 = 0L // padding

    @volatile private var running       = false
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

    def enqueue(task: Task): Unit = {
        queue.add(task)
        wakeup()
    }

    def wakeup() =
        if (!running && runningHandle.compareAndSet(this, false, true))
            exec.execute(this)

    def load() = {
        var load = queue.size()
        if (currentTask ne null)
            load += 1
        load
    }

    def stealingBy(thief: Worker): Task = {
        val task = queue.stealingBy(thief.queue)
        if (task ne null)
            lostTasks.add(thief.queue.size() + 1)
        task
    }

    def drain(): Unit =
        queue.drain(schedule)

    def checkAvailability(nowMs: Long): Boolean = {
        val available = !checkStalling(nowMs) && !isBlocked()
        if (!available)
            drain()
        available
    }

    private def checkStalling(nowMs: Long): Boolean = {
        val task    = currentTask
        val start   = taskStartMs
        val stalled = (task ne null) && start > 0 && start < nowMs - timeSliceMs
        if (stalled) {
            task.doPreempt()
            if (javaInterruptAfterMs > 0) {
                val thread = mount
                if (thread != null && (currentTask eq task) && start < nowMs - javaInterruptAfterMs && !thread.isInterrupted())
                    thread.interrupt()
            }
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
        mounts += 1
        mount = Thread.currentThread()
        setCurrent(this)
        var task: Task = null
        while (true) {
            if (task eq null)
                task = queue.poll()
            if (task eq null) {
                task = stealTask(this)
                if (task ne null)
                    stolenTasks += queue.size() + 1
            }
            if (task ne null) {
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
            if (shouldStop()) {
                running = false
                if (task ne null) schedule(task)
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
            running,
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
