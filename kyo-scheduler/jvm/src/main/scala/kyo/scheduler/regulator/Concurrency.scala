package kyo.scheduler.regulator

import kyo.scheduler.*
import scala.concurrent.duration.*

final class Concurrency(
    loadAvg: () => Double,
    updateConcurrency: Int => Unit,
    sleep: Int => Unit,
    nowNanos: () => Long,
    timer: InternalTimer,
    config: Config =
        Config(
            collectWindow = 200, // 2 regulate intervals
            collectInterval = 10.millis,
            regulateInterval = 1500.millis,
            jitterUpperThreshold = 800000,
            jitterLowerThreshold = 500000,
            loadAvgTarget = 0.8,
            stepExp = 1.2
        )
) extends Regulator(loadAvg, timer, config):

    protected def probe() =
        val start = nowNanos()
        sleep(1)
        measure(nowNanos() - start - 1000000)
    end probe

    protected def update(diff: Int): Unit =
        updateConcurrency(diff)

end Concurrency
