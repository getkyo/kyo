package kyo.scheduler

import kyo.*

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport
import scala.annotation.tailrec
import org.jctools.queues.MpmcUnboundedXaddArrayQueue
import kyo.scheduler.IOTask
import kyo.scheduler.Coordinator
import kyo.scheduler.Flag
import kyo.scheduler.Threads
import kyo.scheduler.Worker
import kyo.scheduler.XSRandom

private[kyo] object Scheduler:

    private val coreWorkers = Flag(
        "coreWorkers",
        Math.ceil(Runtime.getRuntime().availableProcessors().toDouble / 2).intValue()
    )

    @volatile
    private var concurrencyLimit = coreWorkers
    private val concurrency      = new AtomicInteger(0)

    private val idle = new MpmcUnboundedXaddArrayQueue[Worker](8)
    private val pool = Executors.newCachedThreadPool(Threads("kyo-worker", new Worker(_)))

    startWorkers()
    Coordinator.load()

    def removeWorker(): Unit =
        if concurrencyLimit > coreWorkers then
            concurrencyLimit = Math.max(1, concurrency.get() - 1)

    def addWorker(): Unit =
        concurrencyLimit = Math.max(concurrencyLimit, concurrency.get()) + 1
        startWorkers()

    private def startWorkers(): Unit =
        var c = concurrency.get()
        while c < concurrencyLimit && concurrency.compareAndSet(c, c + 1) do
            pool.execute(() => Worker().runWorker(null))
            c = concurrency.get()
    end startWorkers

    def flush(): Unit =
        val w = Worker()
        if w != null then
            w.flush()
    end flush

    def schedule(t: IOTask[?]): Unit =
        val local = Worker()
        if local != null && local.enqueueLocal(t) then
            return
        schedule(t, local)
    end schedule

    @tailrec private[kyo] def schedule(t: IOTask[?], submitter: Worker): Unit =
        val w = idle.poll()
        if w != null && w != submitter && w.enqueue(t) then
            return
        var w0: Worker = randomWorker(submitter)
        var w1: Worker = randomWorker(submitter)
        if w0.load() > w1.load() then
            val w = w0
            w0 = w1
            w1 = w
        end if
        if !w0.enqueue(t) && !w1.enqueue(t) then
            schedule(t, submitter)
    end schedule

    def steal(thief: Worker): IOTask[?] =
        // p2c load stealing
        var r: IOTask[?] = null
        var w0: Worker   = randomWorker(thief)
        var w1: Worker   = randomWorker(thief)
        if w0.load() < w1.load() then
            val w = w0
            w0 = w1
            w1 = w
        end if
        r = w0.steal(thief)
        if r == null then
            r = w1.steal(thief)
        r
    end steal

    def loadAvg(): Double =
        var sum = 0L
        val it  = Worker.all.iterator()
        var c   = 0
        while it.hasNext() do
            sum += it.next().load()
            c += 1
        sum.doubleValue() / c
    end loadAvg

    def cycle(): Unit =
        Worker.all.forEach(_.cycle())

    def idle(w: Worker): Unit =
        if w.load() == 0 then
            idle.add(w)
            w.park()

    def stopWorker(): Boolean =
        val c = concurrency.get()
        c > concurrencyLimit && concurrency.compareAndSet(c, c - 1)

    private def randomWorker(besides: Worker): Worker =
        var w: Worker = null
        while w == null || w == besides do
            try
                val a = Worker.all
                w = a.get(XSRandom.nextInt(a.size()))
            catch
                case _: ArrayIndexOutOfBoundsException | _: IllegalArgumentException =>
        end while
        w
    end randomWorker

    override def toString =
        s"Scheduler(loadAvg=${loadAvg()},concurrency=$concurrency,limit=$concurrencyLimit)"
end Scheduler
