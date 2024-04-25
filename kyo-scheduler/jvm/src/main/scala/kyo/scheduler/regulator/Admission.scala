package kyo.scheduler.regulator

import java.util.concurrent.ThreadLocalRandom
import kyo.scheduler.*
import kyo.scheduler.InternalTimer
import kyo.scheduler.util.Flag
import kyo.stats.internal.MetricReceiver
import scala.concurrent.duration.*
import scala.util.hashing.MurmurHash3

final class Admission(
    loadAvg: () => Double,
    schedule: Task => Unit,
    nowMillis: () => Long,
    timer: InternalTimer,
    config: Config = Admission.defaultConfig
) extends Regulator(loadAvg, timer, config) {

    @volatile private var admissionPercent = 100

    final private class ProbeTask extends Task {
        val start = nowMillis()
        def run(startMillis: Long, clock: InternalClock) = {
            measure(nowMillis() - start)
            Task.Done
        }
    }

    def percent(): Int = admissionPercent

    def reject(key: String): Boolean =
        reject(MurmurHash3.stringHash(key))

    def reject(): Boolean =
        reject(ThreadLocalRandom.current().nextInt())

    def reject(key: Int): Boolean = {
        val r =
            (key.abs % 100) > admissionPercent
        if (r) stats.rejected.inc()
        else stats.allowed.inc()
        r
    }

    protected def probe() =
        schedule(new ProbeTask)

    protected def update(diff: Int): Unit =
        admissionPercent = Math.max(0, Math.min(100, admissionPercent + diff))

    override def stop(): Unit = {
        stats.percent.close()
        super.stop()
    }

    private object stats {
        val receiver = MetricReceiver.get
        val percent  = receiver.gauge(statsScope, "percent")(admissionPercent)
        val allowed  = receiver.counter(statsScope, "allowed")
        val rejected = receiver.counter(statsScope, "rejected")
    }
}

object Admission {
    val defaultConfig: Config =
        Config(
            collectWindow = Flag("admission.collectWindow", 40),
            collectInterval = Flag("admission.collectIntervalMs", 100).millis,
            regulateInterval = Flag("admission.regulateIntervalMs", 1000).millis,
            jitterUpperThreshold = Flag("admission.jitterUpperThreshold", 100),
            jitterLowerThreshold = Flag("admission.jitterLowerThreshold", 80),
            loadAvgTarget = Flag("admission.loadAvgTarget", 0.8),
            stepExp = Flag("admission.stepExp", 1.5)
        )
}
