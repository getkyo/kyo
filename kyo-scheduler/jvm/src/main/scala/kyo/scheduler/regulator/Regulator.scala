package kyo.scheduler.regulator

import kyo.scheduler.InternalTimer
import kyo.scheduler.util.*
import kyo.stats.internal.MetricReceiver
import scala.util.control.NonFatal

abstract class Regulator(
    loadAvg: () => Double,
    timer: InternalTimer,
    config: Config
) {
    import config.*

    private var step         = 0
    private val measurements = new MovingStdDev(collectWindow)

    protected def probe(): Unit
    protected def update(diff: Int): Unit

    protected def measure(v: Long): Unit = {
        stats.measurement.observe(v.toDouble)
        synchronized(measurements.observe(v))
    }

    private val collectTask =
        timer.schedule(collectInterval)(collect())

    private val regulateTask =
        timer.schedule(regulateInterval)(adjust())

    final private def collect(): Unit = {
        try {
            stats.probe.inc()
            probe()
        } catch {
            case ex if NonFatal(ex) =>
                kyo.scheduler.bug(s"${getClass.getSimpleName()} regulator's probe collection has failed.", ex)
        }
    }

    final private def adjust() = {
        try {
            val jitter = synchronized(measurements.dev())
            val load   = loadAvg()
            if (jitter > jitterUpperThreshold) {
                if (step < 0) step -= 1
                else step = -1
            } else if (jitter < jitterLowerThreshold && load >= loadAvgTarget) {
                if (step > 0) step += 1
                else step = 1
            } else
                step = 0
            if (step != 0) {
                val pow = Math.pow(Math.abs(step), stepExp).toInt
                val delta =
                    if (step < 0) -pow
                    else pow
                stats.update.observe(delta)
                update(delta)
            } else
                stats.update.observe(0)
            stats.jitter.observe(jitter)
            stats.loadavg.observe(load)
        } catch {
            case ex if NonFatal(ex) =>
                kyo.scheduler.bug(s"${getClass.getSimpleName()} regulator's adjustment has failed.", ex)
        }
    }

    def stop(): Unit = {
        collectTask.cancel()
        regulateTask.cancel()
        ()
    }

    protected val statsScope = kyo.scheduler.statsScope("regulator", getClass.getSimpleName())

    private object stats {
        val receiver    = MetricReceiver.get
        val collect     = receiver.counter(statsScope, "collect")
        val adjust      = receiver.counter(statsScope, "adjust")
        val probe       = receiver.counter(statsScope, "probe")
        val loadavg     = receiver.histogram(statsScope, "loadavg")
        val measurement = receiver.histogram(statsScope, "measurement")
        val update      = receiver.histogram(statsScope, "update")
        val jitter      = receiver.histogram(statsScope, "jitter")
    }

}
