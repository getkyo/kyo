package kyo.scheduler

import Scheduler.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.regulator.Admission
import kyo.scheduler.regulator.Concurrency
import kyo.scheduler.util.Flag
import kyo.scheduler.util.LoomSupport
import kyo.scheduler.util.Threads
import kyo.scheduler.util.Top
import kyo.scheduler.util.XSRandom
import kyo.stats.internal.MetricReceiver
import kyo.stats.internal.UnsafeGauge
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.control.NonFatal

final class Scheduler(
    workerExecutor: Executor = Scheduler.defaultWorkerExecutor,
    clockExecutor: Executor = Scheduler.defaultClockExecutor,
    timerExecutor: ScheduledExecutorService = Scheduler.defaultTimerExecutor,
    config: Config = Config.default
) {

    import config.*

    private val pool    = LoomSupport.tryVirtualize(virtualizeWorkers, workerExecutor)
    private val clock   = new InternalClock(clockExecutor)
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
        new Admission(loadAvg, schedule, System.currentTimeMillis, timer)

    private val concurrencyRegulator =
        new Concurrency(loadAvg, updateWorkers, Thread.sleep(_), System.nanoTime, timer)

    private val top = new Top(status, enableTopJMX, enableTopConsoleMs, timer)

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

    private def schedule(task: Task, submitter: Worker): Unit = {
        val cycles = this.cycles
        @tailrec def loop(tries: Int = 0): Unit = {
            var worker: Worker = null
            if (submitter == null && tries == 0)
                worker = Worker.current()
            if (worker == null) {
                val currentWorkers = this.currentWorkers
                var position       = XSRandom.nextInt(currentWorkers)
                var stride         = Math.min(currentWorkers, scheduleStride)
                var minLoad        = Int.MaxValue
                while (stride > 0 && minLoad != 0) {
                    val candidate = workers(position)
                    if (
                        candidate != null &&
                        (candidate ne submitter) &&
                        candidate.checkAvailability(cycles)
                    ) {
                        val l = candidate.load()
                        if (l < minLoad) {
                            minLoad = l
                            worker = candidate
                        }
                    }
                    position += 1
                    if (position == currentWorkers)
                        position = 0
                    stride -= 1
                }
            }
            while (worker == null)
                worker = workers(XSRandom.nextInt(currentWorkers))
            if (!worker.enqueue(cycles, task, force = tries >= scheduleTries))
                loop(tries + 1)
        }
        loop()
    }

    private def steal(thief: Worker): Task = {
        val cycles         = this.cycles
        val currentWorkers = this.currentWorkers
        var worker: Worker = null
        var maxLoad        = 1
        var position       = 0
        while (position < currentWorkers) {
            val candidate = workers(position)
            if (
                candidate != null &&
                (candidate ne thief) &&
                candidate.checkAvailability(cycles)
            ) {
                val load = candidate.load()
                if (load > maxLoad) {
                    maxLoad = load
                    worker = candidate
                }
            }
            position += 1
        }
        if (worker != null)
            worker.stealingBy(thief)
        else
            null
    }

    def flush() = {
        val worker = Worker.current()
        if (worker != null) {
            flushes.increment()
            worker.drain()
        }
    }

    def loadAvg(): Double = {
        val currentWorkers =
            this.currentWorkers
        var position = 0
        var sum      = 0
        while (position < currentWorkers) {
            val w = workers(position)
            if (w != null)
                sum += w.load()
            position += 1
        }
        sum.toDouble / currentWorkers
    }

    def shutdown(): Unit = {
        cycleTask.cancel(true)
        admissionRegulator.stop()
        concurrencyRegulator.stop()
        gauges.close()
        top.close()
    }

    private def updateWorkers(delta: Int) = {
        currentWorkers = Math.max(minWorkers, Math.min(maxWorkers, currentWorkers + delta))
        ensureWorkers()
    }

    private def ensureWorkers() =
        for (idx <- allocatedWorkers until currentWorkers) {
            workers(idx) = new Worker(idx, pool, schedule, steal, () => cycles, clock)
            allocatedWorkers += 1
        }

    private val cycleTask =
        timerExecutor.scheduleAtFixedRate(
            () => cycleWorkers(),
            timeSliceMs,
            timeSliceMs,
            TimeUnit.MILLISECONDS
        )

    private def cycleWorkers(): Unit = {
        try {
            val cycles = this.cycles + 1
            this.cycles = cycles
            var position = 0
            while (position < allocatedWorkers) {
                val worker = workers(position)
                if (worker != null) {
                    if (position >= currentWorkers)
                        worker.drain()
                    worker.cycle(cycles)
                }
                position += 1
            }
        } catch {
            case ex if NonFatal(ex) =>
                bug(s"Worker cyclying has failed.", ex)
        }
    }

    private val gauges = {
        val scope    = statsScope()
        val receiver = MetricReceiver.get
        UnsafeGauge.all(
            receiver.gauge(scope, "current_workers")(currentWorkers),
            receiver.gauge(scope, "allocated_workers")(allocatedWorkers),
            receiver.gauge(scope, "load_avg")(loadAvg()),
            receiver.gauge(scope, "flushes")(flushes.sum().toDouble)
        )
    }

    def status(): Scheduler.Status = {
        def workerStatus(i: Int) = {
            workers(i) match {
                case null   => null
                case worker => worker.status()
            }
        }
        val activeWorkers =
            (0 until currentWorkers).map(workerStatus)
        val inactiveWorkers =
            (currentWorkers until allocatedWorkers).map(workerStatus)
        val (activeThreads, totalThreads) =
            workerExecutor match {
                case exec: ThreadPoolExecutor =>
                    (exec.getActiveCount(), exec.getPoolSize())
                case _ =>
                    (-1, -1)
            }
        Scheduler.Status(
            currentWorkers,
            allocatedWorkers,
            loadAvg(),
            flushes.sum(),
            activeThreads,
            totalThreads,
            activeWorkers,
            inactiveWorkers,
            admissionRegulator.status(),
            concurrencyRegulator.status()
        )
    }
}

