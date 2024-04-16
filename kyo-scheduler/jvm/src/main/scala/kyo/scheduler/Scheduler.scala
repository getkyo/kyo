package kyo.scheduler

import Scheduler.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.util.Flag
import kyo.scheduler.util.LoomSupport
import kyo.scheduler.util.Threads
import kyo.scheduler.util.XSRandom
import kyo.stats.internal.MetricReceiver
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

object Scheduler:

    lazy val get = Scheduler()

    case class Config(
        cores: Int,
        coreWorkers: Int,
        minWorkers: Int,
        maxWorkers: Int,
        scheduleTries: Int,
        virtualizeWorkers: Boolean
    )
    object Config:
        val default: Config =
            val cores: Int                 = Runtime.getRuntime().availableProcessors()
            val coreWorkers: Int           = Math.max(1, Flag("coreWorkers", cores))
            val minWorkers: Int            = Math.max(1, Flag("minWorkers", coreWorkers.toDouble / 2).intValue())
            val maxWorkers: Int            = Math.max(minWorkers, Flag("maxWorkers", coreWorkers * 100))
            val scheduleTries: Int         = Math.max(1, Flag("scheduleTries", 8))
            val virtualizeWorkers: Boolean = Flag("virtualizeWorkers", false)
            Config(cores, coreWorkers, minWorkers, maxWorkers, scheduleTries, virtualizeWorkers)
        end default
    end Config
end Scheduler

final class Scheduler(
    executor: Executor = Executors.newCachedThreadPool(Threads("kyo-scheduler")),
    config: Config = Config.default
):

    import config.*

    @volatile private var maxConcurrency   = coreWorkers
    @volatile private var allocatedWorkers = maxConcurrency

    private val workers = new Array[Worker](maxWorkers)

    private val exec =
        if virtualizeWorkers then
            LoomSupport.tryVirtualize(executor)
        else
            executor
        end if
    end exec

    for i <- 0 until maxConcurrency do
        workers(i) = new Worker(i, exec, schedule, steal, coordinator.currentCycle)

    val coordinator: Coordinator =
        Coordinator.load(Thread.sleep, addWorker, removeWorker, cycleWorkers, loadAvg)

    def schedule(t: Task): Unit =
        schedule(t, null)

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
        stats.flushes.increment()
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

    def currentTick(): Long =
        coordinator.currentTick()

    private def addWorker() =
        val m = maxConcurrency
        if m < maxWorkers then
            if m > allocatedWorkers then
                workers(m) = new Worker(m, exec, schedule, steal, coordinator.currentCycle)
                allocatedWorkers += 1
            maxConcurrency = m + 1
        end if
    end addWorker

    private def removeWorker() =
        maxConcurrency = Math.max(maxConcurrency - 1, minWorkers)

    private def cycleWorkers(curr: Long): Unit =
        var i = 0
        while i < allocatedWorkers do
            val w = workers(i)
            if w != null then
                w.cycle(curr)
            i += 1
        end while
        val w = workers(XSRandom.nextInt(maxConcurrency))
        if w != null then
            w.wakeup()
    end cycleWorkers

    def asExecutor: Executor =
        (r: Runnable) => schedule(Task(r.run()))

    def asExecutionContext: ExecutionContext =
        ExecutionContext.fromExecutor(asExecutor)

    private[scheduler] object stats:
        val flushes  = new LongAdder
        val scope    = List("kyo", "scheduler")
        val receiver = MetricReceiver.get

        receiver.gauge(scope, "max_concurrency")(maxConcurrency)
        receiver.gauge(scope, "allocated_workers")(allocatedWorkers)
        receiver.gauge(scope, "load_avg")(loadAvg())
        receiver.gauge(scope, "flushes")(flushes.sum().toDouble)
    end stats

end Scheduler
