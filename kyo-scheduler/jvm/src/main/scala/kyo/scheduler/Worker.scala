package kyo.scheduler

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.Executor
import kyo.stats.internal.MetricReceiver
import scala.util.control.NonFatal

final private class Worker(
    id: Int,
    exec: Executor,
    scheduleTask: (Task, Worker) => Unit,
    stealTask: Worker => Task,
    getCurrentCycle: () => Long,
    clock: InternalClock
) extends Runnable:

    import Worker.internal.*
    import Worker.internal.State.*

    val a1, a2, a3, a4, a5, a6, a7 = 0L // padding

    @volatile private var state: State  = Inactive
    @volatile private var mount: Thread = null

    val b1, b2, b3, b4, b5, b6, b7 = 0L // padding

    @volatile private var currentCycle = 0L
    @volatile private var runningTask  = false

    private var executions  = 0L
    private var preemptions = 0L
    private var completions = 0L
    private var mounts      = 0L

    private val queue = Queue[Task]()

    private val schedule = scheduleTask(_, this)

    def enqueue(t: Task, force: Boolean = false): Boolean =
        val proceed = force || !handleBlocking()
        if proceed then
            queue.add(t)
            wakeup()
        end if
        proceed
    end enqueue

    def wakeup() =
        if state.isInactive && stateHandle.compareAndSet(this, state, Active) then
            exec.execute(this)

    def load() =
        var l = queue.size()
        if runningTask then
            l += 1
        l
    end load

    def steal(thief: Worker): Task =
        queue.steal(thief.queue)

    def drain(): Unit =
        queue.drain(schedule)

    def preempt(): Boolean =
        state.isPreempt

    def doPreempt(): Unit =
        val state = this.state
        if !state.isPreempt && !state.isInactive then
            stateHandle.compareAndSet(this, state, state.toPreempt)
            ()
    end doPreempt

    def cycle(curr: Long): Unit =
        if currentCycle < curr - 1 then
            doPreempt()

    def handleBlocking(): Boolean =
        val state = this.state
        state.isBlocked || {
            !state.isInactive && {
                val mount = this.mount
                mount != null && {
                    val threadState = mount.getState().ordinal()
                    val blocked =
                        threadState == Thread.State.BLOCKED.ordinal() ||
                            threadState == Thread.State.WAITING.ordinal() ||
                            threadState == Thread.State.TIMED_WAITING.ordinal()
                    if blocked && stateHandle.compareAndSet(this, state, Blocked) then
                        drain()
                    blocked
                }
            }
        }
    end handleBlocking

    def run(): Unit =
        mounts += 1
        mount = Thread.currentThread()
        setCurrent(this)
        var task: Task = null
        while true do
            state = Active
            currentCycle = getCurrentCycle()
            if task == null then
                task = queue.poll()
            if task == null then
                task = stealTask(this)
            if task != null then
                executions += 1
                runningTask = true
                val r =
                    try task.doRun(clock)
                    catch
                        case ex if NonFatal(ex) =>
                            val thread = Thread.currentThread()
                            thread.getUncaughtExceptionHandler().uncaughtException(thread, ex)
                            Task.Done
                runningTask = false
                if r == Task.Preempted then
                    preemptions += 1
                    task = queue.addAndPoll(task)
                else
                    completions += 1
                    task = null
                end if
            else
                state = Inactive
                if queue.isEmpty() || {
                        val state = this.state
                        state.isInactive && !stateHandle.compareAndSet(this, state, Active)
                    }
                then
                    clearCurrent()
                    mount = null
                    return
                end if
            end if
        end while
    end run

    private def registerStats() =
        val scope    = statsScope("worker", id.toString)
        val receiver = MetricReceiver.get
        receiver.gauge(scope, "executions")(executions.toDouble)
        receiver.gauge(scope, "preemptions")(preemptions.toDouble)
        receiver.gauge(scope, "completions")(completions.toDouble)
        receiver.gauge(scope, "queue_size")(queue.size())
        receiver.gauge(scope, "current_cycle")(currentCycle.toDouble)
        receiver.gauge(scope, "mounts")(mounts.toDouble)
    end registerStats
    registerStats()
end Worker

private object Worker:

    final class WorkerThread(init: Runnable) extends Thread(init):
        var current: Worker = null

    private[Worker] object internal:

        opaque type State = Int
        object State:
            extension (s: State)
                inline def toPreempt: State   = -Math.abs(s)
                inline def isPreempt: Boolean = s < 0
                inline def isInactive: Boolean =
                    Math.abs(s) == Inactive
                inline def isActive: Boolean =
                    Math.abs(s) == Active
                inline def isBlocked: Boolean =
                    Math.abs(s) == Blocked
            end extension
        end State
        inline def Inactive: State = 0
        inline def Active: State   = 1
        inline def Blocked: State  = 2

        val stateHandle: VarHandle =
            MethodHandles.privateLookupIn(classOf[Worker], MethodHandles.lookup())
                .findVarHandle(classOf[Worker], "state", classOf[Int])

        val local = new ThreadLocal[Worker]

        def setCurrent(w: Worker): Unit =
            Thread.currentThread() match
                case t: WorkerThread => t.current = w
                case _               => local.set(w)

        def clearCurrent(): Unit =
            Thread.currentThread() match
                case t: WorkerThread => t.current = null
                case _               => local.set(null)
    end internal

    def current(): Worker =
        Thread.currentThread() match
            case t: WorkerThread => t.current
            case _               => internal.local.get()
end Worker
