package kyo.scheduler.regulator

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.LongAdder
import java.util.function.DoubleSupplier
import java.util.function.LongSupplier
import kyo.scheduler.*
import kyo.scheduler.InternalTimer
import kyo.scheduler.top.AdmissionStatus
import kyo.scheduler.util.Flag
import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.util.hashing.MurmurHash3

final class Admission(
    loadAvg: DoubleSupplier,
    schedule: Task => Unit,
    nowMillis: LongSupplier,
    timer: InternalTimer,
    config: Config = Admission.defaultConfig
) extends Regulator(loadAvg, timer, config) {

    @volatile private var admissionPercent = 100

    private val rejected = new LongAdder
    private val allowed  = new LongAdder

    def percent(): Int = admissionPercent

    def reject(key: String): Boolean =
        reject(MurmurHash3.stringHash(key))

    def reject(): Boolean =
        reject(ThreadLocalRandom.current().nextInt())

    def reject(key: Int): Boolean = {
        val r =
            (key.abs % 100) > admissionPercent
        if (r) rejected.increment()
        else allowed.increment()
        r
    }

    final private class ProbeTask extends Task {
        val start = nowMillis.getAsLong()
        def run(startMillis: Long, clock: InternalClock) = {
            measure(nowMillis.getAsLong() - start)
            Task.Done
        }
    }

    protected def probe() =
        schedule(new ProbeTask)

    protected def update(diff: Int): Unit =
        admissionPercent = Math.max(0, Math.min(100, admissionPercent + diff))

    @nowarn("msg=unused")
    private val gauges =
        List(
            statsScope.gauge("percent")(admissionPercent),
            statsScope.counterGauge("allowed")(allowed.sum()),
            statsScope.counterGauge("rejected")(rejected.sum())
        )

    def status(): AdmissionStatus =
        AdmissionStatus(
            admissionPercent,
            allowed.sum(),
            rejected.sum(),
            regulatorStatus()
        )
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
