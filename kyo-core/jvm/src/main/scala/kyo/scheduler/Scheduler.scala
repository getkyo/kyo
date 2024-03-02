package kyo.scheduler

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import org.jctools.queues.MpmcUnboundedXaddArrayQueue
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] object Scheduler:

    private val printStatus = Flag("printStatus", false)

    private val coreWorkers = Flag(
        "coreWorkers",
        Math.min(1, Math.ceil(Runtime.getRuntime().availableProcessors().toDouble / 2).intValue())
    )

    @volatile
    private var concurrencyLimit = coreWorkers
    private val concurrency      = new AtomicInteger(0)

    private val idle = new MpmcUnboundedXaddArrayQueue[Worker](8)

    private val pool =
        val v = Thread.ofVirtual()
        try
            val field = v.getClass().getDeclaredField("scheduler")
            field.setAccessible(true)
            field.set(v, Executors.newCachedThreadPool(Threads("kyo-scheduler")))
        catch
            case ex if (NonFatal(ex)) =>
                Logs.logger.warn(
                    "Notice: Kyo's scheduler falling back to Loom's global ForkJoinPool, which might not be optimal. " +
                        "Enhance performance by adding '--add-opens=java.base/java.lang=ALL-UNNAMED' to JVM args for a dedicated thread pool."
                )
        end try
        Executors.newCachedThreadPool(v.name("kyo-worker").factory())
    end pool

    startWorkers()

    Coordinator.load()

    if printStatus then
        discard(Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
            (() => println(status())): Runnable,
            1,
            1,
            TimeUnit.SECONDS
        ))
    end if

    def removeWorker(): Unit =
        if concurrencyLimit > coreWorkers then
            concurrencyLimit = Math.max(1, concurrency.get() - 1)

    def addWorker(): Unit =
        concurrencyLimit = Math.max(concurrencyLimit, concurrency.get()) + 1
        startWorkers()

    private def startWorkers(): Unit =
        var c = concurrency.get()
        while c < concurrencyLimit && concurrency.compareAndSet(c, c + 1) do
            pool.execute(() => Worker.run())
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
        if Worker.all.size() <= 1 then
            return null
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

    def status(): String =
        val sb = new StringBuilder

        sb.append("===== Kyo Scheduler =====\n")
        sb.append(f"${"Load"}%-8s ${"Workers"}%-8s ${"Limit"}%-8s\n")
        sb.append(f"${loadAvg()}%-8.2f ${concurrency.get()}%-8d ${concurrencyLimit}%-8d\n")
        sb.append("\n")

        sb.append(
            f"${"Thread"}%-20s ${"Load"}%-5s ${"State"}%-15s ${"Frame"}%-30s\n"
        )

        Worker.all.iterator().forEachRemaining { worker =>
            sb.append(
                f"${worker.thread.getName}%-20s ${worker.load()}%-5.2f ${worker.thread.getState}%-15s ${worker.thread.getStackTrace()(0)}%-30s\n"
            )
        }
        sb.append("=========================\n")

        sb.toString()
    end status

    override def toString =
        s"Scheduler(loadAvg=${loadAvg()},concurrency=$concurrency,limit=$concurrencyLimit)"
end Scheduler