object Scheduler {

    case class Status(
        currentWorkers: Int,
        allocatedWorkers: Int,
        loadAvg: Double,
        flushes: Long,
        activeThreads: Int,
        totalThreads: Int,
        activeWorkers: Seq[WorkerStatus],
        inactiveWorkers: Seq[WorkerStatus],
        admission: Admission.AdmissionStatus,
        concurrency: Concurrency.AdmissionStatus
    ) {
        private def delta(a: Seq[WorkerStatus], b: Seq[WorkerStatus]) =
            a.zipAll(b, null, null).map {
                case (a, null) => a
                case (null, b) => b
                case (a, b)    => a - b
            }

        infix def -(other: Status): Status =
            Status(
                currentWorkers,
                allocatedWorkers,
                loadAvg,
                flushes - other.flushes,
                activeThreads,
                totalThreads,
                delta(activeWorkers, other.activeWorkers),
                delta(inactiveWorkers, other.inactiveWorkers),
                admission - other.admission,
                concurrency - other.concurrency
            )
    }

    private lazy val defaultWorkerExecutor = Executors.newCachedThreadPool(Threads("kyo-scheduler-worker", new Worker.WorkerThread(_)))
    private lazy val defaultClockExecutor  = Executors.newSingleThreadExecutor(Threads("kyo-scheduler-clock"))
    private lazy val defaultTimerExecutor  = Executors.newScheduledThreadPool(2, Threads("kyo-scheduler-timer"))

    val get = new Scheduler()

    case class Config(
        cores: Int,
        coreWorkers: Int,
        minWorkers: Int,
        maxWorkers: Int,
        scheduleTries: Int,
        scheduleStride: Int,
        virtualizeWorkers: Boolean,
        timeSliceMs: Int,
        enableTopJMX: Boolean,
        enableTopConsoleMs: Int
    )
    object Config {
        val default: Config = {
            val cores             = Runtime.getRuntime().availableProcessors()
            val coreWorkers       = Math.max(1, Flag("coreWorkers", cores))
            val minWorkers        = Math.max(1, Flag("minWorkers", coreWorkers.toDouble / 2).intValue())
            val maxWorkers        = Math.max(minWorkers, Flag("maxWorkers", coreWorkers * 100))
            val scheduleTries     = Math.max(1, Flag("scheduleTries", 32))
            val scheduleStride    = Math.max(1, Flag("scheduleStride", 8))
            val virtualizeWorkers = Flag("virtualizeWorkers", false)
            val timeSliceMs       = Flag("timeSliceMs", 5)
            val enableTopJMX      = Flag("enableTopJMX", true)
            val enableTopConsole  = Flag("enableTopConsoleMs", 0)
            Config(
                cores,
                coreWorkers,
                minWorkers,
                maxWorkers,
                scheduleTries,
                scheduleStride,
                virtualizeWorkers,
                timeSliceMs,
                enableTopJMX,
                enableTopConsole
            )
        }
    }
}
