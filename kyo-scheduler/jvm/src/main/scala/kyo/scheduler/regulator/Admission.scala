package kyo.scheduler.regulator

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.*
import kyo.scheduler.InternalTimer
import kyo.scheduler.util.Flag
import kyo.stats.internal.MetricReceiver
import kyo.stats.internal.UnsafeGauge
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
        val start = nowMillis()
        def run(startMillis: Long, clock: InternalClock) = {
            measure(nowMillis() - start)
            Task.Done
        }
    }

    protected def probe() =
        schedule(new ProbeTask)

    protected def update(diff: Int): Unit =
        admissionPercent = Math.max(0, Math.min(100, admissionPercent + diff))

    override def stop(): Unit = {
        gauges.close()
        super.stop()
    }

    private val gauges = {
        val receiver = MetricReceiver.get
        UnsafeGauge.all(
            receiver.gauge(statsScope, "percent")(admissionPercent),
            receiver.gauge(statsScope, "allowed")(allowed.sum().toDouble),
            receiver.gauge(statsScope, "rejected")(rejected.sum().toDouble)
        )
    }

    def status(): Admission.AdmissionStatus =
        Admission.AdmissionStatus(
            admissionPercent,
            allowed.sum(),
            rejected.sum(),
            regulatorStatus()
        )
}

object Admission {

    case class AdmissionStatus(
        admissionPercent: Int,
        allowed: Long,
        rejected: Long,
        regulator: Regulator.Status
    ) {
        infix def -(other: AdmissionStatus): AdmissionStatus =
            AdmissionStatus(
                admissionPercent - other.admissionPercent,
                allowed - other.allowed,
                rejected - other.rejected,
                regulator - other.regulator
            )
    }

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
