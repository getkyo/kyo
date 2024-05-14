package kyo.scheduler.regulator

import kyo.scheduler.*
import kyo.scheduler.util.Flag
import scala.concurrent.duration.*

final class Concurrency(
    loadAvg: () => Double,
    updateConcurrency: Int => Unit,
    sleep: Int => Unit,
    nowNanos: () => Long,
    timer: InternalTimer,
    config: Config = Concurrency.defaultConfig
) extends Regulator(loadAvg, timer, config) {

    protected def probe() = {
        val start = nowNanos()
        sleep(1)
        measure(nowNanos() - start - 1000000)
    }

    protected def update(diff: Int): Unit =
        updateConcurrency(diff)

    def status(): Concurrency.AdmissionStatus =
        Concurrency.AdmissionStatus(
            regulatorStatus()
        )
}

object Concurrency {

    case class AdmissionStatus(
        regulator: Regulator.Status
    ) {
        infix def -(other: AdmissionStatus): AdmissionStatus =
            AdmissionStatus(regulator - other.regulator)
    }

    val defaultConfig: Config = Config(
        collectWindow = Flag("concurrency.collectWindow", 200),
        collectInterval = Flag("concurrency.collectIntervalMs", 10).millis,
        regulateInterval = Flag("concurrency.regulateIntervalMs", 1500).millis,
        jitterUpperThreshold = Flag("concurrency.jitterUpperThreshold", 800000),
        jitterLowerThreshold = Flag("concurrency.jitterLowerThreshold", 500000),
        loadAvgTarget = Flag("concurrency.loadAvgTarget", 0.8),
        stepExp = Flag("concurrency.stepExp", 1.2)
    )
}
