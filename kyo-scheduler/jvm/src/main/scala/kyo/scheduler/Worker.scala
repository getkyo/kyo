package kyo.scheduler

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.util.Queue
import kyo.stats.internal.MetricReceiver

final private class Worker(
    id: Int,
    exec: Executor,
    scheduleTask: (Task, Worker) => Unit,
    stealTask: Worker => Task,
    getCurrentCycle: () => Long
) extends Runnable:

    private val running = new AtomicBoolean

    @volatile private var mount: Thread     = null
    @volatile private var currentCycle      = 0L
    @volatile private var currentTask: Task = null

    private var executions  = 0L
    private var preemptions = 0L
    private var completions = 0L
    private val mounts      = new LongAdder

    private val queue = Queue[Task]()

    private val schedule = scheduleTask(_, this)

    def enqueue(t: Task): Boolean =
        val blocked = handleBlocking()
        if !blocked then
            queue.add(t)
            wakeup()
        end if
        !blocked
    end enqueue

    def wakeup() =
        if !running.get() && running.compareAndSet(false, true) then
            mounts.increment()
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
            c.preempt()
    end cycle

    def handleBlocking() =
        val m = mount
        val r = m != null && m.getState().ordinal() == Thread.State.BLOCKED.ordinal()
        if r then
            drain()
        r
    end handleBlocking

    def run(): Unit =
        mount = Thread.currentThread()
        Worker.local.set(this)
        var task: Task = null
        while true do
            currentCycle = getCurrentCycle()
            if task == null then
                task = queue.poll()
            if task == null then
                task = stealTask(this)
            if task != null then
                currentTask = task
                executions += 1
                val r = task.run()
                currentTask = null
                if r == Task.Preempted then
                    preemptions += 1
                    task = queue.addAndPoll(task)
                else
                    completions += 1
                    task = null
                end if
            else
                running.set(false)
                if queue.isEmpty() || !running.compareAndSet(false, true) then
                    Worker.local.set(null)
                    mount = null
                    return
                end if
            end if
        end while
    end run

    private def registerStats() =
        val scope    = List("kyo", "scheduler", "worker", id.toString)
        val receiver = MetricReceiver.get
        receiver.gauge(scope, "executions")(executions.toDouble)
        receiver.gauge(scope, "preemptions")(preemptions.toDouble)
        receiver.gauge(scope, "completions")(completions.toDouble)
        receiver.gauge(scope, "queue_size")(queue.size())
        receiver.gauge(scope, "current_cycle")(currentCycle.toDouble)
        receiver.gauge(scope, "mounts")(mounts.sum().toDouble)
    end registerStats
    registerStats()

end Worker

private object Worker:
    private val local     = new ThreadLocal[Worker]
    def current(): Worker = local.get()
end Worker
