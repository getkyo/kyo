package kyo.scheduler

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

final class Worker(exec: Executor) extends Runnable:

    private val running = new AtomicBoolean

    @volatile private var cycle         = 0L
    @volatile private var current: Task = null

    private val queue = Queue[Task]()

    private val schedule = Scheduler.schedule(_, this)

    def enqueue(t: Task): Unit =
        queue.add(t)
        if !running.get() && running.compareAndSet(false, true) then
            exec.execute(this)
    end enqueue

    def load() =
        var l = queue.size()
        if current != null then
            l += 1
        l
    end load

    def steal(thief: Worker): Task =
        queue.steal(thief.queue)

    def drain(): Unit =
        queue.drain(schedule)

    def cycle(curr: Long): Unit =
        val c = current
        if c != null && cycle < curr - 1 then
            c.preempt()
    end cycle

    def run(): Unit =
        Worker.local.set(this)
        var task: Task = null
        while true do
            cycle = Coordinator.cycle()
            if task == null then
                task = queue.poll()
            if task == null then
                task = steal(this)
            if task != null then
                current = task
                val r = task.run()
                current = null
                if r == Task.Preempted then
                    task = queue.addAndPoll(task)
                else
                    task = null
                end if
            else
                running.set(false)
                if queue.isEmpty() || !running.compareAndSet(false, true) then
                    Worker.local.set(null)
                    return
            end if
        end while
    end run
end Worker

object Worker:
    private val local = new ThreadLocal[Worker]
    def current(): Worker =
        local.get()
end Worker
