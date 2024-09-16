package kyo.scheduler.regulator

import java.util.function.DoubleSupplier
import java.util.function.LongSupplier
import kyo.scheduler.*
import kyo.scheduler.top.ConcurrencyStatus
import kyo.scheduler.util.Flag
import scala.concurrent.duration.*

final class Concurrency(
    loadAvg: DoubleSupplier,
    updateConcurrency: Int => Unit,
    sleep: Int => Unit,
    nowNanos: LongSupplier,
    timer: InternalTimer,
    config: Config = Concurrency.defaultConfig
) extends Regulator(loadAvg, timer, config) {

    protected def probe() = {
        val start = nowNanos.getAsLong()
        sleep(1)
        measure(nowNanos.getAsLong() - start - 1000000)
    }

    protected def update(diff: Int): Unit =
        updateConcurrency(diff)

    def status(): ConcurrencyStatus =
        ConcurrencyStatus(
            regulatorStatus()
        )
}

object Concurrency {

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
