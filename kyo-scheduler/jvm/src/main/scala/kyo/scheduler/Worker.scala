package kyo.scheduler

import Worker.*
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

    val a1, a2, a3, a4, a5, a6, a7 = 0L // padding

    @volatile private var state: State  = Inactive
    @volatile private var mount: Thread = null

    val b1, b2, b3, b4, b5, b6, b7 = 0L // padding

    @volatile private var currentCycle      = 0L
    @volatile private var currentTask: Task = null

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
        if state == Inactive && stateHandle.compareAndSet(this, Inactive, Active) then
            exec.execute(this)

    def load() =
        var l = queue.size()
        if currentTask != null then
            l += 1
        l
    end load

    def steal(thief: Worker): Task =
        queue.steal(thief.queue)

    def drain(): Unit =
        queue.drain(schedule)

    def cycle(curr: Long): Unit =
        val c = currentTask
        if c != null && currentCycle < curr - 1 then
            c.doPreempt()
    end cycle

    def handleBlocking(): Boolean =
        state match
            case Inactive => false
            case Blocked  => true
            case Active =>
                val mount = this.mount
                mount != null && {
                    val state = mount.getState().ordinal()
                    val blocked =
                        state == Thread.State.BLOCKED.ordinal() ||
                            state == Thread.State.WAITING.ordinal() ||
                            state == Thread.State.TIMED_WAITING.ordinal()
                    if blocked && stateHandle.compareAndSet(this, Active, Blocked) then
                        drain()
                    blocked
                }
        end match
    end handleBlocking

    def run(): Unit =
        mounts += 1
        mount = Thread.currentThread()
        local.set(this)
        var task: Task = null
        while true do
            state = Active
            currentCycle = getCurrentCycle()
            if task == null then
                task = queue.poll()
            if task == null then
                task = stealTask(this)
            if task != null then
                currentTask = task
                executions += 1
                val r =
                    try task.doRun(clock)
                    catch
                        case ex if NonFatal(ex) =>
                            val thread = Thread.currentThread()
                            thread.getUncaughtExceptionHandler().uncaughtException(thread, ex)
                            Task.Done
                currentTask = null
                if r == Task.Preempted then
                    preemptions += 1
                    task = queue.addAndPoll(task)
                else
                    completions += 1
                    task = null
                end if
            else
                state = Inactive
                if queue.isEmpty() ||
                    (!stateHandle.compareAndSet(this, Inactive, Active) &&
                        !stateHandle.compareAndSet(this, Blocked, Active))
                then
                    local.set(null)
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

    override def toString =
        s"Worker(id=$id, blocked=${handleBlocking()}, mount=${mount}, executions=${executions}, " +
            s"preemptions=${preemptions}, completions=${completions})"

end Worker

private object Worker:

    private[Worker] object internal:
        type State = Int
        val Inactive = 0
        val Active   = 1
        val Blocked  = 2

        val stateHandle: VarHandle =
            MethodHandles.privateLookupIn(classOf[Worker], MethodHandles.lookup())
                .findVarHandle(classOf[Worker], "state", classOf[Int])

        val local = new ThreadLocal[Worker]
    end internal

    def current(): Worker = internal.local.get()
end Worker
