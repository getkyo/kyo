package kyo.scheduler.regulator

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kyo.scheduler.util.*
import scala.util.control.NonFatal

abstract class Regulator(
    loadAvg: () => Double,
    executor: ScheduledExecutorService,
    config: Config
):
    import config.*

    private var step         = 0
    private val pending      = new AtomicInteger
    private val measurements = MovingStdDev(collectWindowExp)

    protected def probe(callback: Long => Unit): Unit
    protected def update(diff: Int): Unit

    private val collectTask =
        executor.scheduleWithFixedDelay(() => collect(), collectIntervalMs, collectIntervalMs, TimeUnit.MILLISECONDS)

    private val regulateTask =
        executor.scheduleWithFixedDelay(() => regulate(), regulateIntervalMs, regulateIntervalMs, TimeUnit.MILLISECONDS)

    final private def collect(): Unit =
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

    final private def regulate() =
        try
            val jitter = synchronized(measurements.dev())
            println((this, "jitter", jitter))
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

    final def stop(): Unit =
        collectTask.cancel(true)
        regulateTask.cancel(true)
        ()
    end stop

end Regulator
