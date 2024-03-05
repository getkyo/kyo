package kyo.scheduler

import java.util.concurrent.Executors
import kyo.Logs
import kyo.scheduler.util.Flag
import kyo.scheduler.util.Threads
import kyo.scheduler.util.XSRandom
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] object Scheduler:

    private val cores = Runtime.getRuntime().availableProcessors()
    private val min   = Flag("minWorkers", Math.min(1, cores.toDouble / 2).intValue())
    private val max   = Flag("maxWorkers", cores * 100)
    private val tries = Flag("tries", 16)

    @volatile private var maxConcurrency   = cores
    @volatile private var allocatedWorkers = maxConcurrency

    private val workers = new Array[Worker](max)

    private val exec =
        val v = Thread.ofVirtual()
        try
            val field = v.getClass().getDeclaredField("scheduler")
            field.setAccessible(true)
            field.set(v, Executors.newCachedThreadPool(Threads("kyo-scheduler")))
        catch
            case ex if (NonFatal(ex)) =>
                Logs.logger.warn(
                    "Warning: Kyo's scheduler is using a less efficient system-wide thread pool. " +
                        "For better performance, add '--add-opens=java.base/java.lang=ALL-UNNAMED' to " +
                        "your JVM arguments to use a dedicated thread pool. This step is needed due to " +
                        "limitations in Loom with customizing thread executors."
                )
        end try
        Executors.newThreadPerTaskExecutor(v.name("kyo-worker").factory())
    end exec

    for idx <- 0 until max do
        workers(idx) = new Worker(exec)

    Coordinator.load()

    def addWorker() =
        val m = Math.min(maxConcurrency + 1, max)
        if m > allocatedWorkers && maxConcurrency < max then
            workers(m) = new Worker(exec)
            allocatedWorkers += 1
        maxConcurrency = m
    end addWorker

    def removeWorker() =
        maxConcurrency = Math.max(maxConcurrency - 1, min)

    def schedule(t: Task): Unit =
        schedule(t, null)

    @tailrec
    def schedule(t: Task, submitter: Worker): Unit =
        var worker: Worker = null
        if submitter == null then
            worker = Worker.current();
        if worker == null then
            val m       = this.maxConcurrency
            var i       = XSRandom.nextInt(m)
            var tries   = Math.min(m, this.tries)
            var minLoad = Int.MaxValue
            while tries > 0 && minLoad != 0 do
                val w = workers(i)
                if !w.handleBlocking() then
                    val l = w.load()
                    if l < minLoad && w != submitter then
                        minLoad = l
                        worker = w
                end if
                i += 1
                if i == m then
                    i = 0
                tries -= 1
            end while
        end if
        if !worker.enqueue(t) then
            schedule(t, submitter)
    end schedule

    def steal(thief: Worker): Task =
        var worker: Worker = null
        var i              = 0
        var maxLoad        = Int.MaxValue
        while i < maxConcurrency do
            val w = workers(i)
            if !w.handleBlocking() then
                val l = w.load()
                if l > maxLoad && w != thief then
                    maxLoad = l
                    worker = w
            end if
            i += 1
        end while
        if worker != null then
            worker.steal(thief)
        else
            null
        end if
    end steal

    def flush() =
        val w = Worker.current()
        if w != null then
            w.drain()
    end flush

    def loadAvg(): Double =
        val m = this.maxConcurrency
        var i = 0
        var r = 0
        while i < m do
            r += workers(i).load()
            i += 1
        r.toDouble / m
    end loadAvg

    def cycle(curr: Long): Unit =
        var i = 0
        while i < maxConcurrency do
            workers(i).cycle(curr)
            i += 1
        workers(XSRandom.nextInt(maxConcurrency)).wakeup()
    end cycle
end Scheduler
