package kyo.scheduler

import java.util.concurrent.Executors
import kyo.Logs
import kyo.Stats
import kyo.scheduler.util.Flag
import kyo.scheduler.util.Threads
import kyo.scheduler.util.XSRandom
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] object Scheduler:

    private val cores = Runtime.getRuntime().availableProcessors()
    private val min   = Math.max(1, Flag("minWorkers", cores.toDouble / 2).intValue())
    private val max   = Math.max(min, Flag("maxWorkers", cores * 100))
    private val tries = Math.max(1, Flag("tries", 8))

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

    for i <- 0 until maxConcurrency do
        workers(i) = new Worker(i, stats.scope, exec)

    Coordinator.load()

    def addWorker() =
        stats.addWorker.inc()
        val m = maxConcurrency
        if m > allocatedWorkers && maxConcurrency < max then
            workers(m) = new Worker(m, stats.scope, exec)
            allocatedWorkers += 1
        maxConcurrency = m + 1
    end addWorker

    def removeWorker() =
        stats.removeWorker.inc()
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
                if w != null && !w.handleBlocking() then
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
        while worker == null do
            worker = workers(XSRandom.nextInt(maxConcurrency))
        if !worker.enqueue(t) then
            schedule(t, submitter)
    end schedule

    def steal(thief: Worker): Task =
        var worker: Worker = null
        var i              = 0
        var maxLoad        = Int.MaxValue
        while i < maxConcurrency do
            val w = workers(i)
            if w != null && !w.handleBlocking() then
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
        stats.flushes.inc()
        val w = Worker.current()
        if w != null then
            w.drain()
    end flush

    def loadAvg(): Double =
        val m = this.maxConcurrency
        var i = 0
        var r = 0
        while i < m do
            val w = workers(i)
            if w != null then
                r += w.load()
            i += 1
        end while
        r.toDouble / m
    end loadAvg

    def cycle(curr: Long): Unit =
        stats.cycles.inc()
        var i = 0
        while i < maxConcurrency do
            val w = workers(i)
            if w != null then
                w.cycle(curr)
            i += 1
        end while
        val w = workers(XSRandom.nextInt(maxConcurrency))
        if w != null then
            w.wakeup()
    end cycle

    private[scheduler] object stats:
        val scope = Stats.kyoScope.scope("scheduler")
        scope.initGauge("max_concurrency")(maxConcurrency)
        scope.initGauge("allocated_workers")(allocatedWorkers)
        scope.initGauge("load_avg")(loadAvg())
        val flushes      = scope.initCounter("flushes").unsafe
        val cycles       = scope.initCounter("cycles").unsafe
        val addWorker    = scope.initCounter("worker_add").unsafe
        val removeWorker = scope.initCounter("worker_remove").unsafe
    end stats

end Scheduler
