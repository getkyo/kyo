package kyo.scheduler.regulator

import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.InternalTimer
import kyo.scheduler.top.RegulatorStatus
import kyo.scheduler.util.*
import scala.util.control.NonFatal

abstract class Regulator(
    loadAvg: () => Double,
    timer: InternalTimer,
    config: Config
) {
    import config.*

    private var step            = 0
    private val measurements    = new MovingStdDev(collectWindow)
    private val probesSent      = new LongAdder
    private val probesCompleted = new LongAdder
    private val adjustments     = new LongAdder
    private val updates         = new LongAdder

    protected def probe(): Unit
    protected def update(diff: Int): Unit

    private val collectTask =
        timer.schedule(collectInterval)(collect())

    private val regulateTask =
        timer.schedule(regulateInterval)(adjust())

    final private def collect(): Unit = {
        try {
            probesSent.increment()
            probe()
        } catch {
            case ex if NonFatal(ex) =>
                kyo.scheduler.bug(s"${getClass.getSimpleName()} regulator's probe collection has failed.", ex)
        }
    }

    protected def measure(v: Long): Unit = {
        probesCompleted.increment()
        stats.measurement.observe(Math.max(0, v.toDouble))
        synchronized(measurements.observe(v))
    }

    final private def adjust() = {
        try {
            adjustments.increment()
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
                updates.increment()
                update(delta)
            }
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
    }

    protected val statsScope = kyo.scheduler.statsScope.scope("regulator", getClass.getSimpleName().toLowerCase())

    private object stats {
        val loadavg     = statsScope.histogram("loadavg")
        val measurement = statsScope.histogram("measurement")
        val update      = statsScope.histogram("update")
        val jitter      = statsScope.histogram("jitter")
        val gauges = List(
            statsScope.gauge("probes_sent")(probesSent.sum().toDouble),
            statsScope.gauge("probes_completed")(probesSent.sum().toDouble),
            statsScope.gauge("adjustments")(adjustments.sum().toDouble),
            statsScope.gauge("updates")(updates.sum.toDouble)
        )
    }

    protected def regulatorStatus(): RegulatorStatus =
        RegulatorStatus(
            step,
            measurements.avg(),
            measurements.dev(),
            probesSent.sum(),
            probesCompleted.sum(),
            adjustments.sum(),
            updates.sum()
        )
}
