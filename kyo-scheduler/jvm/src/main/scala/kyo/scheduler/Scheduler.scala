package kyo.scheduler

import Scheduler.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.regulator.Admission
import kyo.scheduler.regulator.Concurrency
import kyo.scheduler.util.Flag
import kyo.scheduler.util.LoomSupport
import kyo.scheduler.util.Threads
import kyo.scheduler.util.XSRandom
import kyo.stats.internal.MetricReceiver
import kyo.stats.internal.UnsafeGauge
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

final class Scheduler(
    workerExecutor: Executor = Scheduler.defaultExecutor,
    clockExecutor: Executor = InternalClock.defaultExecutor,
    scheduledExecutor: ScheduledExecutorService = Scheduler.defaultScheduledExecutor,
    config: Config = Config.default
):

    import config.*

    private val pool    = LoomSupport.tryVirtualize(virtualizeWorkers, workerExecutor)
    private val clock   = InternalClock(clockExecutor)
    private val workers = new Array[Worker](maxWorkers)
    private val flushes = new LongAdder

    @volatile private var allocatedWorkers = 0
    @volatile private var maxConcurrency   = coreWorkers

    val a1, a2, a3, a4, a5, a6, a7 = 0L // padding

    @volatile private var cycles = 0L

    val b1, b2, b3, b4, b5, b6, b7 = 0L // padding

    ensureWorkers()

    private val admissionRegulator =
        Admission(loadAvg, scheduledExecutor)

    private val concurrencyRegulator =
        Concurrency(loadAvg, schedule, updateWorkers, scheduledExecutor)

    def schedule(t: Task): Unit =
        schedule(t, null)

    def reject(keyPath: Seq[String]): Boolean =
        admissionRegulator.reject(keyPath)

    def asExecutor: Executor =
        (r: Runnable) => schedule(Task(r.run()))

    def asExecutionContext: ExecutionContext =
        ExecutionContext.fromExecutor(asExecutor)

    @tailrec
    private def schedule(t: Task, submitter: Worker): Unit =
        var worker: Worker = null
        if submitter == null then
            worker = Worker.current();
        if worker == null then
            val m       = this.maxConcurrency
            var i       = XSRandom.nextInt(m)
            var tries   = Math.min(m, scheduleTries)
            var minLoad = Int.MaxValue
            while tries > 0 && minLoad != 0 do
                val w = workers(i)
                if w != null && !w.handleBlocking() then
                    val l = w.load()
                    if l < minLoad && (w ne submitter) then
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

    private def steal(thief: Worker): Task =
        var worker: Worker = null
        var i              = 0
        var maxLoad        = Int.MaxValue
        while i < maxConcurrency do
            val w = workers(i)
            if w != null && !w.handleBlocking() then
                val l = w.load()
                if l > maxLoad && (w ne thief) then
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
            flushes.increment()
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

    def shutdown(): Unit =
        cycleTask.cancel(true)
        admissionRegulator.stop()
        concurrencyRegulator.stop()
        gauges.close()
    end shutdown

    private def updateWorkers(delta: Int) =
        maxConcurrency = Math.max(minWorkers, Math.min(maxWorkers, maxConcurrency + delta))
        ensureWorkers()

    private def ensureWorkers() =
        for idx <- allocatedWorkers until maxConcurrency do
            workers(idx) = new Worker(idx, pool, schedule, steal, () => cycles, clock)
            allocatedWorkers += 1

    private val cycleTask =
        scheduledExecutor.scheduleAtFixedRate(
            () => cycleWorkers(),
            timeSliceMs,
            timeSliceMs,
            TimeUnit.MILLISECONDS
        )

    private def cycleWorkers(): Unit =
        cycles += 1
        var i    = 0
        val curr = cycles
        while i < allocatedWorkers do
            val w = workers(i)
            if w != null then
                w.cycle(curr)
                if i >= maxWorkers then
                    w.drain()
            end if
            i += 1
        end while
    end cycleWorkers

    private val gauges =
        val scope    = List("kyo", "scheduler")
        val receiver = MetricReceiver.get
        UnsafeGauge.all(
            receiver.gauge(scope, "max_concurrency")(maxConcurrency),
            receiver.gauge(scope, "allocated_workers")(allocatedWorkers),
            receiver.gauge(scope, "load_avg")(loadAvg()),
            receiver.gauge(scope, "flushes")(flushes.sum().toDouble)
        )
    end gauges

end Scheduler

object Scheduler:

    private[Scheduler] lazy val defaultExecutor          = Executors.newCachedThreadPool(Threads("kyo-scheduler-worker"))
    private[Scheduler] lazy val defaultScheduledExecutor = Executors.newScheduledThreadPool(2, Threads("kyo-scheduler-timer"))

    val get = Scheduler()

    case class Config(
        cores: Int,
        coreWorkers: Int,
        minWorkers: Int,
        maxWorkers: Int,
        scheduleTries: Int,
        virtualizeWorkers: Boolean,
        timeSliceMs: Int
    )
    object Config:
        val default: Config =
            val cores             = Runtime.getRuntime().availableProcessors()
            val coreWorkers       = Math.max(1, Flag("coreWorkers", cores))
            val minWorkers        = Math.max(1, Flag("minWorkers", coreWorkers.toDouble / 2).intValue())
            val maxWorkers        = Math.max(minWorkers, Flag("maxWorkers", coreWorkers * 100))
            val scheduleTries     = Math.max(1, Flag("scheduleTries", 8))
            val virtualizeWorkers = Flag("virtualizeWorkers", false)
            val timeSliceMs       = Flag("timeSliceMs", 5)
            Config(cores, coreWorkers, minWorkers, maxWorkers, scheduleTries, virtualizeWorkers, timeSliceMs)
        end default
    end Config
end Scheduler
