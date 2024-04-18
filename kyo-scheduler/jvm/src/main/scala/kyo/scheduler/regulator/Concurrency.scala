package kyo.scheduler.regulator

import java.util.concurrent.ScheduledExecutorService
import kyo.scheduler.*

final class Concurrency(
    loadAvg: () => Double,
    schedule: Task => Unit,
    updateConcurrency: Int => Unit,
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
) extends Regulator(loadAvg, executor, config):

    protected def probe(callback: Long => Unit) =
        val start = System.currentTimeMillis()
        schedule(Task(callback(System.currentTimeMillis() - start)))
    end probe

    protected def update(diff: Int): Unit =
        updateConcurrency(diff)

    override def toString(): String = "Concurrency()"

end Concurrency
