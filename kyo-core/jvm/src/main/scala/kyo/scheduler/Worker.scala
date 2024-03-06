package kyo.scheduler

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder
import kyo.Stats
import kyo.scheduler.util.Queue

final private class Worker(id: Int, scope: Stats, exec: Executor) extends Runnable:

    private val running = new AtomicBoolean

    @volatile private var mount: Thread     = null
    @volatile private var currentCycle      = 0L
    @volatile private var currentTask: Task = null

    private val queue = Queue[Task]()

    private val schedule = Scheduler.schedule(_, this)

    def enqueue(t: Task): Boolean =
        val blocked = handleBlocking()
        if !blocked then
            stats.submissions.increment()
            queue.add(t)
            wakeup()
        end if
        !blocked
    end enqueue

    def wakeup() =
        if !running.get() && running.compareAndSet(false, true) then
            stats.mounts.increment()
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
        val r = m != null && m.getState() == Thread.State.BLOCKED
        if r then
            drain()
        r
    end handleBlocking

    def run(): Unit =
        mount = Thread.currentThread()
        Worker.local.set(this)
        var task: Task = null
        while true do
            currentCycle = Coordinator.currentCycle()
            if task == null then
                task = queue.poll()
            if task == null then
                task = steal(this)
            if task != null then
                currentTask = task
                stats.executions += 1
                val r = task.run()
                currentTask = null
                if r == Task.Preempted then
                    stats.preemptions += 1
                    task = queue.addAndPoll(task)
                else
                    stats.completions += 1
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

    private object stats:
        var executions  = 0L
        var preemptions = 0L
        var completions = 0L
        val submissions = new LongAdder
        val mounts      = new LongAdder

        val s = scope.scope("workers", id.toString())
        s.initGauge("executions")(executions.toDouble)
        s.initGauge("preemptions")(preemptions.toDouble)
        s.initGauge("completions")(completions.toDouble)
        s.initGauge("queue_size")(queue.size())
        s.initGauge("current_cycle")(currentCycle.toDouble)
        s.initGauge("submissions")(submissions.sum().toDouble)
        s.initGauge("mounts")(mounts.sum().toDouble)
    end stats

end Worker

private object Worker:
    private val local     = new ThreadLocal[Worker]
    def current(): Worker = local.get()
end Worker
