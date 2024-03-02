package kyo.scheduler

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport
import kyo.iosInternal.*

final private class Worker:

    val thread        = Thread.currentThread()
    private val queue = new Queue[IOTask[_]]()

    @volatile private var running                = false
    @volatile private var currentTask: IOTask[?] = null
    @volatile private var parkedThread: Thread   = null

    private val schedule = (t: IOTask[?]) => Scheduler.schedule(t, this)

    def park() =
        parkedThread = thread
        LockSupport.parkNanos(this, 1000000L) // 1ms
        parkedThread = null
    end park

    def unpark() =
        LockSupport.unpark(parkedThread)

    def steal(thief: Worker): IOTask[?] =
        queue.steal(thief.queue)

    def enqueueLocal(t: IOTask[?]): Boolean =
        running && queue.offer(t)

    def enqueue(t: IOTask[?]): Boolean =
        running && queue.offer(t) && {
            unpark()
            true
        }

    def cycle(): Unit =
        val t = currentTask
        if t != null && !queue.isEmpty() then
            t.preempt()
    end cycle

    def flush(): Unit =
        queue.drain(schedule)

    def load(): Int =
        var s = queue.size()
        if currentTask != null then
            s += 1
        s
    end load

    private def runWorker() =
        var task: IOTask[?] = null
        def stop() =
            !running || {
                val stop = Scheduler.stopWorker()
                if stop then
                    running = false
                stop
            }
        running = true
        Worker.all.add(this)
        while !stop() do
            if task == null then
                task = queue.poll()
            if task != null then
                currentTask = task
                task.run()
                currentTask = null
                if task.reenqueue() then
                    task = queue.addAndPoll(task)
                else
                    task = null
                end if
            else
                task = Scheduler.steal(this)
                if task == null then
                    Scheduler.idle(this)
            end if
        end while
        Worker.all.remove(this)
        running = false
        if task != null then
            queue.add(task)
        flush()
    end runWorker

    override def toString =
        s"Worker(thread=${thread.getName},load=${load()},task=$currentTask,queue.size=${queue.size()},frame=${thread.getStackTrace()(0)})"
end Worker

private object Worker:
    private[kyo] val all = new CopyOnWriteArrayList[Worker]
    private val local    = ThreadLocal.withInitial(() => new Worker)

    def run(): Unit =
        local.get().runWorker()

    def current(): Worker =
        local.get()
end Worker
