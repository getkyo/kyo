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
import scala.util.control.NonFatal

final class Scheduler(
    workerExecutor: Executor = Scheduler.defaultWorkerExecutor,
    clockExecutor: Executor = Scheduler.defaultClockExecutor,
    timerExecutor: ScheduledExecutorService = Scheduler.defaultTimerExecutor,
    config: Config = Config.default
):

    import config.*

    private val pool    = LoomSupport.tryVirtualize(virtualizeWorkers, workerExecutor)
    private val clock   = InternalClock(clockExecutor)
    private val workers = new Array[Worker](maxWorkers)
    private val flushes = new LongAdder

    @volatile private var allocatedWorkers = 0
    @volatile private var currentWorkers   = coreWorkers

    val a1, a2, a3, a4, a5, a6, a7 = 0L // padding

    @volatile private var cycles = 0L

    val b1, b2, b3, b4, b5, b6, b7 = 0L // padding

    ensureWorkers()

    private val timer = InternalTimer(timerExecutor)

    private val admissionRegulator =
        Admission(loadAvg, schedule, System.currentTimeMillis, timer)

    private val concurrencyRegulator =
        Concurrency(loadAvg, updateWorkers, Thread.sleep, System.nanoTime, timer)

    def schedule(task: Task): Unit =
        schedule(task, null)

    def reject(): Boolean =
        admissionRegulator.reject()

    def reject(key: String): Boolean =
        admissionRegulator.reject(key)

    def reject(key: Int): Boolean =
        admissionRegulator.reject(key)

    def asExecutor: Executor =
        (r: Runnable) => schedule(Task(r.run()))

    def asExecutionContext: ExecutionContext =
        ExecutionContext.fromExecutor(asExecutor)

    private def schedule(task: Task, submitter: Worker): Unit =
        val cycles = this.cycles
        @tailrec def loop(tries: Int = 0): Unit =
            var worker: Worker = null
            if submitter == null && tries == 0 then
                worker = Worker.current()
            if worker == null then
                val currentWorkers = this.currentWorkers
                var position       = XSRandom.nextInt(currentWorkers)
                var stride         = Math.min(currentWorkers, scheduleStride)
                var minLoad        = Int.MaxValue
                while stride > 0 && minLoad != 0 do
                    val candidate = workers(position)
                    if candidate != null && candidate.checkAvailability(cycles) then
                        val l = candidate.load()
                        if l < minLoad && (candidate ne submitter) then
                            minLoad = l
                            worker = candidate
                    end if
                    position += 1
                    if position == currentWorkers then
                        position = 0
                    stride -= 1
                end while
            end if
            while worker == null do
                worker = workers(XSRandom.nextInt(currentWorkers))
            if !worker.enqueue(cycles, task, force = tries >= scheduleTries) then
                loop(tries + 1)
        end loop
        loop()
    end schedule

    private def steal(thief: Worker): Task =
        val cycles         = this.cycles
        val currentWorkers = this.currentWorkers
        var worker: Worker = null
        var maxLoad        = 1
        var position       = 0
        while position < currentWorkers do
            val candidate = workers(position)
            if candidate != null &&
                (candidate ne thief) &&
                candidate.checkAvailability(cycles)
            then
                val load = candidate.load()
                if load > maxLoad then
                    maxLoad = load
                    worker = candidate
            end if
            position += 1
        end while
        if worker != null then
            worker.stealingBy(thief)
        else
            null
        end if
    end steal

    def flush() =
        val worker = Worker.current()
        if worker != null then
            flushes.increment()
            worker.drain()
    end flush

    def loadAvg(): Double =
        val currentWorkers =
            this.currentWorkers
        var position = 0
        var sum      = 0
        while position < currentWorkers do
            val w = workers(position)
            if w != null then
                sum += w.load()
            position += 1
        end while
        sum.toDouble / currentWorkers
    end loadAvg

    def shutdown(): Unit =
        cycleTask.cancel(true)
        admissionRegulator.stop()
        concurrencyRegulator.stop()
        gauges.close()
    end shutdown

    private def updateWorkers(delta: Int) =
        currentWorkers = Math.max(minWorkers, Math.min(maxWorkers, currentWorkers + delta))
        ensureWorkers()

    private def ensureWorkers() =
        for idx <- allocatedWorkers until currentWorkers do
            workers(idx) = new Worker(idx, pool, schedule, steal, () => cycles, clock)
            allocatedWorkers += 1

    private val cycleTask =
        timerExecutor.scheduleAtFixedRate(
            () => cycleWorkers(),
            timeSliceMs,
            timeSliceMs,
            TimeUnit.MILLISECONDS
        )

    private def cycleWorkers(): Unit =
        try
            val cycles = this.cycles + 1
            this.cycles = cycles
            var position = 0
            while position < allocatedWorkers do
                val worker = workers(position)
                if worker != null then
                    if position >= currentWorkers then
                        worker.drain()
                    worker.cycle(cycles)
                end if
                position += 1
            end while
        catch
            case ex if NonFatal(ex) =>
                bug(s"Worker cyclying has failed.", ex)
    end cycleWorkers

    private val gauges =
        val scope    = statsScope()
        val receiver = MetricReceiver.get
        UnsafeGauge.all(
            receiver.gauge(scope, "current_workers")(currentWorkers),
            receiver.gauge(scope, "allocated_workers")(allocatedWorkers),
            receiver.gauge(scope, "load_avg")(loadAvg()),
            receiver.gauge(scope, "flushes")(flushes.sum().toDouble)
        )
    end gauges

end Scheduler

object Scheduler:

    private lazy val defaultWorkerExecutor = Executors.newCachedThreadPool(Threads("kyo-scheduler-worker", new Worker.WorkerThread(_)))
    private lazy val defaultClockExecutor  = Executors.newSingleThreadExecutor(Threads("kyo-scheduler-clock"))
    private lazy val defaultTimerExecutor  = Executors.newScheduledThreadPool(2, Threads("kyo-scheduler-timer"))

    val get = Scheduler()

    case class Config(
        cores: Int,
        coreWorkers: Int,
        minWorkers: Int,
        maxWorkers: Int,
        scheduleTries: Int,
        scheduleStride: Int,
        virtualizeWorkers: Boolean,
        timeSliceMs: Int
    )
    object Config:
        val default: Config =
            val cores             = Runtime.getRuntime().availableProcessors()
            val coreWorkers       = Math.max(1, Flag("coreWorkers", cores))
            val minWorkers        = Math.max(1, Flag("minWorkers", coreWorkers.toDouble / 2).intValue())
            val maxWorkers        = Math.max(minWorkers, Flag("maxWorkers", coreWorkers * 100))
            val scheduleTries     = Math.max(1, Flag("scheduleTries", 32))
            val scheduleStride    = Math.max(1, Flag("scheduleStride", 8))
            val virtualizeWorkers = Flag("virtualizeWorkers", false)
            val timeSliceMs       = Flag("timeSliceMs", 5)
            Config(cores, coreWorkers, minWorkers, maxWorkers, scheduleTries, scheduleStride, virtualizeWorkers, timeSliceMs)
        end default
    end Config
end Scheduler
