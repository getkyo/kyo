package kyo.scheduler

import Regulator.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kyo.scheduler.Task.Result
import kyo.scheduler.util.*
import scala.util.control.NonFatal

class Regulator(
    name: String,
    loadAvg: () => Double,
    probe: (Long => Unit) => Unit,
    update: Int => Unit,
    executor: ScheduledExecutorService,
    config: Config
):
    import config.*

    private var step         = 0
    private val pending      = new AtomicInteger
    private val measurements = MovingStdDev(collectWindowExp)

    private val collectTask =
        executor.scheduleWithFixedDelay(() => collect(), collectIntervalMs, collectIntervalMs, TimeUnit.MILLISECONDS)

    private val regulateTask =
        executor.scheduleWithFixedDelay(() => regulate(), regulateIntervalMs, regulateIntervalMs, TimeUnit.MILLISECONDS)

    private def collect(): Unit =
        try
            val samples = collectSamples - pending.get()
            if loadAvg() < loadAvgTarget then
                synchronized {
                    var i = 0
                    while i < samples do
                        measurements.observe(0)
                        i += 1
                }
            else
                var i = 0
                while i < samples do
                    probe(callback)
                    i += 1
            end if
        catch
            case ex if NonFatal(ex) =>
                ex.printStackTrace()
    end collect

    private val callback =
        (v: Long) =>
            pending.decrementAndGet()
            synchronized(measurements.observe(v))

    private def regulate() =
        try
            val jitter = synchronized(measurements.dev())
            println((name, "jitter", jitter))
            if jitter > jitterUpperThreshold then
                if step < 0 then step -= 1
                else step = -1
            else if jitter < jitterLowerThreshold && loadAvg() >= loadAvgTarget then
                if step > 0 then step += 1
                else step = 1
            else
                step = 0
            end if
            update(Math.pow(step, stepExp).toInt)
        catch
            case ex if NonFatal(ex) =>
                ex.printStackTrace()
    end regulate

    def close(): Unit =
        collectTask.cancel(true)
        regulateTask.cancel(true)
        ()
    end close

end Regulator

object Regulator:
    case class Config(
        collectWindowExp: Int,
        collectIntervalMs: Int,
        collectSamples: Int,
        regulateIntervalMs: Int,
        jitterUpperThreshold: Double,
        jitterLowerThreshold: Double,
        loadAvgTarget: Double,
        stepExp: Double
    )

    def threadSchedulingDelay(
        loadAvg: () => Double,
        update: Int => Unit,
        executor: ScheduledExecutorService,
        config: Config =
            Config(
                collectWindowExp = 11, // 2^11=2048 ~2 regulate intervals
                collectIntervalMs = 100,
                collectSamples = 10,
                regulateIntervalMs = 1000,
                jitterUpperThreshold = 1000000,
                jitterLowerThreshold = 800000,
                loadAvgTarget = 0.8,
                stepExp = 1.3
            )
    ): Regulator =
        Regulator(
            "threadSchedulingDelay",
            loadAvg,
            probe = cb =>
                val start = System.nanoTime()
                Thread.sleep(1)
                cb(System.nanoTime() - start - 1000000)
            ,
            update,
            executor,
            config
        )

    def admission(
        loadAvg: () => Double,
        schedule: Task => Unit,
        update: Int => Unit,
        executor: ScheduledExecutorService,
        config: Config =
            Config(
                collectWindowExp = 9, // 2^9=512 ~5 regulate intervals
                collectIntervalMs = 1000,
                collectSamples = 10,
                regulateIntervalMs = 5000,
                jitterUpperThreshold = 100,
                jitterLowerThreshold = 80,
                loadAvgTarget = 0.8,
                stepExp = 1.5
            )
    ): Regulator =
        Regulator(
            "admission",
            loadAvg,
            probe = cb =>
                val start = System.currentTimeMillis()
                schedule(Task(cb(System.currentTimeMillis() - start)))
            ,
            update,
            executor,
            config
        )
end Regulator
